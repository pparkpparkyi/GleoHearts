package com.example.gleo_type.recommendation

import com.example.gleo_type.context.*
import kotlin.math.abs

/** Local inference boundary. No networking, keyword vectors, or silently substituted checkpoints. */
data class TextVector(val vector: FloatArray, val modelId: String, val revision: String,
    val inputText: String, val language: String, val inferenceMs: Double,
    val encodedText: String = inputText, val processing: String = "RAW_LOCAL_INFERENCE_OR_EXPLICIT_TEST_FIXTURE")
fun interface LocalClapTextEncoder { fun encode(text: String): TextVector? }
data class FusionRuntime(val encoder: LocalClapTextEncoder, val cases: ExperienceCaseRepository,
    val policy: FusionPolicy = FusionPolicy(),val caseFilter:(ExperienceCase)->Boolean={true})

data class FusionPolicy(val version: String = "experimental_fusion_v1", val profile: Double = 1.0,
    val text: Double = .25, val experience: Double = .15, val night: Double = .02, val rain: Double = .02) {
    init { require(listOf(profile,text,experience,night,rain).all { it.isFinite() && it in 0.0..1.0 }) }
}

/** Comparable observed features, never the user's assertions about weather or unobserved route purpose. */
data class ObservedFeatures(val facets: Map<String, Map<String, String>>) {
    fun similarity(other: ObservedFeatures): Double {
        val dimensions = setOf("location","destination","driving","road","timeWeather","voice")
        return dimensions.sumOf { facet ->
            val a = facets[facet].orEmpty(); val b = other.facets[facet].orEmpty(); val keys = a.keys + b.keys
            if (keys.isEmpty()) 0.0 else keys.count { it in a && it in b && a[it] == b[it] }.toDouble() / keys.size
        } / dimensions.size // Missing dimensions contribute zero, never perfect similarity from one known clock field.
    }
}

object ContextFeatureExtractor {
    fun extract(s: ContextSnapshot, now: Long): ObservedFeatures {
        require(s.schemaVersion == 1 && now >= s.capturedElapsedMs)
        fun facet(fields: Map<String,ContextField>, ttl: Long): Map<String,String> {
            val out = linkedMapOf<String,String>()
            fun flatten(key: String, value: Any?) {
                if (out.size >= 64) return
                if (key.substringAfterLast('.') in setOf("timestamp","timestampMillis","time","eventId","connectionToken","coverage","observedDurationMs")) return
                when (value) {
                    is Map<*,*> -> value.entries.sortedBy { it.key.toString() }.forEach { flatten("$key.${it.key}",it.value) }
                    is List<*> -> value.take(16).forEachIndexed { i,v -> flatten("$key.$i",v) }
                    is Number, is Boolean, is String -> {
                        val raw=value.toString()
                        if (raw.isNotBlank() && raw != "null" && !key.endsWith("timestamp") && !key.endsWith("eventId")) {
                            val numeric=raw.toDoubleOrNull()
                            if (numeric == null || numeric.isFinite()) out[key] = when {
                                key.endsWith(".hour") && numeric != null -> (numeric.toInt()/4).toString()
                                (key.endsWith(".latitude") || key.endsWith(".longitude")) && numeric != null ->
                                    kotlin.math.floor(numeric*100).toLong().toString() // disclosed ~1km grid, no place inference
                                else -> raw
                            }
                        }
                    }
                }
            }
            fields.forEach { (key,f) ->
                val age=f.observedElapsedMs?.let { now-it }
                if (f.freshness == ContextFreshness.FRESH && age != null && f.observedElapsedMs >= 0 && age in 0..ttl && f.value != null &&
                    f.origin != "USER_STATEMENT" && f.source != "USER_STATEMENT") flatten(key,f.value)
            }
            return out
        }
        // Do not treat movementState as an independent location observation, or raw speed as a second bonus.
        return ObservedFeatures(mapOf(
            "location" to facet(s.currentLocation.filterKeys { it == "position" },10_000),
            "destination" to facet(s.destination,15_000),
            "driving" to facet(s.driving.filterKeys { it == "speedState" },2_500),
            "road" to facet(s.road,15_000),
            "timeWeather" to (facet(s.timeWeather.filterKeys { it == "deviceTime" },30_000) +
                facet(s.timeWeather.filterKeys { it == "weather" && s.timeWeather[it]?.origin == "API_OBSERVATION" },600_000)),
            "voice" to facet(s.voice.filterKeys { it == "command" },30_000)))
    }
}

