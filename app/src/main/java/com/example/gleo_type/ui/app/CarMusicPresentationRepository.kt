package com.example.gleo_type.ui.app

import android.content.Context
import com.example.gleo_type.recommendation.*
import org.json.JSONObject
import java.time.Instant

/** Local, permission-gated artwork only; no networking, user/location URLs or disk image cache. */
class CarMusicPresentationRepository(private val context:Context,private val fingerprint:()->String?,private val modelIds:()->List<String>) {
    private var features:Map<String,CarAcousticFeatures>?=null
    private val images=object:LinkedHashMap<String,ByteArray>(8,.75f,true){}
    @Synchronized fun feature(track:CarMusicCandidate?):CarAcousticFeatures? {
        if(track==null)return null
        if(features==null)features=runCatching {
            val bytes=context.assets.open("car_v10/music_features.json").use{it.readBytes()};require(bytes.size<=256000)
            val j=JSONObject(bytes.toString(Charsets.UTF_8));require(j.getString("modelFingerprint")==fingerprint())
            val a=j.getJSONArray("tracks");require(a.length()==500);val ids=modelIds();require(ids.size==500)
            (0 until a.length()).associate { n -> val r=a.getJSONObject(n);require(r.getInt("embeddingIndex")==n)
                require(r.getString("trackId")==ids[n])
                r.getString("trackId") to CarAcousticFeatures(r.getString("trackId"),
                    if(r.isNull("bpmEstimated"))null else r.getDouble("bpmEstimated").takeIf{it.isFinite()},
                    null,source=j.getString("source")) }
        }.getOrDefault(emptyMap())
        return (features!![track.trackId] ?: CarAcousticFeatures(track.trackId)).copy(moods=track.moods)
    }
    @Synchronized fun artwork(track:CarMusicCandidate?):ByteArray? {
        val media=track?.mediaId ?: return null
        // No permission manifest is bundled until the individual image rights are confirmed.
        return runCatching {
            val raw=context.assets.open("car_v10/artwork_manifest.json").use{it.readBytes()};require(raw.size<=128000)
            val a=JSONObject(raw.toString(Charsets.UTF_8)).getJSONArray("images")
            val matches=(0 until a.length()).map{a.getJSONObject(it)}.filter{it.getString("trackId")==track.trackId && it.getString("mediaId")==media}
            require(matches.size==1);val j=matches.single()
            val ref=CarArtworkReference(track.trackId,media,j.getString("imageId"),j.getString("assetPath"),j.getString("sha256"),
                j.getString("source"),j.getString("permission"),j.getString("licenseReference"))
            val key="${ref.trackId}/${ref.mediaId}/${ref.sha256}"
            images[key]?.let{return@runCatching it}
            require(ref.assetPath.startsWith("car_v10/artwork/") && !ref.assetPath.contains(".."))
            val bytes=context.assets.open(ref.assetPath).use{require(it.available()<=2_097_152);it.readBytes()};require(CarArtworkPolicy.accepts(ref,track.trackId,media,bytes))
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
            bounds.inJustDecodeBounds=false;bounds.inSampleSize=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/bounds.inSampleSize>256)bounds.inSampleSize*=2
            val decoded=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds) ?: error("Undecodable artwork")
            decoded.recycle()
            while(images.values.sumOf{it.size}+bytes.size>4_194_304 && images.isNotEmpty())images.remove(images.keys.first())
            images[key]=bytes;bytes
        }.getOrNull()
    }
}

class CarMusicRecommendationSource(private val adapter:RecommendationModelAdapter,private val history:RecommendationHistory,
    val presentation:CarMusicPresentationRepository,private val excluded:()->Set<String>,
    private val requestProvider:()->MusicRequest?={null},private val appRunId:()->String?={null},private val driveId:()->String?={null}) {
    fun request():MusicRequest?=requestProvider() // Gleo remains unregistered; callers may inject explicit typed requests.
    fun isCurrentDrive(snapshot:CarMusicSnapshot)=snapshot.run.driveId==driveId()
    private var lastKey:String?=null
    private var last:CarMusicSnapshot?=null
    @Synchronized fun refresh(catalog:List<Track>,drive:String?,contextKey:String,request:MusicRequest?,current:String?,catalogAt:String?):CarMusicSnapshot {
        val blocked=excluded();val key=listOf(catalog.map{it.id},drive,contextKey,request,if(request?.intent==MusicRequestIntent.ALTERNATIVE)current else null,blocked.sorted(),catalogAt).toString()
        if(key==lastKey && last!=null)return last!!
        val raw=adapter.carSnapshot(catalog,blocked,drive,request,current,catalogAt)
        val candidates=raw.candidates.map { row -> val f=presentation.feature(row);row.copy(bpmEstimated=f?.bpmEstimated,energy=f?.energy) }
        val byId=candidates.associateBy{it.trackId}
        val next=raw.copy(candidates=candidates,playableRecommendations=raw.playableRecommendations.map{byId.getValue(it.trackId)})
        history.append(next.run)
        last=next;lastKey=key;return next
    }
    /** Read-only metadata from an already validated snapshot; never invent a model/media alias. */
    @Synchronized fun observedCandidate(mediaId:String):CarMusicCandidate? =
        last?.candidates?.singleOrNull { it.mediaId==mediaId && it.mappingStatus=="VERIFIED_LOCAL_SHA256" }
    fun evidence(runId:String,track:CarMusicCandidate,stage:String,playbackId:String?,at:String=Instant.now().toString()) {
        history.recordPlaybackEvidence(runId,track.trackId,track.mediaId ?: return,stage,playbackId,at,appRunId(),driveId())
    }
}
