package com.example.gleo_type

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.LocalArchiveRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only fresh cache stores and existing approved audio; never writes the operating user archive. */
class Step9DemoTest {
 @get:Rule val ui=createAndroidComposeRule<Step9DemoActivity>()
 @Test fun actualPlaybackLocalRememberAndAlternative(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  ui.waitUntil(40000){runCatching{ui.onNodeWithTag("step9_energetic").assertIsEnabled();true}.getOrDefault(false)}
  ui.onNodeWithTag("step9_energetic").performClick()
  ui.waitUntil(30000){runCatching{ui.onNodeWithTag("step9_remember").assertIsEnabled();true}.getOrDefault(false)}
  val root=File(context.cacheDir,File(context.cacheDir,"step9-demo-root.txt").readText())
  val repository=LocalArchiveRepository(File(root,"journal/archive"))
  ui.onNodeWithTag("step9_remember").performClick()
  ui.waitUntil(15000){repository.load().size==1}
  val first=repository.load().single()
  assertNotNull(first.runtimeHeart);assertNull(first.place)
  ui.waitUntil(10000){runCatching{ui.onNodeWithTag("step9_remember").assertIsEnabled();true}.getOrDefault(false)}
  ui.onNodeWithTag("step9_remember").performClick()
  ui.waitUntil(10000){runCatching{ui.onNodeWithTag("step9_alternative").assertIsEnabled();true}.getOrDefault(false)}
  assertEquals(1,repository.load().size)
  ui.onNodeWithTag("step9_alternative").performClick()
  ui.waitUntil(30000){runCatching{
   val texts=ui.onNodeWithTag("step9_playback").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
   texts.none{it.text.contains(first.track!!.title)}
  }.getOrDefault(false)}
  ui.waitUntil(30000){runCatching{ui.onNodeWithTag("step9_remember").assertIsEnabled();true}.getOrDefault(false)}
  ui.onNodeWithTag("step9_remember").performClick()
  ui.waitUntil(15000){repository.load().size==2}
  assertEquals(2,repository.load().map{it.track!!.id}.distinct().size)
  fun screenshot(name:String){
   val image=ui.onRoot().captureToImage().asAndroidBitmap()
   File(context.cacheDir,name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  }
  ui.onNodeWithTag("step9_context").performClick()
  screenshot("step9-context.png")
  ui.onNodeWithTag("step9_hearts").performClick()
  screenshot("step9-hearts.png")
  ui.onNodeWithTag("step9_finish").performClick()
 }
}
