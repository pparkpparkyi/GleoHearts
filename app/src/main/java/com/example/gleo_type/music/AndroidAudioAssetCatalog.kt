package com.example.gleo_type.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import java.io.File

/** Offline metadata only. Installation is explicit; absence/corruption never alters existing files. */
object AndroidAudioAssetCatalog {
    const val CATALOG_ASSET = "audio-assets/catalog-v1.json"
    const val INSTALL_MANIFEST = "installed-v1.json"
    fun productionRoot(context: Context) = File(context.noBackupFilesDir, "audio-assets")

    fun load(context: Context, root: File = productionRoot(context)): AudioAssetRegistry {
        val catalog = context.assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(catalog.getInt("formatVersion") == 1)
        val tracks = catalog.getJSONArray("tracks")
        require(tracks.length() == 500)
        val descriptors = (0 until tracks.length()).map { i -> tracks.getJSONObject(i).let {
            require(it.getInt("embeddingIndex") == i)
            AudioAssetRegistry.Descriptor(it.getString("musicId"), it.getString("driveFileId"),
                it.getString("fileName"), it.getLong("sizeBytes"), it.getString("title"), it.getString("artist"))
        } }
        val manifest = File(root, INSTALL_MANIFEST)
        val installed = if (!manifest.exists()) emptyList() else {
            require(manifest.length() in 1..1_000_000)
            val j = JSONObject(manifest.readText(Charsets.UTF_8))
            require(j.getInt("formatVersion") == 1)
            val rows = j.getJSONArray("tracks"); require(rows.length() <= 500)
            (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
                AudioAssetRegistry.Installed(it.getString("musicId"), it.getString("driveFileId"),
                    it.getString("relativePath"), it.getLong("sizeBytes"), it.getString("sha256"))
            } }
        }
        return AudioAssetRegistry(root, descriptors, installed).also { it.validateAll() }
    }

    fun mediaItems(registry: AudioAssetRegistry): List<MediaItem> = registry.resolutions().mapNotNull { r ->
        if (r.status != AudioAssetRegistry.Status.AVAILABLE) null
        else mediaItem(registry.descriptor(r.musicId)!!, r.file!!)
    }

    fun mediaItem(d: AudioAssetRegistry.Descriptor, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(d.musicId).setUri(Uri.fromFile(file))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(d.title).setArtist(d.artist)
            .setIsBrowsable(false).setIsPlayable(true).build()).build()
}
