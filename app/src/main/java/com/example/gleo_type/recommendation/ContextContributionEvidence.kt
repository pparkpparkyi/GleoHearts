package com.example.gleo_type.recommendation

import com.example.gleo_type.context.*

/** Explanations of existing scores, not a new scoring policy. No inference of traffic/purpose/emotion. */
object ContextContributionEvidence {
    val facets = listOf("location", "destination", "driving", "road", "timeWeather", "voice")

    fun similarities(a: ObservedFeatures, b: ObservedFeatures): Map<String, Double> = facets.associateWith { facet ->
        val x = a.facets[facet].orEmpty(); val y = b.facets[facet].orEmpty(); val keys = x.keys + y.keys
        if (keys.isEmpty()) 0.0 else keys.count { it in x && it in y && x[it] == y[it] }.toDouble() / keys.size
    }

    /** Conditional on the actual top-20 retrieval. Sums to the existing weighted CBR contribution.
     * Counterfactual removal may change retrieval, so these are not counterfactual causal effects. */
    fun cbrParts(musicId: String, observed: ObservedFeatures, retrieved: List<RetrievedExperience>, weight: Double): Map<String, Double> {
        val matches = retrieved.filter { it.case.musicId == musicId }
        return facets.associateWith { facet ->
            if (matches.isEmpty()) 0.0 else weight * matches.sumOf {
                similarities(observed, it.case.features).getValue(facet) * it.case.reward / facets.size
            } / matches.size
        }
    }

    fun describe(s: ContextSnapshot, now: Long, observed: ObservedFeatures,
        retrieved: List<RetrievedExperience>): Map<String, Any?> {
        val fields = mapOf(
            "location" to s.currentLocation.filterKeys { it == "position" },
            "destination" to s.destination, "driving" to s.driving.filterKeys { it == "speedState" },
            "road" to s.road, "timeWeather" to s.timeWeather.filterKeys { it in setOf("deviceTime", "weather") },
            "voice" to s.voice.filterKeys { it == "command" })
        return mapOf("version" to 1, "contextSnapshotId" to s.contextSnapshotId,
            "environment" to s.environment, "asOfElapsedMs" to now,
            "attribution" to "CONDITIONAL_RETRIEVED_CBR_DECOMPOSITION_NOT_CAUSAL_ABLATION",
            "facets" to facets.associateWith { facet -> mapOf(
                "featureCount" to observed.facets[facet].orEmpty().size,
                "fields" to fields[facet].orEmpty().mapValues { (name, f) ->
                    val ttl = when(facet) {
                        "location" -> 10_000L; "destination", "road" -> 15_000L; "driving" -> 2_500L
                        "timeWeather" -> if(name == "weather") 600_000L else 30_000L
                        else -> 30_000L
                    }
                    val age = f.observedElapsedMs?.let { now - it }
                    val reason = when {
                        f.origin == "USER_STATEMENT" || f.source == "USER_STATEMENT" -> "USER_CLAIM_NOT_OBSERVATION"
                        facet == "timeWeather" && name == "weather" && f.origin != "API_OBSERVATION" -> "WEATHER_NOT_API_OBSERVATION"
                        f.value == null -> f.missingReason ?: "MISSING_VALUE"
                        f.freshness != ContextFreshness.FRESH -> f.freshness.name
                        age == null -> "MISSING_CLOCK"
                        f.observedElapsedMs!! < 0 -> "INVALID_CLOCK"
                        age < 0 -> "FUTURE_CLOCK"
                        age > ttl -> "EXPIRED"
                        observed.facets[facet].orEmpty().keys.none { it == name || it.startsWith("$name.") } -> "NO_COMPARABLE_FEATURE"
                        else -> "ELIGIBLE"
                    }
                    mapOf("source" to f.source, "origin" to f.origin, "environment" to f.environment,
                        "eventId" to f.eventId, "freshness" to f.freshness.name, "ageMs" to age,
                        "ttlMs" to ttl, "eligibility" to reason)
                }) },
            "retrieval" to retrieved.map { mapOf("caseId" to it.case.caseId, "origin" to it.case.origin,
                "similarity" to it.similarity, "reward" to it.case.reward,
                "facetSimilarities" to similarities(observed, it.case.features)) })
    }
}
