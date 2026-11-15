package com.example.gleo_type
import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.music.MusicPlaybackService
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture
/** Actual source-only service. No private audio/model/profile. */
class SubmissionCatalogTest {
 @Test fun realBrowserHasValidEmptyCatalog() {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  assertEquals("com.gleohearts.submission",c.packageName)
  var pending:ListenableFuture<MediaBrowser>?=null
  i.runOnMainSync {pending=MediaBrowser.Builder(c,SessionToken(c,ComponentName(c,MusicPlaybackService::class.java))).buildAsync()}
  val browser=pending!!.get(30,TimeUnit.SECONDS)
  try {
   var root:ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>?=null
   i.runOnMainSync {root=browser.getLibraryRoot(null)}
   val parent=root!!.get(30,TimeUnit.SECONDS).value!!
   assertEquals(true,parent.mediaMetadata.isBrowsable)
   var children:ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>?=null
   i.runOnMainSync {children=browser.getChildren(parent.mediaId,0,100,null)}
   val result=children!!.get(30,TimeUnit.SECONDS)
   assertEquals(0,result.resultCode);assertTrue(result.value!!.isEmpty())
   i.runOnMainSync {assertFalse(browser.isPlaying);assertEquals(0,browser.mediaItemCount)}
  } finally {i.runOnMainSync {browser.release()}}
 }
}
