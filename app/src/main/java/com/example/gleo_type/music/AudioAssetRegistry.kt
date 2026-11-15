package com.example.gleo_type.music

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** No network, copying, deletion, or fallback ID inference. Call validation on an IO worker. */
class AudioAssetRegistry(
    private val root: File,
    descriptors: List<Descriptor>,
    installed: List<Installed>
) {
    data class Descriptor(val musicId: String, val driveFileId: String, val fileName: String,
        val sizeBytes: Long, val title: String, val artist: String)
    data class Installed(val musicId: String, val driveFileId: String, val relativePath: String,
        val sizeBytes: Long, val sha256: String)
    enum class Status { NOT_INSTALLED, DUPLICATE_INSTALL, INVALID_MAPPING, MISSING,
        SIZE_MISMATCH, HASH_MISMATCH, ACCESS_FAILED, AVAILABLE, PLAYBACK_ERROR }
    data class Resolution(val musicId: String, val status: Status, val file: File? = null,
        val locallyObservedSha256: String? = null)

    private val expected = descriptors.associateBy { it.musicId }
    private val installedById = installed.groupBy { it.musicId }
    private val state = ConcurrentHashMap<String, Resolution>()
    private val playbackErrors = ConcurrentHashMap.newKeySet<String>()
    private val stamps = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val progressed = ConcurrentHashMap.newKeySet<String>()

    init {
        require(expected.size == descriptors.size) { "Duplicate canonical catalog ID" }
        require(installed.all { it.musicId in expected }) { "Unknown installed ID" }
        require(descriptors.map { it.driveFileId }.distinct().size == descriptors.size) { "Duplicate source ID" }
        require(descriptors.all { it.musicId.matches(Regex("jamendo_api_[0-9]+")) &&
            it.fileName == "${it.musicId}.mp3" && it.sizeBytes > 0 && it.driveFileId.isNotBlank() })
    }

    fun validateAll(): List<Resolution> = expected.keys.map(::resolve)

    /** Startup/import validation hashes bytes. Queue reuse requires the same private file size/mtime. */
    fun resolve(musicId: String, rehash: Boolean = true): Resolution {
        if (!rehash && isAvailable(musicId)) return state.getValue(musicId)
        fun result(status: Status, file: File? = null, sha: String? = null) =
            Resolution(musicId, status, file, sha).also { state[musicId] = it }
        val d = expected[musicId] ?: return result(Status.INVALID_MAPPING)
        if (musicId in playbackErrors) return result(Status.PLAYBACK_ERROR)
        val entries = installedById[musicId].orEmpty()
        if (entries.isEmpty()) return result(Status.NOT_INSTALLED)
        if (entries.size != 1) return result(Status.DUPLICATE_INSTALL)
        val entry = entries.single()
        if (entry.driveFileId != d.driveFileId || entry.relativePath != "audio/${d.fileName}" ||
            entry.sizeBytes != d.sizeBytes || !entry.sha256.matches(Regex("[0-9a-f]{64}")))
            return result(Status.INVALID_MAPPING)
        return try {
            val base = root.canonicalFile
            val file = File(base, entry.relativePath).canonicalFile
            if (!file.path.startsWith(base.path + File.separator) || file.name != d.fileName)
                return result(Status.INVALID_MAPPING)
            if (!file.isFile) return result(Status.MISSING)
            if (!file.canRead()) return result(Status.ACCESS_FAILED)
            if (file.length() != d.sizeBytes) return result(Status.SIZE_MISMATCH)
            val before = file.length() to file.lastModified()
            val hash = sha256(file)
            if (before != (file.length() to file.lastModified())) return result(Status.ACCESS_FAILED)
            if (hash != entry.sha256) return result(Status.HASH_MISMATCH)
            stamps[musicId] = before
            if (musicId in playbackErrors) result(Status.PLAYBACK_ERROR) else result(Status.AVAILABLE, file, hash)
        } catch (_: Exception) { result(Status.ACCESS_FAILED) }
    }

    /** Cheap freshness check for browsing; no full-file IO on the Player/main thread. */
    fun isAvailable(musicId: String): Boolean {
        val r = state[musicId] ?: return false
        val f = r.file ?: return false
        return r.status == Status.AVAILABLE && musicId !in playbackErrors &&
            f.isFile && f.canRead() && stamps[musicId] == (f.length() to f.lastModified())
    }
    fun resolution(musicId: String): Resolution? = state[musicId]
    fun resolutions(): List<Resolution> = expected.keys.mapNotNull { state[it] }
    fun descriptor(musicId: String): Descriptor? = expected[musicId]
    fun contains(musicId: String) = musicId in expected
    fun markPlaybackError(musicId: String) {
        if (contains(musicId)) { playbackErrors += musicId; state[musicId] = Resolution(musicId, Status.PLAYBACK_ERROR) }
    }
    fun observeProgress(musicId: String, positionMs: Long, isPlaying: Boolean) {
        if (isAvailable(musicId) && positionMs > 0 && isPlaying) progressed += musicId
    }
    fun observedProgressIds(): Set<String> = progressed.toSet()

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
