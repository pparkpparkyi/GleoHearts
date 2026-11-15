package com.example.gleo_type.sync.security

import com.example.gleo_type.sync.ReviewPeer
import org.json.JSONObject
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Existing v1 P-256/HKDF transcript, tested for interoperability with ReviewDevicePairing.
 * Only the confirmed shared key persists, encrypted by the local vault. Pending offers are ephemeral. */
class PersistentReviewPairing(private val user:String,private val device:String,private val vault:PeerKeyVault,
    private val clock:()->Long={System.nanoTime()/1_000_000},private val offerTtlMs:Long=120000) {
    init{require(user.isNotBlank()&&device.isNotBlank()&&user.length<=1024&&device.length<=1024)}
    private val key=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    private val createdAt=clock()
    val offer:String=canonical(JSONObject().put("version",1).put("user",user).put("device",device)
        .put("session",java.util.UUID.randomUUID().toString()).put("publicKey",Base64.getEncoder().encodeToString(key.public.encoded)))
    private fun agreement(remote:String):Pair<PeerBinding,ByteArray> {
        require(offerTtlMs in 1000..300000&&clock()-createdAt in 0..offerTtlMs){"Pairing offer expired; create a fresh session"}
        require(remote.toByteArray(Charsets.UTF_8).size<=8192)
        val j=JSONObject(remote);require(j.keys().asSequence().toSet()==setOf("version","user","device","session","publicKey"))
        require(j.getInt("version")==1&&listOf("user","device","session").all{j.getString(it).length in 1..1024})
        val binding=PeerBinding(user,device,j.getString("user"),j.getString("device"))
        val peer=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(j.getString("publicKey")))) as java.security.interfaces.ECPublicKey
        val own=key.public as java.security.interfaces.ECPublicKey
        require(peer.params.curve==own.params.curve&&peer.params.order==own.params.order&&peer.params.generator==own.params.generator)
        val shared=KeyAgreement.getInstance("ECDH").apply{init(key.private);doPhase(peer,true)}.generateSecret()
        val salt=MessageDigest.getInstance("SHA-256").digest(listOf(offer,canonical(j)).sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        fun hmac(k:ByteArray,data:ByteArray)=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(k,"HmacSHA256"))}.doFinal(data)
        val extracted=try{hmac(salt,shared)}finally{shared.fill(0)}
        val derived=try{hmac(extracted,"gleo-review-pair-v1\u0001".toByteArray(Charsets.UTF_8))}finally{extracted.fill(0)}
        return binding to derived
    }
    fun fingerprint(remote:String):String {val (_,secret)=agreement(remote)
        return try{MessageDigest.getInstance("SHA-256").digest(secret+"verify".toByteArray()).take(8).joinToString(""){"%02X".format(it.toInt() and 255)}}finally{secret.fill(0)}}
    /** UI must compare on BOTH devices via a separate trusted channel. Equal user IDs are not consent. */
    fun confirm(remote:String,enteredFingerprint:String,userConfirmedComparison:Boolean):ReviewPeer {
        require(userConfirmedComparison&&MessageDigest.isEqual(enteredFingerprint.toByteArray(Charsets.UTF_8),fingerprint(remote).toByteArray(Charsets.UTF_8)))
        val (binding,secret)=agreement(remote)
        try{vault.enroll(binding,secret,enteredFingerprint)}finally{secret.fill(0)}
        return vault.open(binding)
    }
}
