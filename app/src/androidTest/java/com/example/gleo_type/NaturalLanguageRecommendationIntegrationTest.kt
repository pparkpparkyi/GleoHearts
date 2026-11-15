package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class NaturalLanguageRecommendationIntegrationTest {
    @Test fun testTextUsesRealModelVerifiedMappingAndEphemeralExclusion() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"natural-language-${UUID.randomUUID()}")
        val model=File(root,"models/clap500");check(model.mkdirs())
        for(name in listOf("manifest.json","embeddings.bin"))
            File(context.filesDir,"models/clap500/$name").copyTo(File(model,name))
        val adapter=RecommendationModelAdapter(root,"SYNTHETIC_TEXT_TEST",PlaybackMappingVerifier(context)::verify)
        adapter.initialize(setOf("indie"),setOf("energetic"))
        val catalog=listOf(Track("jamendo_0010","Is It Right?","Lower Loveday"),Track("unverified_candidate","Not playable","Test"))
        fun run(text:String?,current:String?=null)=adapter.recommend(catalog,MusicScope.THREE,emptySet(),emptySet(),0,null,null,
            text?.let(MusicRequestParser::parse),current)
        val base=run(null)
        assertEquals(listOf("jamendo_0010"),base.musicIds)
        for(text in listOf("잔잔한 음악 추천해 줘","신나는 음악 틀어 줘","지금 운전에 어울리는 음악 추천해 줘","모호한 요청")) {
            val result=run(text)
            assertEquals(base.musicIds,result.musicIds)
            assertTrue(result.usedFeatures.any { it.source.contains("TEST_TEXT") })
            assertFalse(result.drivingDecision!!.applied)
        }
        assertTrue(run("이 노래 말고 다른 곡 추천해 줘","jamendo_0010").musicIds.isEmpty())
        assertEquals(base.scores,run(null).scores)
        val f=File(context.filesDir,"diagnostics/natural-language-${System.currentTimeMillis()}.json")
        f.parentFile!!.mkdirs();f.writeText(JSONObject().put("passed",true).put("source","TEST_TEXT")
            .put("gleoVoiceVerified",false).put("verifiedPlayableCount",1)
            .put("environment",android.os.Build.FINGERPRINT).toString(),Charsets.UTF_8)
    }
}
