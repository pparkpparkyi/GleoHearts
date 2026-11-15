package com.example.gleo_type.recommendation

import android.content.Context
import com.example.gleo_type.music.JamendoResearchCatalog
import org.json.JSONObject
import java.security.MessageDigest

/** Research debug assets only; a matching title alone never creates an alias. IO worker only. */
class PlaybackMappingVerifier(private val context: Context) {
    fun verify(mapping: JSONObject): Boolean = runCatching {
        val original=mapping.getString("originalTrackId")
        check(mapping.getString("modelMusicId")=="jamendo_api_$original")
        check(mapping.getString("sourceUrl").startsWith("https://www.jamendo.com/track/$original/"))
        check(mapping.getString("evidence")=="original_track_id+title_artist+local_manifest_sha256")
        val item=JamendoResearchCatalog.load(context).single { it.musicId==mapping.getString("playbackMusicId") }
        check(item.title.equals(mapping.getString("title"),true) && item.artist.equals(mapping.getString("artist"),true))
        check(item.sha256==mapping.getString("localAudioSha256"))
        val digest=MessageDigest.getInstance("SHA-256")
        context.assets.open("jamendo/${item.audioFile}").use { stream ->
            val bytes=ByteArray(8192)
            while(true) { val n=stream.read(bytes); if(n<0)break; digest.update(bytes,0,n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==item.sha256)
        true
    }.getOrDefault(false)
}
