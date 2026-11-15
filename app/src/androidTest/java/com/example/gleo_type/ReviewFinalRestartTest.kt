package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.LocalArchiveRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Invoked in a separate instrumentation process after the Review interaction test. Cache only. */
class ReviewFinalRestartTest {
 @Test fun selectedHeartsPersistWithoutDuplicateAfterProcessRestart() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(File(context.cacheDir,"review-final-archive-path.txt").readText()).canonicalFile
  require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
  val pins=LocalArchiveRepository(root).load()
  assertEquals(2,pins.size)
  assertEquals(setOf("SYNTHETIC_B","SYNTHETIC_C"),pins.map{it.track!!.id}.toSet())
  assertEquals(2,pins.map{it.id}.toSet().size)
  assertTrue(pins.all{it.heartsReview!!.excludedMusicIds==listOf("SYNTHETIC_A")})
 }
}