data class ExperienceCase(val caseId: String, val localUserId: String, val musicId: String,
    val playbackSessionId: String, val features: ObservedFeatures, val reward: Double,
    val feedbackEventIds: Set<String>, val updatedAtEpochMs: Long, val origin: String,
    val contextSnapshotId: String? = null, val recommendationId: String? = null, val driveSessionId: String? = null)
data class RetrievedExperience(val case: ExperienceCase, val similarity: Double)
object CaseBasedReasoner {
    fun retrieve(user: String, features: ObservedFeatures, cases: List<ExperienceCase>, nowEpochMs: Long,
        maxAgeMs: Long = 90L*24*60*60*1000): List<RetrievedExperience> = cases.asSequence()
        .filter { it.localUserId == user && nowEpochMs-it.updatedAtEpochMs in 0..maxAgeMs &&
            it.reward.isFinite() && abs(it.reward)<=1 && it.feedbackEventIds.isNotEmpty() }
        .map { RetrievedExperience(it,features.similarity(it.features)) }.filter { it.similarity > 0 }
        .sortedWith(compareByDescending<RetrievedExperience> { it.similarity }.thenBy { it.case.caseId }).take(20).toList()
    /** Missing cases yield zero. Similarity also attenuates confidence; no invented cold-start cases. */
    fun score(musicId: String, retrieved: List<RetrievedExperience>): Double {
        val matches=retrieved.filter { it.case.musicId == musicId }
        return if(matches.isEmpty()) 0.0 else matches.sumOf { it.similarity*it.case.reward }/matches.size
    }
}

data class FusionScore(val musicId: String, val profileCosine: Double, val textCosine: Double?,
    val cbr: Double, val contributions: Map<String,Double>, val total: Double,
    val contextCbrContributions: Map<String,Double> = emptyMap())
data class FusionDecision(val scores: List<FusionScore>, val textStatus: String, val textModel: String?,
    val textRevision: String?, val inferenceMs: Double?, val retrievedCaseIds: List<String>,
    val observed: ObservedFeatures, val policy: FusionPolicy, val legacySpeed: DrivingDecision,
    val encodedText: String? = null, val textProcessing: String? = null,
    val contextEvidence: Map<String,Any?> = emptyMap()) {
    fun toMap(): Map<String,Any?> = mapOf("policy" to policy.toString(), "learnedWeights" to false,
        "textStatus" to textStatus,"textModel" to textModel,"textRevision" to textRevision,
        "inferenceMs" to inferenceMs?.takeUnless { textProcessing=="CURATED_ENGLISH_PRECOMPUTED_LOOKUP" },
        "prototypeLookupMs" to inferenceMs?.takeIf { textProcessing=="CURATED_ENGLISH_PRECOMPUTED_LOOKUP" },
        "encodedText" to encodedText,"textProcessing" to textProcessing,
        "contextUsage" to mapOf("location" to "CBR_ONLY_IF_OBSERVED", "destination" to "CBR_ONLY_IF_OBSERVED",
            "driving" to "EXISTING_SPEED_BONUS_ONCE_AND_CBR", "road" to "CBR_ONLY_IF_OBSERVED",
            "timeWeather" to "EXPERIMENTAL_FRESH_NIGHT_RAIN_TAG_BONUS_AND_CBR", "voice" to "ACTUAL_CLAP_IF_AVAILABLE_AND_CBR"),
        "retrievedCaseIds" to retrievedCaseIds,"observedFeatures" to observed.facets,
        "contextEvidence" to contextEvidence,
        "scores" to scores.map { mapOf("musicId" to it.musicId,"profileCosine" to it.profileCosine,
            "textCosine" to it.textCosine,"cbr" to it.cbr,"contributions" to it.contributions,"total" to it.total,
            "contextCbrContributions" to it.contextCbrContributions) })
}

