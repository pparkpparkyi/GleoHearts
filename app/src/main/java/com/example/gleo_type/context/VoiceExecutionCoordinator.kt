package com.example.gleo_type.context

import com.example.gleo_type.recommendation.*
import com.example.gleo_type.ui.app.*
import java.time.Instant
import java.util.UUID

data class VoicePlaybackObservation(val musicId:String?,val playbackSessionId:String?,val playing:Boolean,
    val positionMs:Long,val error:Boolean=false)
interface VoicePlaybackPort {
    fun catalog():List<Track>
    fun current():VoicePlaybackObservation
    fun start(ticket:PlaybackTicket)
    fun control(intent:CommandIntent)
}

/** IO worker only. The official transport remains gated; an internal delivery token is NOT a Gleo request ID. */
class VoiceExecutionCoordinator(private val user:String,private val run:String,private val drive:()->String?,
    private val pipeline:ContextPipeline,private val model:RecommendationModelAdapter,private val fusion:FusionRuntime,
    private val playback:VoicePlaybackPort,private val clock:()->Long,private val wait:(Long)->Unit=Thread::sleep,
    private val applyProfileFeedback:Boolean=true,
    private val onPhase:(VoiceRuntimePhase)->Unit={},
    private val onRecommendation:(RecommendationPlan)->Unit={},
    private val callback:(GleoVoiceContract.Callback)->Unit) {
    private data class Done(val action:GleoVoiceContract.Action,val callback:GleoVoiceContract.Callback)
    private val delivered=linkedMapOf<String,Done>()
    private var busy=false
    private val snapshots=linkedMapOf<String,ContextSnapshot>()
    fun execute(action:GleoVoiceContract.Action,source:CommandSource,localDeliveryToken:String,
        receivedElapsedMs:Long):GleoVoiceContract.Callback {
        fun fail(message:String)=GleoVoiceContract.Callback(action.function,"fail",message)
        if(source==CommandSource.OFFICIAL_GLEO_ACTION) {onPhase(VoiceRuntimePhase.OFFICIAL_DISCONNECTED);return fail("Official runtime registration and caller validation are not verified.")}
        val rejection=synchronized(this) {
            delivered[localDeliveryToken]?.let { require(it.action==action) { "Delivery token conflict" };return it.callback }
            require(localDeliveryToken.isNotBlank() && localDeliveryToken.length<=128)
            require(delivered.size<1000) { "Delivery cache full" }
            if(busy) fail("Another voice action is still running.").also{delivered[localDeliveryToken]=Done(action,it)}
            else {busy=true;null}
        }
        if(rejection!=null){callback(rejection);return rejection}
        val deadline=receivedElapsedMs+6500
        onPhase(VoiceRuntimePhase.RECEIVED)
        var result:GleoVoiceContract.Callback
        try {
            require(clock()-receivedElapsedMs in 0..6500) { "Expired command" }
            val activeDrive=drive()
            fun checkTime() {check(clock()<deadline && drive()==activeDrive) {"Expired or changed drive"}}
            fun voice(text:String?)=VoiceCommand(UUID.randomUUID().toString(),user,run,activeDrive,action.intent,source,
                text,Instant.now().toString(),receivedElapsedMs,text?.let(MusicRequestParser::parse),
                text?.let{KoreanMusicIntentParser.parse(it).claims}.orEmpty())
            fun await(predicate:()->Boolean) {
                while(true) {checkTime();check(!playback.current().error) {"Playback error"};if(predicate())break;wait(25)}
            }
            result=if(action.intent in setOf(CommandIntent.RECOMMEND_AND_PLAY,CommandIntent.CHANGE_MOOD)) {
                val parsed=KoreanMusicIntentParser.parse(action.text.orEmpty())
                val alternative=MusicRequestParser.parse(action.text.orEmpty()).intent==MusicRequestIntent.ALTERNATIVE
                if(parsed.moodKey==null&&!alternative) GleoVoiceContract.Callback(action.function,"asking","Please specify a supported music mood.")
                else {
                    onPhase(VoiceRuntimePhase.RECOMMENDING)
                    val exclude=if(alternative)playback.current().musicId ?: error("No current music for alternative request") else null
                    val plan=pipeline.recommend(voice(action.text),playback.catalog(),model,fusion,exclude)
                    onRecommendation(plan)
                    snapshots[plan.recommendationId]=plan.snapshot
                    checkTime()
                    val id=plan.run.musicIds.firstOrNull() ?: error("No playable candidate")
                    val ticket=pipeline.preparePlayback(plan.recommendationId,id)
                    checkTime();playback.start(ticket)
                    var binding:PipelineRecord?=null
                    await {
                        val current=playback.current()
                        binding=pipeline.ledger.records().singleOrNull{it.kind==LedgerKind.PLAYBACK_BOUND &&
                            it.ids.playbackRequestId==ticket.playbackRequestId && it.ids.musicId==id && it.ids.playbackSessionId==current.playbackSessionId}
                        current.musicId==id && current.playing && current.positionMs>=250 && binding!=null
                    }
                    onPhase(VoiceRuntimePhase.PLAYBACK_CONFIRMED)
                    GleoVoiceContract.Callback(action.function,"success","The recommended music is playing.",listOf(mapOf(
                        "musicId" to id,"recommendationId" to plan.recommendationId,"playbackSessionId" to binding!!.ids.playbackSessionId)))
                }
            } else {
                val before=playback.current();val id=before.musicId ?: error("No current music")
                val pb=before.playbackSessionId ?: error("No current playback occurrence")
                val bound=pipeline.ledger.records().singleOrNull{it.kind==LedgerKind.PLAYBACK_BOUND && it.ids.playbackSessionId==pb &&
                    it.ids.musicId==id && it.ids.appRunId==run && it.ids.driveSessionId==activeDrive} ?: error("Playback is not causally bound")
                val command=voice(null)
                if(action.intent in setOf(CommandIntent.LIKE,CommandIntent.DISLIKE)) {
                    val snapshot=snapshots[bound.ids.recommendationId] ?: error("Recommendation snapshot unavailable in this executor")
                    pipeline.commandExecuted(command,pb,id,"SUCCESS")
                    val fid=pipeline.feedback(pb,id,FeedbackKind.valueOf(action.intent.name),command.voiceRequestId)
                    checkTime();check(model.retainExplicitExperience(fusion.cases,pipeline.ledger,fid,snapshot))
                    val profile=if(applyProfileFeedback)model.applyExplicitProfileFeedback(pipeline.ledger,fid) else null
                    if(applyProfileFeedback)check(profile in setOf(ProfileFeedbackOutcome.APPLIED_FIXED_RULE,
                        ProfileFeedbackOutcome.ALREADY_APPLIED,ProfileFeedbackOutcome.RECORDED_SAME_POLARITY))
                    GleoVoiceContract.Callback(action.function,"success","The explicit music feedback was recorded.",listOf(mapOf(
                        "musicId" to id,"playbackSessionId" to pb,"feedbackEventId" to fid,"profileUpdate" to profile?.name)))
                } else {
                    checkTime();playback.control(action.intent)
                    await {
                        val c=playback.current()
                        when(action.intent) {
                            CommandIntent.PAUSE -> c.musicId==id && c.playbackSessionId==pb && !c.playing
                            CommandIntent.PLAY -> c.musicId==id && c.playbackSessionId==pb && c.playing
                            CommandIntent.NEXT,CommandIntent.PREVIOUS -> c.playbackSessionId!=null && c.playbackSessionId!=pb &&
                                c.musicId!=id && c.playing && pipeline.ledger.records().any{it.kind==LedgerKind.PLAYBACK_BOUND &&
                                    it.ids.playbackSessionId==c.playbackSessionId && it.ids.musicId==c.musicId && it.ids.recommendationId==bound.ids.recommendationId}
                            else -> false
                        }
                    }
                    pipeline.commandExecuted(command,pb,id,"SUCCESS")
                    if(action.intent==CommandIntent.PAUSE || action.intent==CommandIntent.NEXT)
                        pipeline.feedback(pb,id,FeedbackKind.valueOf(action.intent.name),command.voiceRequestId)
                    GleoVoiceContract.Callback(action.function,"success","The requested playback state was observed.",listOf(mapOf("musicId" to playback.current().musicId)))
                }
            }
        } catch(_:Exception) { onPhase(VoiceRuntimePhase.CONTROL_FAILED);result=fail("The action could not be confirmed before its deadline.") }
        try {
            // A sink records a callback attempt. A successful Android broadcast is not proof Gleo consumed it.
            callback(result)
        } finally { synchronized(this) { delivered[localDeliveryToken]=Done(action,result);busy=false } }
        return result
    }
}
