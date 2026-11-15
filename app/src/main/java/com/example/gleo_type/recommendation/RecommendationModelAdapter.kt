package com.example.gleo_type.recommendation

import com.example.gleo_type.ui.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Append-only local preference snapshots. Call on an IO worker, never a Player callback. */
class UserPreferenceRepository(private val file: File, private val model: MusicEmbeddingRepository,
    private val expectedUser: String? = null) {
    companion object { private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>() }
    private val pathLock=locks.computeIfAbsent(file.canonicalPath){Any()}
    private var seenStamp:Pair<Long,Long>?=null
    private val explicitLatest=linkedMapOf<String,String>()
    private var preference: FloatArray? = null
    private val processed = linkedSetOf<String>()
    private val listened = linkedSetOf<String>()
    private val likes = linkedSetOf<String>()
    private var loaded = false
    private var learned = 0
    @Synchronized private fun load() {
        val stamp=if(file.isFile)file.length() to file.lastModified() else -1L to -1L
        if (loaded && seenStamp==stamp) return
        preference=null;processed.clear();listened.clear();likes.clear();explicitLatest.clear();learned=0
        if (file.isFile) {
            require(file.length() <= 8L * 1024 * 1024)
            val bytes = file.readBytes()
            require(bytes.isEmpty() || bytes.last() == 10.toByte()) { "Incomplete preference journal; original preserved" }
            bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.lastOrNull()?.let { line ->
                val j = JSONObject(line); require(j.getString("modelVersion") == model.modelVersion)
                if(j.has("ownerLocalUserId") && !j.isNull("ownerLocalUserId"))require(j.getString("ownerLocalUserId")==expectedUser)
                j.optJSONObject("ledgerExplicitLatest")?.let { x -> x.keys().forEach {explicitLatest[it]=x.getString(it)} }
                learned=j.optInt("learnedCount",0)
                val v = j.getJSONArray("vector"); require(v.length() == 512)
                preference = PreferenceMath.normalize(FloatArray(512) { v.getDouble(it).toFloat() })
                fun read(name: String, target: MutableSet<String>) { val a=j.getJSONArray(name); repeat(a.length()) { target += a.getString(it) } }
                read("processed", processed); read("listened", listened); read("pendingLikes", likes)
            }
        }
        loaded = true; seenStamp=stamp
    }
    @Synchronized fun vector(): FloatArray? = synchronized(pathLock) { load(); preference?.copyOf() }
    @Synchronized fun previouslyListened(): Set<String> = synchronized(pathLock) { load(); listened.toSet() }
    @Synchronized fun learnedCount(): Int = synchronized(pathLock) { load(); learned }
    @Synchronized fun initialize(genres: Set<String>, moods: Set<String>) { synchronized(pathLock) {
        load(); require(preference == null) { "Preference already initialized; explicit reset flow required" }
        val next = model.onboarding(genres, moods)
        persist(next, processed, listened, likes, "onboarding:genre0.6+mood0.4")
        preference = next
    } }
    @Synchronized fun like(playbackKey: String): Boolean { synchronized(pathLock) {
        load(); if (preference == null || playbackKey in processed) return false
        if (playbackKey in likes) return true
        val next = likes + playbackKey
        persist(preference!!, processed, listened, next, "explicit_like_pending_verified_summary")
        likes.add(playbackKey); return true
    } }
    @Synchronized fun apply(input: PlaybackFeedback): Boolean { synchronized(pathLock) {
        load(); val prior = preference ?: return false
        if (input.key in processed || !model.contains(input.musicId)) return false
        require(processed.size < 10000) { "Preference ledger full; export required" }
        val reward = FixedFeedbackPolicy.reward(input.copy(explicitLike = input.key in likes))
        val next = reward?.let { PreferenceMath.update(prior, model.vector(input.musicId)!!, it) } ?: prior
        val nextListened = if (input.trustworthy && input.creditedWallMs > 0) listened + input.musicId else listened
        persist(next, processed + input.key, nextListened, likes - input.key, "feedback:${reward ?: "not_eligible"}", learned+if(reward!=null)1 else 0)
        preference = next; processed += input.key; listened.clear(); listened += nextListened; likes -= input.key
        if(reward!=null)learned++
        return reward != null
    } }
    @Synchronized fun applyLedgerExplicit(ledger:com.example.gleo_type.context.PipelineLedger,eventId:String):ProfileFeedbackOutcome = synchronized(pathLock) {
        val user=expectedUser ?: error("Explicit profile update requires a scoped user")
        val evidence=LedgerProfileFeedback.validate(ledger,user,eventId) ?: return@synchronized ProfileFeedbackOutcome.NEUTRAL_NOT_APPLIED
        load();val prior=preference ?: return@synchronized ProfileFeedbackOutcome.NO_PROFILE
        require(model.contains(evidence.musicId))
        val eventKey="pipeline-v1:$eventId"
        if(eventKey in processed)return@synchronized ProfileFeedbackOutcome.ALREADY_APPLIED
        val previousId=explicitLatest[evidence.occurrenceKey]
        if(previousId==null && evidence.occurrenceKey in processed)return@synchronized ProfileFeedbackOutcome.LEGACY_SUMMARY_ALREADY_PROCESSED
        val previous=previousId?.let {LedgerProfileFeedback.validate(ledger,user,it) ?: error("Invalid prior explicit feedback")}
        if(previous!=null && previous.ledgerIndex>=evidence.ledgerIndex)return@synchronized ProfileFeedbackOutcome.OLDER_EVENT_NOT_APPLIED
        require(previous==null || (previous.musicId==evidence.musicId && previous.occurrenceKey==evidence.occurrenceKey))
        require(processed.size<10000)
        val changed=previous?.kind!=evidence.kind
        val next=if(changed)PreferenceMath.update(prior,model.vector(evidence.musicId)!!,ExplicitProfilePolicy.reward(evidence.kind)!!) else prior
        val latest=explicitLatest.toMutableMap().apply{put(evidence.occurrenceKey,eventId)}
        val keys=processed+eventKey+evidence.occurrenceKey
        persist(next,keys,listened,likes-evidence.occurrenceKey,"${ExplicitProfilePolicy.version}:${evidence.kind}",learned+if(changed)1 else 0,latest)
        preference=next;processed.clear();processed+=keys;likes-=evidence.occurrenceKey
        explicitLatest.clear();explicitLatest.putAll(latest);if(changed)learned++
        if(changed)ProfileFeedbackOutcome.APPLIED_FIXED_RULE else ProfileFeedbackOutcome.RECORDED_SAME_POLARITY
    }
    private fun persist(vector: FloatArray, keys: Set<String>, heard: Set<String>, pending: Set<String>, reason: String, learnedCount:Int=learned, latestExplicit:Map<String,String> = explicitLatest) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = (JSONObject().put("modelVersion", model.modelVersion).put("vector", JSONArray(vector.map { it.toDouble() }))
            .put("ownerLocalUserId",expectedUser).put("ledgerExplicitLatest",JSONObject(latestExplicit))
            .put("processed", JSONArray(keys.toList())).put("listened", JSONArray(heard.toList()))
            .put("pendingLikes", JSONArray(pending.toList())).put("learnedCount",learnedCount).put("reason", reason).put("storedAt", Instant.now().toString()).toString()+"\n").toByteArray(Charsets.UTF_8)
        check(file.length()+bytes.size <= 8L*1024*1024)
        RandomAccessFile(file,"rw").use { out ->
            val before=out.length()
            try { out.seek(before); out.write(bytes); out.fd.sync() }
            catch(e:Exception) { runCatching { out.setLength(before) }; throw e }
        }
        seenStamp=file.length() to file.lastModified()
    }
}