class ContextFusionRanker(private val model: MusicEmbeddingRepository, private val encoder: LocalClapTextEncoder,
    private val policy: FusionPolicy = FusionPolicy(), private val drivingPolicy: DrivingScorePolicy = DrivingScorePolicy()) {
    fun rank(user: String, preference: FloatArray, snapshot: ContextSnapshot, nowElapsedMs: Long, nowEpochMs: Long,
        text: String?, cases: List<ExperienceCase> = emptyList(), playable: Set<String>? = null,
        excluded: Set<String> = emptySet(), listened: Set<String> = emptySet(), topK: Int = 3): FusionDecision {
        require(snapshot.localUserId == user && topK in 1..500)
        val observed=ContextFeatureExtractor.extract(snapshot,nowElapsedMs)
        val retrieved=CaseBasedReasoner.retrieve(user,observed,cases,nowEpochMs)
        var status=if(text.isNullOrBlank()) "NO_UTTERANCE" else "ENCODER_UNAVAILABLE"
        val encoded=if(text.isNullOrBlank()) null else try { encoder.encode(text)?.also {
            require(it.modelId == "laion/clap-htsat-unfused" && it.revision.matches(Regex("[0-9a-f]{40}")))
            require(it.inputText == text && it.inferenceMs.isFinite() && it.inferenceMs >= 0)
            PreferenceMath.normalize(it.vector)
            status=if(it.processing=="CURATED_ENGLISH_PRECOMPUTED_LOOKUP")"REAL_CLAP_PRECOMPUTED_CURATED_ENGLISH" else "REAL_LOCAL_CLAP_TEXT"
        } } catch (_:Exception) { status="ENCODER_FAILED_OR_INCOMPATIBLE";null }
        val vector=encoded?.let { PreferenceMath.normalize(it.vector) }
        val legacy=DrivingRecommendationEngine(model,drivingPolicy).rank(preference,500,excluded,listened,playable,
            snapshot.drivingFor(user,snapshot.driveSessionId,nowElapsedMs))
        val metadata=model.tracks.associateBy { it.musicId }
        val time=observed.facets["timeWeather"].orEmpty()
        val night=time["deviceTime.hour"]?.toIntOrNull()?.let { it in setOf(0,1,5) } == true
        val rain=time.any { (k,v) -> k.startsWith("weather.") && k.substringAfterLast('.') in setOf("condition","conditionCode") && v.lowercase() in setOf("rain","rainy") }
        val scored=legacy.scores.map { row ->
            val tags=metadata.getValue(row.musicId).let { it.genres+it.moods }
            val calm=tags.any { it in setOf("calm","relaxing","ambient","chillout") }
            val similarity=vector?.let { v -> val audio=model.vector(row.musicId)!!; v.indices.sumOf { i -> v[i].toDouble()*audio[i] } }
            val cbr=CaseBasedReasoner.score(row.musicId,retrieved)
            val parts=linkedMapOf("profile" to policy.profile*row.preferenceScore,"voice" to policy.text*(similarity ?: 0.0),
                "cbr" to policy.experience*cbr,"driving" to row.contextCorrection,
                "time" to if(night && calm)policy.night else 0.0,"weather" to if(rain && calm)policy.rain else 0.0,
                "locationDirect" to 0.0,"destinationDirect" to 0.0,"roadDirect" to 0.0)
            FusionScore(row.musicId,row.preferenceScore,similarity,cbr,parts,parts.values.sum(),
                ContextContributionEvidence.cbrParts(row.musicId,observed,retrieved,policy.experience))
        }.sortedWith(compareByDescending<FusionScore> { it.total }.thenBy { it.musicId }).take(topK)
        return FusionDecision(scored,status,encoded?.modelId,encoded?.revision,encoded?.inferenceMs,
            retrieved.map { it.case.caseId },observed,policy,legacy,encoded?.encodedText,encoded?.processing,
            ContextContributionEvidence.describe(snapshot,nowElapsedMs,observed,retrieved))
    }
}
