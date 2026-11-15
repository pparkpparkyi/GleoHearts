package com.example.gleo_type

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Exercises the real LibraryResult validators; no mock bypasses isBrowsable checks. */
@RunWith(AndroidJUnit4::class)
class MusicLibraryBrowsingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> request(action: () -> ListenableFuture<T>): T {
        lateinit var future: ListenableFuture<T>
        instrumentation.runOnMainSync { future = action() }
        return future.get(15, TimeUnit.SECONDS)
    }
    private fun assertSong(item: MediaItem) {
        assertTrue(item.mediaId.startsWith("music_") ||
            item.mediaId in com.example.gleo_type.music.JamendoResearchCatalog.expectedIds)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertNotNull(item.mediaMetadata.title)
    }

    @Test fun rootItemsPaginationAndSearchDoNotCrash() {
        val context = instrumentation.targetContext
        val browser = request { MediaBrowser.Builder(context,
            SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
        try {
            val rootResult = request { browser.getLibraryRoot(null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, rootResult.resultCode)
            val root = rootResult.value!!
            assertEquals(true, root.mediaMetadata.isBrowsable)
            assertEquals(false, root.mediaMetadata.isPlayable)
            val all = request { browser.getChildren(root.mediaId, 0, 100, null) }.value!!
            val expectedIds = (1..8).map { "music_${it.toString().padStart(3, '0')}" } +
                com.example.gleo_type.music.JamendoResearchCatalog.load(context).map { it.musicId }
            assertEquals(expectedIds, all.map { it.mediaId })
            all.forEach(::assertSong)
            val first = request { browser.getChildren(root.mediaId, 0, 3, null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, first.resultCode)
            assertEquals(3, first.value!!.size)
            first.value!!.forEach(::assertSong)
            val second = request { browser.getChildren(root.mediaId, 1, 3, null) }.value!!
            assertEquals(listOf("music_004", "music_005", "music_006"), second.map { it.mediaId })
            val tail = request { browser.getChildren(root.mediaId, 2, 3, null) }.value!!
            assertEquals(expectedIds.drop(6).take(3), tail.map { it.mediaId })
            tail.forEach(::assertSong)
            assertTrue(request { browser.getChildren(root.mediaId, (expectedIds.size + 2) / 3, 3, null) }.value!!.isEmpty())
            val item = request { browser.getItem("music_001") }
            assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
            assertSong(item.value!!)
            assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, request { browser.getItem("missing") }.resultCode)
            assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE,
                request { browser.getChildren("missing", 0, 3, null) }.resultCode)
            assertEquals(LibraryResult.RESULT_SUCCESS, request { browser.search("Sample", null) }.resultCode)
            val results = request { browser.getSearchResult("Sample", 0, 20, null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, results.resultCode)
            assertEquals(7, results.value!!.size)
            results.value!!.forEach(::assertSong)
            assertEquals(LibraryResult.RESULT_SUCCESS, request { browser.search("no-such-title", null) }.resultCode)
            assertTrue(request { browser.getSearchResult("no-such-title", 0, 20, null) }.value!!.isEmpty())
        } finally {
            instrumentation.runOnMainSync { browser.release() }
        }
    }
}
