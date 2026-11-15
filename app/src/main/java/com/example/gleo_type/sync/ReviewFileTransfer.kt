package com.example.gleo_type.sync

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Explicit local-file exchange adapter: caller moves encrypted files using an approved USB/SAF
 * route. No sockets, server, provider, discovery or automatic user-data export. IO-only. */
class ReviewFileTransfer(private val journalFile:File,private val outbox:DurableSyncOutbox,private val peer:ReviewPeer) {
    private val journal=SyncJournal(journalFile)
    private fun lease(j:JSONObject):SyncLease {
        return SyncLease(j.getString("operationId"),j.getString("token"),ReviewSyncBundle.decode(j.getString("bundle")),j.getInt("attempt"))
    }
    fun prepare(nowEpochMs:Long):String? = journal.update{state->
        val previous=state.optJSONObject("pending")
        if(previous!=null && outbox.isCurrent(previous.getString("operationId"),previous.getString("token")))
            return@update previous.getString("wire") // Restart/manual transfer reuses the same durable request.
        state.remove("pending")
        val next=outbox.claim(nowEpochMs,300000) ?: return@update null
        require(next.bundle.localUserId==peer.localUser && next.bundle.sourceDeviceId==peer.localDevice)
        val wire=peer.seal("review",JSONObject().put("operationId",next.operationId).put("leaseToken",next.token)
            .put("payload",next.bundle.encode()).put("contentHash",next.bundle.contentHash))
        state.put("pending",JSONObject().put("operationId",next.operationId).put("token",next.token).put("attempt",next.attempt)
            .put("bundle",next.bundle.encode()).put("wire",wire))
        wire
    }
    fun acceptAck(wire:String):Boolean=journal.update{state->
        val current=state.optJSONObject("pending") ?: return@update false
        val pending=lease(current);val ack=peer.open(wire,"ack")
        require(ack.keys().asSequence().toSet()==setOf("operationId","leaseToken","contentHash","bundleId","revision","receipt","storage"))
        require(ack.getString("operationId")==pending.operationId && ack.getString("leaseToken")==pending.token &&
            ack.getString("contentHash")==pending.bundle.contentHash && ack.getString("bundleId")==pending.bundle.bundleId &&
            ack.getLong("revision")==pending.bundle.revision && ack.getString("storage")=="DURABLE_INBOX_NOT_HEARTS" &&
            ack.getString("receipt") in setOf("APPLIED","DUPLICATE","STALE"))
        val applied=outbox.acknowledge(pending)
        if(applied || !outbox.isCurrent(pending.operationId,pending.token))state.remove("pending")
        applied
    }
    fun retry(atEpochMs:Long):Boolean=journal.update{state->
        val current=state.optJSONObject("pending") ?: return@update false
        val applied=outbox.fail(lease(current),"MANUAL_TRANSFER_RETRY",atEpochMs)
        if(applied)state.remove("pending");applied
    }
    companion object {
        /** No overwrite; path is chosen by the local caller, never from the bundle. */
        fun writeNew(file:File,wire:String) {
            val bytes=wire.toByteArray(Charsets.UTF_8);require(bytes.size<=800000)
            check(file.createNewFile()){"Existing transfer file preserved"}
            FileOutputStream(file).use{it.write(bytes);it.fd.sync()}
        }
        fun receiveFile(request:File,ack:File,receiver:ReviewBundleReceiver) {
            require(request.isFile&&request.length() in 1..800000)
            val response=receiver.receive(request.readText(Charsets.UTF_8)) // Durable commit BEFORE ack file.
            writeNew(ack,response)
        }
    }
}
