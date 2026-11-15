package com.example.gleo_type.recommendation

import com.example.gleo_type.context.*
import org.json.JSONObject
import java.io.File

/** IO-only production wiring. Missing prototypes/cases never manufacture observations or vectors. */
class OperationalRecommendationRuntime(private val root:File,private val user:String,
    private val prototypes:()->String,private val musicMetadata:()->String) {
    @Volatile var fallbackReason:String?=null
        private set
    private val encoder:LocalClapTextEncoder by lazy {runCatching{CuratedMusicTextEncoder(prototypes())}
        .getOrElse{LocalClapTextEncoder{null}}}
    private val repository by lazy{ExperienceCaseRepository.forUser(root,user)}
    /** Bound repeated UI occurrences without deleting history or multiplying one track's reward. */
    fun boundedUiCases(cases:List<ExperienceCase>):List<ExperienceCase> {
        val ui=cases.filter{it.origin.startsWith("UI_")}.groupBy{it.musicId}.values.flatMap{rows->
            rows.sortedWith(compareByDescending<ExperienceCase>{it.updatedAtEpochMs}.thenBy{it.caseId}).take(3)}
        return cases.filterNot{it.origin.startsWith("UI_")}+ui
    }
    fun runtime():FusionRuntime? = runCatching {
        repository.cases() // Fail closed on corrupt cases; retain the legacy ranking path.
        fallbackReason=null
        FusionRuntime(encoder,repository,caseFilter={it.origin in setOf("EXPLICIT_LEDGER_FEEDBACK","UI_EXPLICIT_LEDGER_FEEDBACK","UI_VERIFIED_LISTENING_FEEDBACK") &&
            !it.contextSnapshotId.isNullOrBlank() && !it.recommendationId.isNullOrBlank()})
    }.getOrElse{fallbackReason="case_store_unavailable_legacy_ranking";null}
    /** Canonical-ID join only. Clusters are diagnostic counts, NEVER score bonuses. */
    fun clusterSummary(ids:Collection<String>):Map<String,Any?> = runCatching {
        val rows=JSONObject(musicMetadata()).getJSONArray("tracks")
        val byId=(0 until rows.length()).map{rows.getJSONObject(it)}.associateBy{it.getString("musicId")}
        require(byId.size==rows.length() && byId.size==500)
        val selected=ids.mapNotNull(byId::get)
        selected.forEach{require(it.getInt("kmeansCluster") in 0..7 && it.getInt("gmmCluster") in 0..8)}
        mapOf("usage" to "DIAGNOSTIC_ONLY_NOT_RANKING","matchedIds" to selected.size,
            "kmeansCounts" to selected.groupingBy{it.getInt("kmeansCluster").toString()}.eachCount(),
            "gmmCounts" to selected.groupingBy{it.getInt("gmmCluster").toString()}.eachCount())
    }.getOrElse{mapOf("usage" to "UNAVAILABLE_NOT_RANKING")}
}
