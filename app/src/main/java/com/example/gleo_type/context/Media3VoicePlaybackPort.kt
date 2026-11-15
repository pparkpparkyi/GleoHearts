package com.example.gleo_type.context

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.example.gleo_type.AppContainer
import com.example.gleo_type.ui.app.Track
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses the caller's existing Media3 controller and container. Creates no player, browser or collector. */
class Media3VoicePlaybackPort(private val player:Player,private val container:AppContainer,
    private val availableCatalog:()->List<Track>):VoicePlaybackPort {
    private fun <T> onPlayer(block:()->T):T {
        check(Looper.myLooper()!=player.applicationLooper) {"Voice executor must run on an IO worker"}
        val done=CountDownLatch(1);val cancelled=AtomicBoolean(false);var value:T?=null;var error:Throwable?=null
        check(Handler(player.applicationLooper).post {
            try {if(!cancelled.get())value=block()}catch(t:Throwable){error=t}finally{done.countDown()}
        })
        if(!done.await(1000,TimeUnit.MILLISECONDS)){cancelled.set(true);error("Player thread unavailable")}
        error?.let{throw it}
        @Suppress("UNCHECKED_CAST") return value as T
    }
    override fun catalog()=availableCatalog()
    override fun current()=onPlayer {VoicePlaybackObservation(player.currentMediaItem?.mediaId,
        container.playbackSessions.currentId(),player.isPlaying,player.currentPosition,player.playerError!=null)}
    override fun start(ticket:PlaybackTicket) {onPlayer{player.setMediaItem(CausalMediaItem.request(ticket));player.prepare();player.play()}}
    override fun control(intent:CommandIntent) {
        when(intent) {
            CommandIntent.PLAY -> onPlayer{player.play()}
            CommandIntent.PAUSE -> onPlayer{player.pause()}
            CommandIntent.NEXT,CommandIntent.PREVIOUS -> {
                val current=current()
                val bound=container.contextPipeline.ledger.records().single {it.kind==LedgerKind.PLAYBACK_BOUND &&
                    it.ids.playbackSessionId==current.playbackSessionId && it.ids.musicId==current.musicId}
                val result=container.contextPipeline.ledger.records().single{it.kind==LedgerKind.RECOMMENDATION_RESULT &&
                    it.ids.recommendationId==bound.ids.recommendationId}
                val available=catalog().map{it.id}.toSet();val array=result.payload.getJSONArray("musicIds")
                val candidates=(0 until array.length()).map{array.getString(it)}.filter{it in available}
                require(candidates.size>1 && current.musicId in candidates) {"No adjacent recommended candidate"}
                val delta=if(intent==CommandIntent.NEXT)1 else -1
                val id=candidates[(candidates.indexOf(current.musicId)+delta).mod(candidates.size)]
                // A new occurrence gets a new explicit ticket, including when returning to a prior track.
                start(container.contextPipeline.preparePlayback(bound.ids.recommendationId!!,id))
            }
            else -> error("Unsupported player operation")
        }
    }
}
