package com.example.gleo_type.recommendation

import com.example.gleo_type.event.TestJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ClapRecommendationEngineTest {
    private fun data(): Pair<MusicEmbeddingRepository, Map<*,*>> {
        val path=System.getenv("GLEO_CLAP_MODEL_DIR")
        assumeTrue("Real CLAP fixture directory required",path!=null)
        val root=File(path!!)
        val m=TestJson.parse(root.resolve("manifest.json").readText()) as Map<*,*>
        val tracks=(m["tracks"] as List<*>).map { value -> val row=value as Map<*,*>
            EmbeddingTrack(row["musicId"] as String,(row["genres"] as String).lowercase().split('|').toSet(),
                (row["moods"] as String).lowercase().split('|').toSet()) }
        return MusicEmbeddingRepository.load(root.resolve("embeddings.bin").readBytes(),tracks,m["modelVersion"] as String,m["sha256"] as String) to
            (TestJson.parse(root.resolve("parity.json").readText()) as Map<*,*>)
    }
    private fun vector(value:Any?)=(value as List<*>).map { (it as Number).toFloat() }.toFloatArray()
    @Test fun realFiveHundredScoresRanksOnboardingAndFourUpdatesMatchPython() {
        val (model,golden)=data(); val preference=vector(golden["preference"])
        val scores=ClapRecommendationEngine(model).rank(preference,500)
        val expected=(golden["scores"] as List<*>).map { (it as Number).toDouble() }
        val byId=model.tracks.mapIndexed { i,t -> t.musicId to expected[i] }.toMap()
        val error=scores.maxOf { kotlin.math.abs(it.score-byId.getValue(it.musicId)) }
        assertTrue("max cosine error=$error",error<1e-6)
        assertEquals(golden["top10"],scores.take(10).map { it.musicId })
        val onboarding=golden["onboarding"] as Map<*,*>
        val actual=model.onboarding((onboarding["genres"] as List<*>).map { it as String }.toSet(),
            (onboarding["moods"] as List<*>).map { it as String }.toSet())
        assertArrayEquals(vector(onboarding["vector"]),actual,1e-6f)
        val updates=golden["updates"] as Map<*,*>
        for(reward in listOf(1.0,.6,.2,-.5)) {
            assertArrayEquals(vector(updates[reward.toString()]),PreferenceMath.update(preference,model.vector(model.tracks[2].musicId)!!,reward),1e-6f)
        }
        System.getenv("GLEO_CLAP_PARITY_OUTPUT")?.let { File(it).writeText("{\"scores\":500,\"dimensions\":512,\"maxCosineError\":$error,\"top10Exact\":true,\"tolerance\":0.000001}") }
    }
    @Test fun filtersDoNotPretendFiveHundredTracksArePlayable() {
        val (model,g)=data(); val engine=ClapRecommendationEngine(model); val p=vector(g["preference"])
        assertTrue(engine.rank(p,10,playableIds=setOf("music_001","jamendo_0010")).isEmpty())
        val ids=model.tracks.take(3).map { it.musicId }
        assertEquals(listOf(ids[2]),engine.rank(p,10,excluded=setOf(ids[0]),previouslyListened=setOf(ids[1]),playableIds=ids.toSet()).map { it.musicId })
        assertNull(model.vector("missing"))
        assertTrue(runCatching { model.onboarding(emptySet(),emptySet()) }.isFailure)
        assertTrue(runCatching { model.onboarding(setOf("not_a_real_genre"),emptySet()) }.isFailure)
    }
    @Test fun invalidVectorsAreRejectedAndNormalizationIsUnitLength() {
        assertTrue(runCatching { PreferenceMath.normalize(FloatArray(512)) }.isFailure)
        assertTrue(runCatching { PreferenceMath.normalize(FloatArray(512) { Float.NaN }) }.isFailure)
        val v=PreferenceMath.normalize(FloatArray(512) { if(it==0)3f else if(it==1)4f else 0f })
        assertEquals(.6f,v[0],1e-7f); assertEquals(.8f,v[1],1e-7f)
    }
    @Test fun truncatedCorruptOrMismatchedModelNeverLoads() {
        val (model,_)=data()
        val root=File(System.getenv("GLEO_CLAP_MODEL_DIR")!!); val bytes=root.resolve("embeddings.bin").readBytes()
        fun load(input:ByteArray,tracks:List<EmbeddingTrack> = model.tracks,sha:String=model.fingerprint) =
            MusicEmbeddingRepository.load(input,tracks,model.modelVersion,sha)
        assertTrue(runCatching { load(bytes.copyOf(20)) }.isFailure)
        val bad=bytes.copyOf(); bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte()
        assertTrue(runCatching { load(bad) }.isFailure)
        assertTrue(runCatching { load(bytes,model.tracks.drop(1)) }.isFailure)
        assertTrue(runCatching { load(bytes,model.tracks.reversed()) }.isFailure)
    }
    @Test fun deterministicTiesUseIdOrder() {
        val (model,_)=data()
        val bytes=File(System.getenv("GLEO_CLAP_MODEL_DIR")!!).resolve("embeddings.bin").readBytes()
        val cursor=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); cursor.position(16)
        val firstLength=cursor.int; cursor.position(cursor.position()+firstLength)
        val offset=cursor.position(); val first=bytes.copyOfRange(offset,offset+2048)
        cursor.position(offset+2048); val nextLength=cursor.int; cursor.position(cursor.position()+nextLength)
        first.copyInto(bytes,cursor.position()) // Deliberate in-memory tie fixture, never deployed.
        val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val tied=MusicEmbeddingRepository.load(bytes,model.tracks,model.modelVersion,sha)
        val ids=model.tracks.take(2).map { it.musicId }.toSet()
        assertEquals(ids.sorted(),ClapRecommendationEngine(tied).rank(tied.vector(ids.first())!!,2,playableIds=ids).map { it.musicId })
    }
    @Test fun feedbackRequiresActualEvidenceNotSeekCompletionOrCardExclusion() {
        val base=PlaybackFeedback("run/drive/pb","test",.98,100000.0,true,naturalCompletion=true)
        assertEquals(.6,FixedFeedbackPolicy.reward(base)!!,0.0)
        assertEquals(.2,FixedFeedbackPolicy.reward(base.copy(seekObserved=true))!!,0.0)
        assertEquals(.2,FixedFeedbackPolicy.reward(base.copy(completionTrustworthy=false))!!,0.0)
        assertNull(FixedFeedbackPolicy.reward(base.copy(uniqueListeningRate=.05,creditedWallMs=3000.0,naturalCompletion=false,
            confirmedManualSkip=true,completionTrustworthy=false)))
        assertNull(FixedFeedbackPolicy.reward(base.copy(uniqueListeningRate=.01,creditedWallMs=1500.0,seekObserved=true)))
        assertEquals(-.5,FixedFeedbackPolicy.reward(base.copy(uniqueListeningRate=.05,creditedWallMs=3000.0,naturalCompletion=false,confirmedManualSkip=true))!!,0.0)
        assertNull(FixedFeedbackPolicy.reward(base.copy(uniqueListeningRate=.05,creditedWallMs=3000.0,naturalCompletion=false,confirmedManualSkip=true,seekObserved=true)))
        assertNull(FixedFeedbackPolicy.reward(base.copy(trustworthy=false,explicitLike=true)))
        assertEquals(1.0,FixedFeedbackPolicy.reward(base.copy(explicitLike=true))!!,0.0)
        assertEquals(.2,FixedFeedbackPolicy.reward(base.copy(uniqueListeningRate=.7,naturalCompletion=false))!!,0.0)
    }
}
