package com.example.gleo_type
import com.example.gleo_type.sync.*
import org.junit.Assert.*
import org.junit.Test
import com.example.gleo_type.ui.app.*

class ArchiveSyncContractTest {
    private fun row()=ArchiveSyncRecord("user","vehicle-device","event-17",1,"drive","jamendo_api_1204669",
        "playback","recommendation",null,"2026-11-13T01:00:00Z",ArchiveRecordKind.UNREVIEWED_MUSIC,"title","artist")
    @Test fun retriesOutOfOrderConflictsAndWrongUsersCannotOverwrite() {
        val inbox=ArchiveSyncInbox("user");val r=row()
        assertEquals(SyncAcceptance.APPLIED,inbox.acceptInIsolatedTest(r))
        assertEquals(SyncAcceptance.DUPLICATE,inbox.acceptInIsolatedTest(r))
        assertEquals(SyncAcceptance.CONFLICT,inbox.acceptInIsolatedTest(r.copy(musicId="other")))
        assertEquals(SyncAcceptance.WRONG_USER,inbox.acceptInIsolatedTest(r.copy(localUserId="someone-else")))
        assertEquals(SyncAcceptance.APPLIED,inbox.acceptInIsolatedTest(r.copy(revision=2,deleted=true,updatedAt="2020-01-01T00:00:00Z")))
        assertEquals(SyncAcceptance.STALE,inbox.acceptInIsolatedTest(r));assertTrue(inbox.snapshot().single().deleted)
        val reopened=ArchiveSyncInbox("user");reopened.restore(inbox.snapshot());assertEquals(inbox.snapshot(),reopened.snapshot())
        assertEquals(SyncAcceptance.TRANSPORT_NOT_CONFIGURED,reopened.sendToVehicleOrServer(r))
    }
    @Test fun originsNeverMergeByTitleMusicOrTimeAndPendingRetainsCausalIds() {
        val inbox=ArchiveSyncInbox("user");val r=row()
        inbox.acceptInIsolatedTest(r);inbox.acceptInIsolatedTest(r.copy(sourceDeviceId="phone"))
        assertEquals(2,inbox.snapshot().size);assertEquals("playback",r.toMap()["playbackSessionId"])
        assertEquals("recommendation",r.toMap()["recommendationId"]);assertNull(r.archiveId)
        assertEquals(ArchiveSyncState.LOCAL_ONLY,r.syncState)
    }
    @Test(expected=IllegalArgumentException::class) fun archiveNeedsExplicitArchiveId() {row().copy(kind=ArchiveRecordKind.HEARTS_ARCHIVE)}
    @Test fun pendingProjectionUsesExactPlaybackIdsAndNeverTimestampJoins() {
        val drive=DriveRecord("run/drive","drive","2026-11-13T00:00:00Z","2026-11-13T00:01:00Z",listOf("a","b"),0.0,false,null,true,
            playbacks=listOf(PlaybackArchive("pb-a","a",0.0,null,null,null,emptySet())))
        val rows=ArchiveSyncProjection.unreviewed(drive,listOf(Track("a","A","artist")),"user","vehicle",true,1,mapOf("pb-a" to "rec-a","unrelated" to "rec-b"))
        assertEquals(2,rows.size);assertEquals("rec-a",rows.single{it.musicId=="a"}.recommendationId)
        assertNull(rows.single{it.musicId=="b"}.playbackSessionId);assertNull(rows.single{it.musicId=="b"}.recommendationId)
        assertTrue(rows.all{it.archiveId==null && it.syncState==ArchiveSyncState.LOCAL_ONLY})
    }
    @Test fun archiveProjectionNeedsExplicitMutationTimeAndNeverGuessesPlaybackFromReviewIds() {
        val at="2026-11-13T00:00:00Z";val changed="2026-11-13T00:02:00Z"
        val review=HeartsReviewMetadata("user",at,"Asia/Seoul",listOf("a"),emptyList(),listOf("pb-a","pb-b"),emptySet(),emptySet())
        val pin=Pin("archive",at,"drive",Track("a","A","artist"),null,emptyList(),"",heartsReview=review)
        val row=ArchiveSyncProjection.heart(pin,"vehicle",2,changed)
        assertEquals("archive",row.archiveId);assertEquals("user",row.localUserId);assertEquals(changed,row.updatedAt)
        assertNull(row.playbackSessionId);assertNull(row.recommendationId)
    }
}
