package com.example.gleo_type.recommendation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

data class EmbeddingTrack(val musicId: String, val genres: Set<String> = emptySet(), val moods: Set<String> = emptySet())
data class ScoredMusic(val musicId: String, val score: Double)

object PreferenceMath {
    fun normalize(input: FloatArray): FloatArray {
        require(input.size == 512 && input.all { it.isFinite() })
        val norm = sqrt(input.sumOf { it.toDouble() * it })
        require(norm > 1e-12 && norm.isFinite()) { "Zero or invalid preference" }
        return FloatArray(input.size) { (input[it] / norm).toFloat() }
    }
    fun update(preference: FloatArray, music: FloatArray, reward: Double): FloatArray {
        require(reward in setOf(1.0, .6, .2, -.5))
        require(preference.size == 512 && music.size == 512)
        return normalize(FloatArray(512) { (preference[it].toDouble() + .15 * reward * music[it]).toFloat() })
    }
}

/** Validated float32 model. Does not know URLs, download audio or translate IDs. */
class MusicEmbeddingRepository private constructor(val tracks: List<EmbeddingTrack>, private val vectors: List<FloatArray>,
    val modelVersion: String, val fingerprint: String) {
    private val byId = tracks.mapIndexed { index, track -> track.musicId to index }.toMap()
    fun vector(musicId: String): FloatArray? = byId[musicId]?.let { vectors[it].copyOf() }
    fun contains(id: String) = id in byId
    internal fun vectorAt(index: Int) = vectors[index]
    fun onboarding(genres: Set<String>, moods: Set<String>): FloatArray {
        fun centroid(select: Set<String>, tags: (EmbeddingTrack) -> Set<String>): FloatArray? {
            if (select.isEmpty()) return null
            val chosen = tracks.indices.filter { tags(tracks[it]).intersect(select).isNotEmpty() }
            require(chosen.isNotEmpty()) { "Selected metadata tags not in model" }
            return PreferenceMath.normalize(FloatArray(512) { axis -> chosen.sumOf { vectors[it][axis].toDouble() }.toFloat() })
        }
        val genre = centroid(genres) { it.genres }; val mood = centroid(moods) { it.moods }
        require(genre != null || mood != null) { "Explicit onboarding required" }
        return PreferenceMath.normalize(FloatArray(512) { (.6 * (genre?.get(it) ?: 0f) + .4 * (mood?.get(it) ?: 0f)).toFloat() })
    }
    companion object {
        fun load(bytes: ByteArray, metadata: List<EmbeddingTrack>, version: String, expectedSha256: String): MusicEmbeddingRepository {
            require(bytes.size <= 2 * 1024 * 1024 && bytes.size >= 16 && version.isNotBlank())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == expectedSha256) { "Embedding checksum mismatch" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8); buffer.get(magic)
            require(magic.toString(Charsets.US_ASCII) == "GLEOCLP1")
            require(buffer.int == 500 && buffer.int == 512)
            require(metadata.size == 500 && metadata.map { it.musicId }.toSet().size == 500)
            val rows = metadata.map { track ->
                val length = buffer.int
                require(length in 1..256 && buffer.remaining() >= length + 512 * 4)
                val id = ByteArray(length); buffer.get(id)
                require(id.toString(Charsets.UTF_8) == track.musicId) { "ID/embedding row mismatch" }
                PreferenceMath.normalize(FloatArray(512) { buffer.float })
            }
            require(!buffer.hasRemaining()) { "Unexpected trailing data" }
            return MusicEmbeddingRepository(metadata.toList(), rows, version, hash)
        }
    }
}

class ClapRecommendationEngine(val model: MusicEmbeddingRepository) {
    /** Exact full-model ranking, separate from the Media3 playable intersection. Ties: ID ascending. */
    fun rank(preference: FloatArray, topN: Int = 10, excluded: Set<String> = emptySet(),
             previouslyListened: Set<String> = emptySet(), playableIds: Set<String>? = null): List<ScoredMusic> {
        require(topN in 1..500)
        val p = PreferenceMath.normalize(preference)
        return model.tracks.mapIndexedNotNull { index, track ->
            if (track.musicId in excluded || track.musicId in previouslyListened || playableIds?.contains(track.musicId) == false) null
            else ScoredMusic(track.musicId, p.indices.sumOf { p[it].toDouble() * model.vectorAt(index)[it] })
        }.sortedWith(compareByDescending<ScoredMusic> { it.score }.thenBy { it.musicId }).take(topN)
    }
}

data class PlaybackFeedback(val key: String, val musicId: String, val uniqueListeningRate: Double?,
    val creditedWallMs: Double, val trustworthy: Boolean, val explicitLike: Boolean = false,
    val naturalCompletion: Boolean = false, val confirmedManualSkip: Boolean = false, val seekObserved: Boolean = false,
    val completionTrustworthy: Boolean = trustworthy)

object FixedFeedbackPolicy {
    fun reward(f: PlaybackFeedback): Double? {
        val rate = f.uniqueListeningRate ?: return null
        if (!f.trustworthy || !rate.isFinite() || rate !in 0.0..1.0 || !f.creditedWallMs.isFinite() || f.creditedWallMs < 0) return null
        return when {
            f.explicitLike && f.creditedWallMs > 0 -> 1.0
            f.naturalCompletion && f.completionTrustworthy && !f.seekObserved && rate >= .95 -> .6
            rate >= .5 -> .2
            f.confirmedManualSkip && f.completionTrustworthy && !f.seekObserved && rate <= .1 && f.creditedWallMs in 1.0..30000.0 -> -.5
            else -> null
        }
    }
}
