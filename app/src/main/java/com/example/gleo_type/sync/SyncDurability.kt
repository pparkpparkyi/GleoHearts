package com.example.gleo_type.sync

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object SyncJson {
 fun canonical(v:Any?):String=when(v){
  null,JSONObject.NULL->"null"
  is JSONObject->v.keys().asSequence().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
  is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
  is Number->java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
  is Boolean->v.toString()
  else->JSONObject.quote(v.toString())
 }
 fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
 fun id(vararg parts:String)=hash(parts.joinToString(""){"${it.length}:$it"})
 fun optional(j:JSONObject,key:String)=if(!j.has(key)||j.isNull(key))null else j.getString(key)
}

/** Bounded append-only state commits. A broken tail is preserved and blocks writes. IO-only. */
internal class SyncJournal(private val file:File) {
 companion object {private val locks=ConcurrentHashMap<String,Any>()}
 private val lock=locks.computeIfAbsent(file.canonicalPath){Any()}
 fun <T> read(block:(JSONObject)->T):T=synchronized(lock){block(load())}
 fun <T> update(block:(JSONObject)->T):T=synchronized(lock){
  val state=load();val before=SyncJson.canonical(state);val result=block(state)
  val next=SyncJson.canonical(state)
  if(next!=before){
   val bytes=(next+"\n").toByteArray(Charsets.UTF_8);require(bytes.size<=1_048_576 && file.length()+bytes.size<=16L*1024*1024)
   check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs())
   RandomAccessFile(file,"rw").use{out->val end=out.length();try{out.seek(end);out.write(bytes);out.fd.sync()}catch(e:Exception){runCatching{out.setLength(end)};throw e}}
  };result
 }
 private fun load():JSONObject {
  if(!file.exists())return JSONObject().put("schemaVersion",1)
  require(file.length()<=16L*1024*1024);val bytes=file.readBytes()
  require(bytes.isNotEmpty() && bytes.last()==10.toByte()){"Incomplete sync journal; original preserved"}
  var last:JSONObject?=null
  bytes.toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.forEach{last=JSONObject(it).also{j->require(j.getInt("schemaVersion")==1)}}
  return requireNotNull(last)
 }
}

/** Local approval binding, NOT cryptographic device pairing or an account. Original user IDs remain intact. */
class SyncIdentityRepository(file:File,private val localUserId:String) {
 init{require(localUserId.isNotBlank()&&localUserId.length<=1024)}
 val ownerId get()=localUserId
 private val journal=SyncJournal(file)
 fun deviceId():String=journal.update{j->
  require(!j.has("owner")||j.getString("owner")==localUserId);j.put("owner",localUserId)
  if(!j.has("deviceId"))j.put("deviceId","sync_"+java.util.UUID.randomUUID())
  j.getString("deviceId")
 }
 fun authorizePeer(sourceUser:String,sourceDevice:String) {require(sourceUser.isNotBlank()&&sourceDevice.isNotBlank());journal.update{j->
  require(!j.has("owner")||j.getString("owner")==localUserId);j.put("owner",localUserId)
  val peers=j.optJSONObject("peers") ?: JSONObject().also{j.put("peers",it)}
  peers.put(SyncJson.id(sourceUser,sourceDevice),localUserId)
 }}
 fun revokePeer(sourceUser:String,sourceDevice:String){journal.update{require(it.optString("owner")==localUserId);it.optJSONObject("peers")?.remove(SyncJson.id(sourceUser,sourceDevice))}}
 fun accepts(sourceUser:String,sourceDevice:String)=journal.read{it.optString("owner")==localUserId&&it.optJSONObject("peers")?.optString(SyncJson.id(sourceUser,sourceDevice))==localUserId}
}
