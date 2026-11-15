package com.example.gleo_type.sync

import org.json.JSONObject
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/** Ephemeral P-256 pairing. Offers are untrusted until users compare the SAME fingerprint
 * on both devices using a separate trusted channel. No automatic peer acceptance. */
class ReviewDevicePairing(private val user:String,private val device:String) {
    private val key=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    val offer:String=SyncJson.canonical(JSONObject().put("version",1).put("user",user).put("device",device)
        .put("session",java.util.UUID.randomUUID().toString()).put("publicKey",Base64.getEncoder().encodeToString(key.public.encoded)))
    init {require(user.isNotBlank()&&device.isNotBlank()&&user.length<=1024&&device.length<=1024)}
    private fun agreement(remote:String):Pair<JSONObject,ByteArray> {
        require(remote.toByteArray(Charsets.UTF_8).size<=8192)
        val j=JSONObject(remote);require(j.keys().asSequence().toSet()==setOf("version","user","device","session","publicKey"))
        require(j.getInt("version")==1 && j.getString("device")!=device && listOf("user","device","session").all{j.getString(it).length in 1..1024})
        val publicKey=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(j.getString("publicKey"))))
        val ec=publicKey as java.security.interfaces.ECPublicKey
        val local=key.public as java.security.interfaces.ECPublicKey
        require(ec.params.curve==local.params.curve && ec.params.order==local.params.order && ec.params.generator==local.params.generator)
        val shared=KeyAgreement.getInstance("ECDH").apply{init(key.private);doPhase(publicKey,true)}.generateSecret()
        val transcript=listOf(offer,SyncJson.canonical(j)).sorted().joinToString("\n")
        val salt=MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
        fun hmac(k:ByteArray,data:ByteArray)=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(k,"HmacSHA256"))}.doFinal(data)
        val extracted=hmac(salt,shared);shared.fill(0)
        val derived=hmac(extracted,"gleo-review-pair-v1\u0001".toByteArray(Charsets.UTF_8));extracted.fill(0)
        return j to derived
    }
    fun fingerprint(remote:String):String {val (_,secret)=agreement(remote)
        return try{MessageDigest.getInstance("SHA-256").digest(secret+"verify".toByteArray()).take(8).joinToString(""){"%02X".format(it.toInt() and 255)}}finally{secret.fill(0)}}
    /** Exact fingerprint entered/confirmed after comparison on BOTH devices. No UI auto-confirm. */
    fun confirm(remote:String,verifiedFingerprint:String):ReviewPeer {
        require(verifiedFingerprint==fingerprint(remote)){"Pairing fingerprint not verified"}
        val (j,secret)=agreement(remote)
        return try{ReviewPeer(user,device,j.getString("user"),j.getString("device"),secret)}finally{secret.fill(0)}
    }
}
