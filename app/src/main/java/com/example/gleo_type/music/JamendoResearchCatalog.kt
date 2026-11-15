package com.example.gleo_type.music

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.gleo_type.BuildConfig
import org.json.JSONObject
import java.io.FileNotFoundException

/** Optional verified debug assets. Never loads research tracks in release builds. */
object JamendoResearchCatalog {
    val expectedIds = listOf("jamendo_0010", "jamendo_0016", "jamendo_0020")
    data class Track(val musicId: String, val title: String, val artist: String,
                     val audioFile: String, val sha256: String)

    fun load(context: Context): List<Track> {
        if (!BuildConfig.DEBUG) return emptyList()
        val text = try {
            context.assets.open("jamendo/catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) { return emptyList() }
        val rows = JSONObject(text).getJSONArray("tracks")
        val tracks = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Track(row.getString("musicId"), row.getString("title"), row.getString("artist"),
                row.getString("audioFile"), row.getString("sha256"))
        }
        check(tracks.map { it.musicId } == expectedIds) { "Unexpected Jamendo research IDs/order" }
        tracks.forEach { track ->
            check(track.audioFile == "${track.musicId}.mp3" && track.sha256.matches(Regex("[0-9a-f]{64}")))
            context.assets.open("jamendo/${track.audioFile}").use { check(it.read() >= 0) { "Empty research asset" } }
        }
        return tracks
    }

    fun mediaItems(context: Context): List<MediaItem> = load(context).map { track ->
        MediaItem.Builder().setMediaId(track.musicId).setUri("asset:///jamendo/${track.audioFile}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setIsBrowsable(false).setIsPlayable(true).build()).build()
    }
}
