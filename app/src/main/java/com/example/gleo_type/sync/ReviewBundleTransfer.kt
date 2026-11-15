package com.example.gleo_type.sync

import org.json.JSONObject
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Explicitly provisioned trust only. No discovery, string-ID auto-trust or unauthenticated import.
 * Key enrollment/revocation and an approved physical transport are separate prerequisites. */
class ReviewPeer(val localUser:String,val localDevice:String,val remoteUser:String,val remoteDevice:String,key:ByteArray) {
    private val secret=key.copyOf()
    init {require(secret.size==32 && listOf(localUser,localDevice,remoteUser,remoteDevice).all{it.isNotBlank()&&it.length<=1024});require(localDevice!=remoteDevice)}
    fun seal(type:String,body:JSONObject):String {
        require(type in setOf("review","ack"))
        val header=JSONObject().put("version",1).put("type",type).put("senderUser",localUser).put("senderDevice",localDevice)
            .put("targetUser",remoteUser).put("targetDevice",remoteDevice)
        val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(secret,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(SyncJson.canonical(header).toByteArray(Charsets.UTF_8))
        val bytes=cipher.doFinal(SyncJson.canonical(body).toByteArray(Charsets.UTF_8))
        return JSONObject().put("header",header).put("nonce",Base64.getEncoder().encodeToString(nonce))
            .put("ciphertext",Base64.getEncoder().encodeToString(bytes)).toString()
    }
    fun open(wire:String,type:String):JSONObject {
        require(wire.toByteArray(Charsets.UTF_8).size<=800_000)
        val j=JSONObject(wire);require(j.keys().asSequence().toSet()==setOf("header","nonce","ciphertext"))
        val h=j.getJSONObject("header")
        require(h.keys().asSequence().toSet()==setOf("version","type","senderUser","senderDevice","targetUser","targetDevice"))
        require(h.getInt("version")==1 && h.getString("type")==type && h.getString("senderUser")==remoteUser &&
            h.getString("senderDevice")==remoteDevice && h.getString("targetUser")==localUser && h.getString("targetDevice")==localDevice)
        val nonce=Base64.getDecoder().decode(j.getString("nonce"));require(nonce.size==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(secret,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(SyncJson.canonical(h).toByteArray(Charsets.UTF_8))
        return JSONObject(cipher.doFinal(Base64.getDecoder().decode(j.getString("ciphertext"))).toString(Charsets.UTF_8))
    }
}

/** No network implementation is configured. The link must be explicitly supplied by an approved caller. */
fun interface ReviewExchange { fun exchange(encryptedRequest:String):String }

class ReviewBundleReceiver(private val peer:ReviewPeer,private val inbox:DurableReviewInbox) {
    init {require(peer.localUser==inbox.ownerId)}
    fun receive(wire:String):String {
        val j=peer.open(wire,"review");require(j.keys().asSequence().toSet()==setOf("operationId","leaseToken","payload","contentHash"))
        val bundle=ReviewSyncBundle.decode(j.getString("payload"))
        require(bundle.localUserId==peer.remoteUser && bundle.sourceDeviceId==peer.remoteDevice && bundle.contentHash==j.getString("contentHash"))
        require(j.getString("operationId")==SyncJson.id(bundle.bundleId,bundle.revision.toString(),bundle.contentHash))
        require(j.getString("leaseToken").length in 1..128)
        val accepted=inbox.accept(bundle.encode()) // Durable commit before ACK creation.
        require(accepted in setOf(SyncAcceptance.APPLIED,SyncAcceptance.DUPLICATE,SyncAcceptance.STALE))
        return peer.seal("ack",JSONObject().put("operationId",j.getString("operationId")).put("leaseToken",j.getString("leaseToken"))
            .put("contentHash",bundle.contentHash).put("bundleId",bundle.bundleId).put("revision",bundle.revision)
            .put("receipt",accepted.name).put("storage","DURABLE_INBOX_NOT_HEARTS"))
    }
}

enum class TransferOutcome { IDLE, ACKNOWLEDGED, RETRY_SCHEDULED, LEASE_SUPERSEDED }
class ReviewBundleDispatcher(private val outbox:DurableSyncOutbox,private val peer:ReviewPeer,private val link:ReviewExchange) {
    /** Blocking IO boundary; never called on an SDK/Media3/main callback. No automatic background loop. */
    fun sendOne(nowEpochMs:Long):TransferOutcome {
        val lease=outbox.claim(nowEpochMs) ?: return TransferOutcome.IDLE
        return try {
            require(lease.bundle.localUserId==peer.localUser && lease.bundle.sourceDeviceId==peer.localDevice)
            val wire=peer.seal("review",JSONObject().put("operationId",lease.operationId).put("leaseToken",lease.token)
                .put("payload",lease.bundle.encode()).put("contentHash",lease.bundle.contentHash))
            val ack=peer.open(link.exchange(wire),"ack")
            require(ack.keys().asSequence().toSet()==setOf("operationId","leaseToken","contentHash","bundleId","revision","receipt","storage"))
            require(ack.getString("operationId")==lease.operationId && ack.getString("leaseToken")==lease.token &&
                ack.getString("contentHash")==lease.bundle.contentHash && ack.getString("bundleId")==lease.bundle.bundleId &&
                ack.getLong("revision")==lease.bundle.revision && ack.getString("storage")=="DURABLE_INBOX_NOT_HEARTS" &&
                ack.getString("receipt") in setOf("APPLIED","DUPLICATE","STALE"))
            check(outbox.acknowledge(lease));TransferOutcome.ACKNOWLEDGED
        } catch(_:Exception) {
            val delay=(1000L shl (lease.attempt-1).coerceIn(0,8)).coerceAtMost(300000L)
            if(outbox.fail(lease,"TRANSFER_OR_AUTHENTICATION_FAILED",Math.addExact(nowEpochMs,delay)))TransferOutcome.RETRY_SCHEDULED
            else TransferOutcome.LEASE_SUPERSEDED
        }
    }
}