data class ModelAvailability(val loaded: Boolean, val hasPreference: Boolean, val playableCount: Int,
    val message: String, val genres: List<String> = emptyList(), val moods: List<String> = emptyList(),
    val playableIds: Set<String> = emptySet())

/** IO-only adapter. Full 500-row scoring is never confused with executable Media3 recommendations. */
class RecommendationModelAdapter(private val root: File, private val localUserId: String,
    private val verifyMapping: (JSONObject) -> Boolean = { false },
    private val drivingContext: (String?) -> DrivingRecommendationInput? = { null },
    private val drivingPolicy: DrivingScorePolicy = DrivingScorePolicy(),
    private val contextSnapshotProvider: ((String?) -> com.example.gleo_type.context.ContextSnapshot)? = null,
    private val contextElapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val contextRunId: () -> String? = { null },
    private val operationalRuntime:OperationalRecommendationRuntime? = null) {
    private var model: MusicEmbeddingRepository? = null
    private var preferences: UserPreferenceRepository? = null
    private var problem = "모델 미설치"
    @Volatile var uiLearningProblem:String?=null
        private set
    private val playbackToModel=linkedMapOf<String,String>()
    private var carMetadata:List<JSONObject> = emptyList()
    @Synchronized fun load(): Boolean {
        if (model != null) return true
        return try {
            val dir=File(root,"models/clap500")
            val manifestFile=File(dir,"manifest.json"); require(manifestFile.length() in 1..1_000_000)
            val j=JSONObject(manifestFile.readText(Charsets.UTF_8)); require(j.getInt("formatVersion")==1)
            require(j.getInt("rows")==500 && j.getInt("dimensions")==512)
            val metadata=j.getJSONArray("tracks")
            val tracks=(0 until metadata.length()).map { i -> metadata.getJSONObject(i).let {
                require(it.getInt("embeddingIndex")==i)
                require(it.getString("musicId")=="jamendo_api_"+it.getString("jamendoTrackId"))
                EmbeddingTrack(it.getString("musicId"), tags(it.getString("genres")), tags(it.getString("moods")))
            } }
            val version=j.getString("modelVersion"); require(version.matches(Regex("[a-zA-Z0-9_.-]{1,96}")))
            val binary=File(dir,"embeddings.bin"); require(binary.length() in 16..2_097_152)
            val loaded=MusicEmbeddingRepository.load(binary.readBytes(),tracks,version,j.getString("sha256"))
            val mappings=j.optJSONArray("playbackMappings")
            val verified=linkedMapOf<String,String>()
            if(mappings!=null) repeat(mappings.length()) { i ->
                val mapping=mappings.getJSONObject(i)
                val target=mapping.getString("modelMusicId")
                require(loaded.contains(target))
                val row=metadata.getJSONObject(tracks.indexOfFirst { it.musicId==target })
                require(row.getString("title").equals(mapping.getString("title"),true) && row.getString("artist").equals(mapping.getString("artist"),true))
                if(verifyMapping(mapping)) {
                    val playback=mapping.getString("playbackMusicId")
                    require(playback !in verified && target !in verified.values)
                    verified[playback]=target
                }
            }
            val user=MessageDigest.getInstance("SHA-256").digest(localUserId.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val repo=UserPreferenceRepository(File(root,"recommendation-model/preferences/$user/$version.jsonl"),loaded,localUserId)
            repo.vector() // Corrupt user state disables the model path; do not overwrite it.
            playbackToModel.clear(); playbackToModel.putAll(verified)
            carMetadata=(0 until metadata.length()).map{JSONObject(metadata.getJSONObject(it).toString())}
            preferences=repo; model=loaded; problem=""; true
        } catch (_:Exception) { problem="모델 미설치 또는 검증 실패 · 테스트 목록 유지"; false }
    }
    @Synchronized fun availability(playable: Set<String>): ModelAvailability {
        load(); val m=model ?: return ModelAvailability(false,false,0,problem)
        val eligible=playable.filter { m.contains(it) || playbackToModel[it]?.let(m::contains)==true }.toSet()
        val count=eligible.map { playbackToModel[it] ?: it }.distinct().size
        return ModelAvailability(true, preferences!!.vector()!=null, count,
            "CLAP 500곡 로드 · 검증 재생곡 $count/500${if(count<3) " · 후보 부족" else ""} · Gleo 미연결",
            m.tracks.flatMap { it.genres }.distinct().sorted(), m.tracks.flatMap { it.moods }.distinct().sorted(),eligible)
    }
    @Synchronized fun initialize(genres: Set<String>, moods: Set<String>) { check(load()); preferences!!.initialize(genres,moods) }
    @Synchronized fun modelFingerprint():String? {load();return model?.fingerprint}
    @Synchronized fun modelTrackIds():List<String> {load();return model?.tracks?.map{it.musicId}.orEmpty()}
    /** Same existing recommendation calculation; full scores are diagnostics, never executable IDs. */
    @Synchronized fun carSnapshot(catalog:List<Track>,excluded:Set<String>,drive:String?,request:MusicRequest?=null,
        currentMediaId:String?=null,catalogAt:String?=null):CarMusicSnapshot {
        // Carry the very snapshot scored below to the UI. This is an in-memory DTO extension;
        // the existing journal/archive formats and scoring formula are unchanged.
        val captured=contextSnapshotProvider?.invoke(drive)
        val run=recommend(catalog,MusicScope.THREE,emptySet(),excluded,0,catalogAt,drive,request,currentMediaId,snapshot=captured)
        val m=model
        val reverse=playbackToModel.entries.associate{it.value to it.key}
        val catalogIds=catalog.map{it.id}.toSet()
        val pref=preferences?.vector()
        val full=if(m!=null && pref!=null && run.origin==RecommendationOrigin.AI_MODEL && run.fusionDecision==null)
            NaturalLanguageRecommendationEngine(m,drivingPolicy).rank(pref,500,
                excluded.map{playbackToModel[it] ?: it}.toSet(),preferences!!.previouslyListened(),null,
                run.drivingDecision?.input,request,currentMediaId?.let{playbackToModel[it] ?: it}).second.scores.associateBy{it.musicId}
            else emptyMap()
        val candidates=carMetadata.map { row ->
            val id=row.getString("musicId");val media=id.takeIf { it in catalogIds } ?: reverse[id]?.takeIf{it in catalogIds}
            val score=full[id]
            val fused=run.fusionDecision?.scores?.singleOrNull{it.musicId==id}
            CarMusicCandidate(id,media,row.getString("title"),row.getString("artist"),tags(row.getString("genres")),tags(row.getString("moods")),
                media?.let{run.scores[it]} ?: score?.finalScore,fused?.profileCosine ?: score?.preferenceScore,
                fused?.contributions?.get("driving") ?: score?.contextCorrection,fused?.contributions?.get("voice") ?: score?.requestCorrection,
                media?.let{run.scores[it]} ?: score?.finalScore,media!=null,
                if(media!=null)"VERIFIED_LOCAL_SHA256" else "UNAVAILABLE",
                run.drivingDecision?.input?.quality ?: "unknown",run.drivingDecision?.fallbackReason,
                "${row.optString("source")} · ${row.optString("licenseReviewStatus")} · internal research")
        }
        val selected=run.musicIds.mapNotNull{media->candidates.firstOrNull{it.mediaId==media && it.playable}}
        val excludedRows=candidates.filter{it !in selected}.associate{it.trackId to when{
            !it.playable -> "UNMAPPED"
            it.mediaId in excluded -> "EXPLICITLY_EXCLUDED"
            run.fusionDecision!=null -> "OUTSIDE_RETURNED_FUSION_TOP_N"
            it.trackId !in full -> "POLICY_OR_PROFILE"
            else -> "OUTSIDE_TOP_N"
        }}
        val features=run.usedFeatures+UsedFeature("car_ui_candidate_filter",org.json.JSONObject(excludedRows).toString(),run.createdAt)
        return CarMusicSnapshot(run.copy(usedFeatures=features),candidates,selected,excludedRows,m?.tracks?.size ?: 0,
            captured.takeIf{run.origin==RecommendationOrigin.AI_MODEL})
    }
    @Synchronized fun like(runId: String, drive: String, playback: String, musicId: String): Boolean =
        load() && model!!.contains(playbackToModel[musicId] ?: musicId) && preferences!!.like("$runId/$drive/$playback")

    @Synchronized fun learnUiFeedback(ledger:com.example.gleo_type.context.PipelineLedger,id:String):Boolean {
        if(!load())return false
        val f=ledger.records().single{it.ids.feedbackEventId==id};require(f.ids.localUserId==localUserId)
        // Existing verified-summary preference update remains the sole weak-learning path.
        if(f.payload.optString("kind")=="LIKE")like(f.ids.appRunId,f.ids.driveSessionId!!,f.ids.playbackSessionId!!,f.ids.musicId!!)
        ExperienceCaseRepository.forUser(root,localUserId).retainUi(ledger,id,model!!,System.currentTimeMillis())
        return true
    }

    @Synchronized fun recommend(catalog: List<Track>, scope: MusicScope, saved: Set<String>, excluded: Set<String>,
        variant: Int, catalogAt: String?, drive: String?, request: MusicRequest? = null,
        currentPlaybackMusicId: String? = null,
        snapshot: com.example.gleo_type.context.ContextSnapshot? = null,
        fusion: FusionRuntime? = null, utterance: String? = null): RecommendationRun {
        val status=availability(catalog.map { it.id }.toSet())
        if (!status.loaded || !status.hasPreference || status.playableCount==0)
            return LocalRecommendationBuilder.build(catalog,scope,saved,excluded,variant,catalogAt,drive)
        ingestVerifiedSummaries()
        val m=model!!; val pref=preferences!!
        val modelToPlayback=catalog.filter { scope!=MusicScope.SAVED || it.id in saved }
            .groupBy { playbackToModel[it.id] ?: it.id }
            .mapValues { (modelId, items) -> items.firstOrNull { it.id == modelId }?.id ?: items.first().id }
        val blocked=excluded.map { playbackToModel[it] ?: it }.toSet()
        val captured = snapshot ?: contextSnapshotProvider?.invoke(drive)
        val input = if (captured != null) captured.drivingFor(localUserId, drive, contextElapsedMs(), contextRunId() ?: captured.appRunId)
            else runCatching { drivingContext(drive) }.getOrNull()?.takeIf { it.driveSessionId==drive }
        val activeFusion=fusion ?: captured?.let { operationalRuntime?.runtime() }
        if (activeFusion != null) {
            require(captured != null && captured.localUserId==localUserId && captured.driveSessionId==drive &&
                (contextRunId()==null || contextRunId()==captured.appRunId)) { "Fusion snapshot scope mismatch" }
            val safeUtterance=utterance?.takeIf { text -> captured!!.voice["command"]?.let { f ->
                f.freshness==com.example.gleo_type.context.ContextFreshness.FRESH &&
                (f.value as? Map<*,*>)?.get("text")==text && f.observedElapsedMs?.let{contextElapsedMs()-it in 0..30000}==true }==true }
            val decision=ContextFusionRanker(m,activeFusion.encoder,activeFusion.policy,drivingPolicy).rank(localUserId,pref.vector()!!,
                captured!!,contextElapsedMs(),System.currentTimeMillis(),safeUtterance,activeFusion.cases.cases().filter(activeFusion.caseFilter).let{
                    if(fusion==null)operationalRuntime?.boundedUiCases(it) ?: it else it},modelToPlayback.keys,
                blocked,pref.previouslyListened(),if(scope==MusicScope.THREE)3 else 500)
            val mappedScores=decision.scores.associate { modelToPlayback.getValue(it.musicId) to it.total }
            val at=Instant.now().toString()
            return RecommendationRun(UUID.randomUUID().toString(),at,catalogAt,RecommendationOrigin.AI_MODEL,
                "experimental_context_clap_cbr_v1",m.modelVersion,scope,variant,mappedScores.keys.toList(),m.fingerprint,
                listOf(UsedFeature("component scores; ${decision.textStatus}","${if(fusion!=null)"explicit" else "operational"} ${decision.policy.version}; untrained weights",at)),
                drive,mappedScores,decision.legacySpeed,decision,
                JSONObject().put("version",1).put("snapshot",JSONObject(captured.toMap()))
                    .put("decision",JSONObject(decision.toMap())).put("modelToMedia",JSONObject(modelToPlayback))
                    .put("clusters",JSONObject(operationalRuntime?.clusterSummary(decision.scores.map{it.musicId}).orEmpty()))
                    .toString())
        }
        val (decision,requested)=NaturalLanguageRecommendationEngine(m,drivingPolicy).rank(pref.vector()!!,
            if(scope==MusicScope.THREE)3 else 500,blocked,pref.previouslyListened(),modelToPlayback.keys,input,
            request,currentPlaybackMusicId?.let { playbackToModel[it] ?: it })
        val byModelId=decision.scores.associateBy { it.musicId }
        val mapped=decision.copy(scores=requested.scores.map {
            byModelId.getValue(it.musicId).copy(musicId=modelToPlayback.getValue(it.musicId)) })
        val scores=requested.scores.map { ScoredMusic(modelToPlayback.getValue(it.musicId),it.finalScore) }
        val at=Instant.now().toString()
        val runtimeEvidence=operationalRuntime?.fallbackReason?.let{listOf(UsedFeature("fusion fallback:$it","operational runtime; existing preference path retained",at))}.orEmpty()
        return RecommendationRun(UUID.randomUUID().toString(),at,catalogAt,RecommendationOrigin.AI_MODEL,
            if(request?.status=="recognized") "clap_fixed015_request_tag_v1" else if(mapped.applied) "clap_fixed015_context_tag_v1" else "clap_cosine_fixed015_v1",m.modelVersion,scope,variant,scores.map { it.musicId },m.fingerprint,
            listOf(UsedFeature("normalized local preference · eligible feedback:${pref.learnedCount()}", "localUserId explicit onboarding · genre0.6/mood0.4",at),
                UsedFeature(if(mapped.applied) "CLAP cosine + untrained catalog tag bonus" else "CLAP 512 cosine · no vehicle weighting",m.modelVersion,catalogAt),
                UsedFeature("context:${mapped.input?.quality ?: "unknown"} · applied:${mapped.applied} · fallback:${mapped.fallbackReason}",
                    "${mapped.input?.source ?: "unavailable"} · ${mapped.policyVersion} · weight:${mapped.weight}",mapped.input?.latestReceivedAt),
                UsedFeature("verified playback mappings: ${playbackToModel}","original Jamendo Track ID + local asset SHA256",catalogAt)) + runtimeEvidence +
                if(request==null) emptyList() else listOf(UsedFeature(
                    "request:${request.intent} · status:${requested.status} · corrections:${requested.scores.associate { modelToPlayback.getValue(it.musicId) to it.requestCorrection }}",
                    "${request.source} · ${request.parserVersion} · untrained tag bonus weight:0.08",at)),drive,
            scores.associate { it.musicId to it.score }, mapped)
    }
    @Synchronized fun retainExplicitExperience(repository: ExperienceCaseRepository,
        ledger: com.example.gleo_type.context.PipelineLedger, feedbackId: String,
        snapshot: com.example.gleo_type.context.ContextSnapshot, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        check(load()); return repository.retainExplicit(ledger,feedbackId,snapshot,model!!,nowEpochMs)
    }
    @Synchronized fun applyExplicitProfileFeedback(ledger:com.example.gleo_type.context.PipelineLedger,feedbackId:String):ProfileFeedbackOutcome {
        check(load());return preferences!!.applyLedgerExplicit(ledger,feedbackId)
    }
    /** Explicit maintenance boundary; never automatically replays operational history on app launch. */
    @Synchronized fun replayExplicitProfileFeedback(ledger:com.example.gleo_type.context.PipelineLedger,
        appRunId:String?=null):Map<String,ProfileFeedbackOutcome> = ledger.records().filter {
            it.kind==com.example.gleo_type.context.LedgerKind.LISTENING_FEEDBACK && it.ids.localUserId==localUserId &&
                (appRunId==null || it.ids.appRunId==appRunId) && it.payload.optString("kind") in setOf("LIKE","DISLIKE")
        }.associate {it.ids.feedbackEventId!! to applyExplicitProfileFeedback(ledger,it.ids.feedbackEventId!!)}
    /** Pull on recommendation request, never on SDK/main-thread callbacks. Only complete journal lines. */
    private fun ingestVerifiedSummaries() {
        data class Evidence(var skipped:Boolean=false,var seek:Boolean=false,var error:Boolean=false)
        val evidence=HashMap<String,Evidence>(); val summaries=linkedMapOf<String,JSONObject>()
        val files=File(root,"events").listFiles().orEmpty().filter { it.extension=="jsonl" }
        require(files.sumOf { it.length() } <= 256L*1024*1024)
        for(file in files) {
            require(file.length()<=8L*1024*1024)
            val bytes=file.readBytes()
            val lastNewline=bytes.indexOfLast { it==10.toByte() }
            if(lastNewline<0)continue
            bytes.copyOfRange(0,lastNewline+1).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val row=runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if(row.optString("localUserId")!=localUserId) return@forEach
            val run=row.optString("runId"); val drive=row.optString("driveSessionId"); val pb=row.optString("playbackSessionId")
            if(run.isBlank() || drive.isBlank() || pb.isBlank() || drive=="null" || pb=="null") return@forEach
            val key="$run/$drive/$pb"
            val e=evidence.getOrPut(key) { Evidence() }
            val event=row.optJSONObject("event")
            if(event!=null) when(event.optString("label")) {
                "SKIPPED" -> e.skipped=row.optString("actionOrigin") in setOf("next_button","previous_button")
                "SEEKED" -> e.seek=true
                "ERROR" -> e.error=true
            }
            if(row.optString("kind")=="listening_summary") {
                val prior=summaries[key]
                if(prior==null) summaries[key]=row else if(prior.toString()!=row.toString()) e.error=true
            }
        } }
        for((key,row) in summaries.entries.sortedWith(compareBy<Map.Entry<String,JSONObject>> { it.value.optString("occurredAt") }.thenBy { it.key })) {
            val e=evidence.getValue(key)
            val flags=row.optJSONArray("qualityFlags")
            // A known stationary player interval contributes zero credit in ListeningTracker.
            // It is not an observation gap. Explicit likes/positive lower-bound credit may use it;
            // completion and negative skip inference still require the strict confidence path.
            val validFlags=flags!=null && (0 until flags.length()).all {
                flags.getString(it) in setOf("progress_capped_by_monotonic_time","position_stalled") }
            val strictFlags=flags!=null && (0 until flags.length()).all { flags.getString(it)=="progress_capped_by_monotonic_time" }
            val confidence=row.optString("confidence")
            val reason=row.optString("closeReason")
            val musicId=row.optString("musicId")
            val input=PlaybackFeedback(key,playbackToModel[musicId] ?: musicId,row.optString("uniqueListeningRate").toDoubleOrNull(),
                row.optString("totalListeningWallMs").toDoubleOrNull() ?: 0.0,
                !e.error && validFlags && confidence in setOf("bounded_callback_estimate","incomplete_lower_bound") && row.optString("finalSnapshotAvailable")=="true",
                naturalCompletion=reason in setOf("natural_end","track_transition:auto","track_transition:repeat"),
                confirmedManualSkip=e.skipped,seekObserved=e.seek,
                completionTrustworthy=strictFlags && confidence=="bounded_callback_estimate")
            preferences!!.apply(input)
            runCatching {com.example.gleo_type.context.UiPersonalization.summary(root,localUserId,input,FixedFeedbackPolicy.reward(input),this)}
                .onFailure {uiLearningProblem="CBR_SUMMARY_NOT_APPLIED_OR_STORE_UNAVAILABLE"}
        }
    }
    private fun tags(value:String) = value.split('|').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
}
