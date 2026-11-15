package com.example.gleo_type.internalfollowup

import com.example.gleo_type.sync.*
import com.example.gleo_type.sync.security.*
import com.example.gleo_type.ui.app.Track
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software protector is test-only. AndroidKeyStore persistence is NOT established by these tests. */
class PeerSecurityTest {
    private class TestProtector:PeerKeyProtector {
        val keys=ConcurrentHashMap<String,ByteArray>()
        override fun protect(alias:String,aad:ByteArray,secret:ByteArray):ByteArray {
            val k=ByteArray(32).also{SecureRandom().nextBytes(it)};check(keys.putIfAbsent(alias,k)==null)
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(k,"AES"));c.updateAAD(aad)
            return c.iv+c.doFinal(secret)
        }
        override fun unprotect(alias:String,aad:ByteArray,sealed:ByteArray):ByteArray {
            val k=keys[alias] ?: error("Test protector key missing")
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(k,"AES"),GCMParameterSpec(128,sealed.copyOfRange(0,12)));c.updateAAD(aad)
            return c.doFinal(sealed.copyOfRange(12,sealed.size))
        }
        override fun revoke(alias:String){keys.remove(alias)?.fill(0)}
    }
    private fun temp()=Files.createTempDirectory("gleo-peer-isolated").toFile()
    @Test fun existingPairingInteroperatesAndProtectedVaultReopens(){
        val root=temp();val protector=TestProtector();val binding=PeerBinding("a","car","b","phone")
        val vault=PeerKeyVault(File(root,"keys"),"a","car",protector)
        val a=PersistentReviewPairing("a","car",vault);val b=ReviewDevicePairing("b","phone")
        assertEquals(a.fingerprint(b.offer),b.fingerprint(a.offer))
        a.confirm(b.offer,a.fingerprint(b.offer),true)
        val phone=b.confirm(a.offer,b.fingerprint(a.offer))
        val reopened=PeerKeyVault(File(root,"keys"),"a","car",protector).open(binding)
        val wire=reopened.seal("review",JSONObject().put("synthetic","hello"))
        assertEquals("hello",phone.open(wire,"review").getString("synthetic"))
        assertFalse(File(root,"keys").readText().contains("privateKey"));assertFalse(wire.contains("hello"))
    }
    @Test fun noTrustWithoutUserComparisonEvenIfUserIdMatches(){
        val root=temp();val protector=TestProtector();val vault=PeerKeyVault(File(root,"keys"),"same","car",protector)
        val a=PersistentReviewPairing("same","car",vault);val b=ReviewDevicePairing("same","phone")
        assertTrue(runCatching{a.confirm(b.offer,a.fingerprint(b.offer),false)}.isFailure)
        assertTrue(runCatching{a.confirm(b.offer,"0000000000000000",true)}.isFailure)
        assertTrue(runCatching{vault.open(PeerBinding("same","car","same","phone"))}.isFailure)
    }
    @Test fun failedVaultCommitCleansNewWrappingKeyAndPreservesPreviousFile(){
        val file=File(temp(),"keys");val protector=TestProtector()
        file.writeText(JSONObject().put("version",1).put("owner","a").put("device","car").put("peers",JSONObject())
            .put("padding","x".repeat(1048400)).toString()+"\n")
        val original=file.readBytes();val a=PersistentReviewPairing("a","car",PeerKeyVault(file,"a","car",protector))
        val b=ReviewDevicePairing("b","phone")
        assertTrue(runCatching{a.confirm(b.offer,a.fingerprint(b.offer),true)}.isFailure)
        assertArrayEquals(original,file.readBytes());assertTrue(protector.keys.isEmpty())
    }
    @Test fun revocationDeletesWrappingKeyAndOldVaultRollbackCannotDecrypt(){
        val root=temp();val file=File(root,"keys");val p=TestProtector();val vault=PeerKeyVault(file,"a","car",p)
        val a=PersistentReviewPairing("a","car",vault);val b=ReviewDevicePairing("b","phone");val binding=PeerBinding("a","car","b","phone")
        a.confirm(b.offer,a.fingerprint(b.offer),true);val old=file.readBytes();assertEquals(1,p.keys.size)
        vault.revoke(binding);assertTrue(p.keys.isEmpty());assertTrue(runCatching{vault.open(binding)}.isFailure)
        file.writeBytes(old) // synthetic attack fixture only, never operating files.
        assertTrue(runCatching{PeerKeyVault(file,"a","car",p).open(binding)}.isFailure)
    }
    @Test fun wrongOwnerTamperingMissingKeyAndBrokenTailFailWithoutRegeneration(){
        val root=temp();val file=File(root,"keys");val p=TestProtector();val vault=PeerKeyVault(file,"a","car",p)
        val a=PersistentReviewPairing("a","car",vault);val b=ReviewDevicePairing("b","phone");val binding=PeerBinding("a","car","b","phone")
        a.confirm(b.offer,a.fingerprint(b.offer),true)
        assertTrue(runCatching{PeerKeyVault(file,"other","car",p).open(binding.copy(localUser="other"))}.isFailure)
        assertTrue(runCatching{PeerKeyVault(file,"a","car",TestProtector()).open(binding)}.isFailure)
        val original=file.readBytes();file.appendText("broken")
        val broken=file.readBytes();assertTrue(runCatching{vault.open(binding)}.isFailure);assertArrayEquals(broken,file.readBytes())
        file.writeBytes(original);val rows=file.readLines().map{JSONObject(it)};val last=rows.last();val r=last.getJSONObject("peers").let{it.getJSONObject(it.keys().next())}
        r.put("enrollment","tampered");file.writeText(rows.dropLast(1).joinToString("\n",postfix="\n")+last.toString()+"\n")
        assertTrue(runCatching{vault.open(binding)}.isFailure)
    }
    @Test fun framingRejectsOversizeBadMagicPartialAndInvalidUtf8(){
        fun bad(bytes:ByteArray)=assertTrue(runCatching{FramedReviewTransport.read(ByteArrayInputStream(bytes))}.isFailure)
        bad(byteArrayOf(0,1));bad(ByteArray(8))
        val out=ByteArrayOutputStream();DataOutputStream(out).apply{writeInt(0x47485231);writeInt(800001)};bad(out.toByteArray())
        val short=ByteArrayOutputStream();DataOutputStream(short).apply{writeInt(0x47485231);writeInt(3);write(1)};bad(short.toByteArray())
        val utf=ByteArrayOutputStream();DataOutputStream(utf).apply{writeInt(0x47485231);writeInt(1);write(0xff)};bad(utf.toByteArray())
        val good=ByteArrayOutputStream();FramedReviewTransport.write(good,"encrypted-fixture");assertEquals("encrypted-fixture",FramedReviewTransport.read(ByteArrayInputStream(good.toByteArray())))
        val reply=ByteArrayOutputStream();FramedReviewTransport.write(reply,"synthetic-encrypted-ack")
        val request=ByteArrayOutputStream();val link=FramedReviewTransport(ByteArrayInputStream(reply.toByteArray()),request)
        assertEquals("synthetic-encrypted-ack",link.exchange("synthetic-encrypted-review"))
        assertEquals("synthetic-encrypted-review",FramedReviewTransport.read(ByteArrayInputStream(request.toByteArray())))
    }
    @Test fun expiredOrReversedPairingClockCannotEnroll(){
        var now=1000L;val vault=PeerKeyVault(File(temp(),"keys"),"a","car",TestProtector())
        val a=PersistentReviewPairing("a","car",vault,{now},1000);val b=ReviewDevicePairing("b","phone")
        now=2001;assertTrue(runCatching{a.confirm(b.offer,a.fingerprint(b.offer),true)}.isFailure)
        now=999;assertTrue(runCatching{a.fingerprint(b.offer)}.isFailure)
    }
    @Test fun closingTransportCancelsBlockedReadWithoutExchangeMonitorDeadlock(){
        val entered=CountDownLatch(1);val cancelled=CountDownLatch(1)
        val blocking=object:InputStream(){
            override fun read():Int{entered.countDown();check(cancelled.await(5,TimeUnit.SECONDS));throw IOException("Synthetic channel closed")}
            override fun close(){cancelled.countDown()}
        }
        val transport=FramedReviewTransport(blocking,ByteArrayOutputStream());val worker=Executors.newSingleThreadExecutor()
        try{val result=worker.submit<Boolean>{runCatching{transport.exchange("encrypted-fixture")}.isFailure}
            assertTrue(entered.await(2,TimeUnit.SECONDS));transport.close();assertTrue(result.get(2,TimeUnit.SECONDS))
            assertTrue(runCatching{transport.exchange("again")}.isFailure)
        }finally{transport.close();worker.shutdownNow()}
    }
    private fun bundle()=ReviewSyncBundle("a","car","run","drive","run/drive",1,"2026-11-14T00:01:00Z","2026-11-14T00:00:00Z","2026-11-14T00:01:00Z","UTC",
        listOf(Track("music","Synthetic","Test")),listOf(ReviewOccurrence("pb","music",100.0,100.0,.1,"lower_bound",emptySet())),testData=true)
    @Test fun duplexFrameCommitBeforeAckDisconnectRetryDuplicateAndInboxDecisionSurviveReload(){
        val root=temp();val p=TestProtector();val vault=PeerKeyVault(File(root,"keys"),"a","car",p)
        val a=PersistentReviewPairing("a","car",vault);val b=ReviewDevicePairing("b","phone")
        val peer=a.confirm(b.offer,a.fingerprint(b.offer),true);val reverse=b.confirm(a.offer,b.fingerprint(a.offer))
        val identity=SyncIdentityRepository(File(root,"identity"),"b");identity.authorizePeer("a","car")
        val inbox=DurableReviewInbox(File(root,"inbox"),"b",identity);val receiver=ReviewBundleReceiver(reverse,inbox)
        val outbox=DurableSyncOutbox(File(root,"outbox"));val value=bundle();outbox.enqueue(value)
        // Real byte framing but in-memory byte streams, NOT CAR/MOBILE hardware or a network socket.
        fun channel(dropAck:Boolean):ReviewExchange=ReviewExchange{wire->
            val request=ByteArrayOutputStream();FramedReviewTransport.write(request,wire)
            val ack=ByteArrayOutputStream();FramedReviewTransport.receiveOne(ByteArrayInputStream(request.toByteArray()),ack,receiver)
            assertEquals(1,DurableReviewInbox(File(root,"inbox"),"b",identity).bundles().size)
            if(dropAck)throw EOFException("Synthetic disconnect after durable commit")
            FramedReviewTransport.read(ByteArrayInputStream(ack.toByteArray()))
        }
        assertEquals(TransferOutcome.RETRY_SCHEDULED,ReviewBundleDispatcher(outbox,peer,channel(true)).sendOne(0))
        inbox.setExcluded(value.bundleId,setOf("music"))
        val reopened=DurableSyncOutbox(File(root,"outbox"));val peerAfter=PeerKeyVault(File(root,"keys"),"a","car",p).open(PeerBinding("a","car","b","phone"))
        assertEquals(TransferOutcome.ACKNOWLEDGED,ReviewBundleDispatcher(reopened,peerAfter,channel(false)).sendOne(1000))
        assertEquals(1,inbox.bundles().size);assertEquals(setOf("music"),inbox.excluded(value.bundleId))
        assertEquals(OutboxState.acknowledged,reopened.states().values.single())
    }
}
