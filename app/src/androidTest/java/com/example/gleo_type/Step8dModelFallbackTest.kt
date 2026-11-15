package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Step8dModelFallbackTest {
 @Test fun missingAndMalformedIsolatedModelFallBackWithoutTouchingInstalledModel() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val installed=File(c.filesDir,"models/clap500")
  val original=listOf("manifest.json","embeddings.bin").associateWith{OperationalModelInstaller.sha(File(installed,it).readBytes())}
  val root=File(c.cacheDir,"step8d-fallback-${java.util.UUID.randomUUID()}").apply{mkdirs()}
  val catalog=listOf(Track("music_001","local","metadata"))
  fun verify(){val adapter=RecommendationModelAdapter(root,"ISOLATED_STEP8D_FALLBACK");assertFalse(adapter.load())
   assertEquals(RecommendationOrigin.TEST_CATALOG,adapter.recommend(catalog,MusicScope.THREE,emptySet(),emptySet(),0,null,null).origin)}
  verify()
  val invalid=File(root,"models/clap500").apply{mkdirs()};File(invalid,"manifest.json").writeText("{\"formatVersion\":999}")
  File(invalid,"embeddings.bin").writeBytes(byteArrayOf(1,2,3));verify()
  original.forEach{(name,hash)->assertEquals(hash,OperationalModelInstaller.sha(File(installed,name).readBytes()))}
 }
}
