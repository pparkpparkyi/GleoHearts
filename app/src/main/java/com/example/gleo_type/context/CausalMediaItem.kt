package com.example.gleo_type.context

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object CausalMediaItem {
    const val TICKET_KEY = "gleo.pipeline.v1.playbackRequestId"
    fun request(ticket: PlaybackTicket): MediaItem = MediaItem.Builder().setMediaId(ticket.musicId)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString(TICKET_KEY, ticket.playbackRequestId) }).build()).build()
    fun ticket(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString(TICKET_KEY)?.takeIf { it.isNotBlank() }
    fun attach(resolved: MediaItem, ticketId: String): MediaItem = resolved.buildUpon()
        .setMediaMetadata(resolved.mediaMetadata.buildUpon().setExtras(Bundle().apply { putString(TICKET_KEY, ticketId) }).build()).build()
}
