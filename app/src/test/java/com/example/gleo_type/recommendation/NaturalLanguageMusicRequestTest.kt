package com.example.gleo_type.recommendation

import com.example.gleo_type.event.TestJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NaturalLanguageMusicRequestTest {
    private fun fixture():Pair<MusicEmbeddingRepository,FloatArray> {
        val path=System.getenv("GLEO_CLAP_MODEL_DIR");assumeTrue("Real model required",path!=null)
        val root=File(path!!);val j=TestJson.parse(root.resolve("manifest.json").readText()) as Map<*,*>
        val tracks=(j["tracks"] as List<*>).map { v -> val r=v as Map<*,*>
            EmbeddingTrack(r["musicId"] as String,(r["genres"] as String).split('|').toSet(),(r["moods"] as String).split('|').toSet()) }
        val m=MusicEmbeddingRepository.load(root.resolve("embeddings.bin").readBytes(),tracks,j["modelVersion"] as String,j["sha256"] as String)
        val g=TestJson.parse(root.resolve("parity.json").readText()) as Map<*,*>
        return m to (g["preference"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
    }
    @Test fun conservativeParsingAndUnsupportedVoiceAdapter() {
        listOf("잔잔한 음악 추천해 줘" to MusicRequestIntent.CALM,"신나는 음악 틀어 줘" to MusicRequestIntent.ENERGETIC,
            "지금 운전에 어울리는 음악 추천해 줘" to MusicRequestIntent.DRIVING,
            "이 노래 말고 다른 곡 추천해 줘" to MusicRequestIntent.ALTERNATIVE).forEach { (text,intent) ->
            assertEquals(intent,MusicRequestParser.parse(text).intent)
            assertEquals(MusicRequestSource.TEST_TEXT,MusicRequestParser.parse(text).source)
        }
        for(text in listOf("", "잔잔한 음악은 싫어", "잔잔하고 신나는 음악", "신나는 음악 틀어 줘 말고"))
            assertEquals(MusicRequestIntent.UNKNOWN,MusicRequestParser.parse(text).intent)
        assertNull(UnavailableGleoMusicRequestAdapter().receive())
    }
    @Test fun absentAmbiguousAndDrivingUnknownPreserveExactTopTen() {
        val(m,p)=fixture();val expected=DrivingRecommendationEngine(m).rank(p).scores
        for(request in listOf(null,MusicRequestParser.parse("모호한 요청"),MusicRequestParser.parse("지금 운전에 어울리는 음악 추천해 줘"))) {
            val(d,r)=NaturalLanguageRecommendationEngine(m).rank(p,request=request)
            assertFalse(d.applied);assertEquals(expected.map{it.musicId},r.scores.map{it.musicId})
            assertEquals(expected.map{it.finalScore},r.scores.map{it.finalScore})
        }
    }
    @Test fun actualTagsAffectScoresWithoutLearningOrFabricatedTextEmbeddings() {
        val(m,p)=fixture();val before=p.copyOf()
        for(text in listOf("잔잔한 음악 추천해 줘","신나는 음악 틀어 줘")) {
            val(_,r)=NaturalLanguageRecommendationEngine(m).rank(p,500,request=MusicRequestParser.parse(text))
            assertEquals(500,r.scores.size);assertTrue(r.scores.any{it.requestCorrection>0})
            r.scores.forEach { assertEquals(it.preferenceScore+it.contextCorrection+it.requestCorrection,it.finalScore,1e-12)
                assertEquals(it.matchedTags.isNotEmpty(),it.requestCorrection>0) }
        }
        assertArrayEquals(before,p,0f)
    }
    @Test fun alternativeIsEphemeralExclusionNotSkipAndNoUnmappedPlayback() {
        val(m,p)=fixture();val id=m.tracks.first().musicId;val e=NaturalLanguageRecommendationEngine(m)
        val request=MusicRequestParser.parse("이 노래 말고 다른 곡 추천해 줘")
        assertTrue(e.rank(p,playableIds=setOf(id),request=request,currentModelId=id).second.scores.isEmpty())
        assertEquals(1,e.rank(p,playableIds=setOf(id)).second.scores.size)
        assertTrue(e.rank(p,playableIds=setOf("music_001","jamendo_0010"),request=request).second.scores.isEmpty())
    }
}
