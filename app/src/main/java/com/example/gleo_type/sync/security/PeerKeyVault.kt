package com.example.gleo_type.sync.security

import com.example.gleo_type.sync.ReviewPeer
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface PeerKeyProtector {
    /** On failure preserve pre-existing aliases and clean up only keys created by this call. */
    fun protect(alias:String,aad:ByteArray,secret:ByteArray):ByteArray
    fun unprotect(alias:String,aad:ByteArray,sealed:ByteArray):ByteArray
    fun revoke(alias:String)
}
data class PeerBinding(val localUser:String,val localDevice:String,val remoteUser:String,val remoteDevice:String) {
    init{require(listOf(localUser,localDevice,remoteUser,remoteDevice).all{it.isNotBlank()&&it.length<=1024});require(localDevice!=remoteDevice)}
    internal fun json()=JSONObject().put("localUser",localUser).put("localDevice",localDevice).put("remoteUser",remoteUser).put("remoteDevice",remoteDevice)
    internal fun aad(enrollment:String)=("gleo-peer-vault-v1\n"+enrollment+"\n"+canonical(json())).toByteArray(Charsets.UTF_8)
    internal fun id()=digest(canonical(json()).toByteArray(Charsets.UTF_8))
}
internal fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
internal fun canonical(j:JSONObject):String=j.keys().asSequence().sorted().joinToString(",","{","}"){k->
    JSONObject.quote(k)+":"+when(val v=j.get(k)){is JSONObject->canonical(v);is Number,is Boolean->v.toString();else->JSONObject.quote(v.toString())}}

/** Append-only, bounded, fd.sync commit and OS file lock. Store ONLY under app no_backup.
 * No cleartext key export. Loss/invalidation of protector keys requires fresh user pairing. */
class PeerKeyVault(private val file:File,private val owner:String,private val device:String,private val protector:PeerKeyProtector) {
    companion object{private val locks=ConcurrentHashMap<String,Any>()}
    private val lock=locks.computeIfAbsent(file.canonicalPath){Any()}
    init{require(owner.isNotBlank()&&device.isNotBlank())}
    private fun key(binding:PeerBinding):String{require(binding.localUser==owner&&binding.localDevice==device);return binding.id()}
    private fun <T> state(write:Boolean,block:(JSONObject)->T):T=synchronized(lock){
        check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs())
        RandomAccessFile(file,"rw").use{raf->raf.channel.lock().use{
            require(raf.length()<=8L*1024*1024)
            val data=ByteArray(raf.length().toInt());raf.readFully(data)
            require(data.isEmpty()||data.last()==10.toByte()){ "Incomplete peer vault; preserve and re-pair separately" }
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString()
            var state=JSONObject().put("version",1).put("owner",owner).put("device",device).put("peers",JSONObject())
            decoded.lineSequence().filter{it.isNotBlank()}.forEach{line->state=JSONObject(line)
                require(state.getInt("version")==1&&state.getString("owner")==owner&&state.getString("device")==device)}
            val before=canonical(state);val result=block(state);val next=canonical(state)
            if(next!=before){check(write);val bytes=(next+"\n").toByteArray(Charsets.UTF_8)
                require(bytes.size<=1_048_576&&raf.length()+bytes.size<=8L*1024*1024)
                val end=raf.length();try{raf.seek(end);raf.write(bytes);raf.fd.sync()}catch(e:Exception){runCatching{raf.setLength(end)};throw e}}
            result
        }}
    }
    internal fun enroll(binding:PeerBinding,secret:ByteArray,verifiedFingerprint:String):String {
        require(secret.size==32&&verifiedFingerprint.matches(Regex("[A-F0-9]{16}")))
        val id=key(binding)
        var allocatedAlias:String?=null
        return try { state(true){s->val peers=s.getJSONObject("peers");check(peers.optJSONObject(id)?.optBoolean("active")!=true){"Active peer must be revoked before re-pairing"}
            val enrollment=UUID.randomUUID().toString();val alias="gleo.review.peer.$enrollment"
                val sealed=protector.protect(alias,binding.aad(enrollment),secret)
                allocatedAlias=alias
                require(sealed.size in 48..4096)
                peers.put(id,JSONObject().put("active",true).put("binding",binding.json()).put("enrollment",enrollment)
                    .put("alias",alias).put("sealed",Base64.getEncoder().encodeToString(sealed)))
                enrollment
        }}catch(e:Exception){allocatedAlias?.let{alias->runCatching{protector.revoke(alias)}};throw e}
    }
    fun open(binding:PeerBinding):ReviewPeer {
        val id=key(binding)
        return state(false){s->val record=s.getJSONObject("peers").getJSONObject(id)
            require(record.getBoolean("active")&&canonical(record.getJSONObject("binding"))==canonical(binding.json()))
            val sealed=Base64.getDecoder().decode(record.getString("sealed"));require(sealed.size in 48..4096)
            val secret=protector.unprotect(record.getString("alias"),binding.aad(record.getString("enrollment")),sealed)
            try{require(secret.size==32);ReviewPeer(binding.localUser,binding.localDevice,binding.remoteUser,binding.remoteDevice,secret)}finally{secret.fill(0)}
        }
    }
    fun revoke(binding:PeerBinding){val id=key(binding)
        // Tombstone durable first. A keystore failure must never silently reactivate trust.
        val alias=state(true){s->s.getJSONObject("peers").getJSONObject(id).let{r->r.put("active",false);r.getString("alias")}}
        protector.revoke(alias)
    }
}
