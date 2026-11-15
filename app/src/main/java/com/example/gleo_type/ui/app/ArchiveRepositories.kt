package com.example.gleo_type.ui.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

interface ArchiveRepository {
    fun load(): List<Pin>
    fun save(pin: Pin)
    fun saveRuntime(request:RuntimeHeartRequest):Pin { error("Runtime save unsupported") }
    fun saveReview(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String):List<Pin> { error("Review save unsupported") }
    fun saveReviewWithMedia(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String,media:ArchiveMediaDraft):List<Pin> { error("Photo review save unsupported") }
    fun delete(id: String)
    fun deleted():List<Pin> = emptyList()
    fun restore(id:String) { error("Restore unsupported") }
}

/** Separate from the append-only event journal. Serialized IO and AtomicFile rollback. */
class LocalArchiveRepository(private val directory: File) : ArchiveRepository {
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
    private val directoryLock = locks.getOrPut(directory.canonicalPath) { Any() }
    private val file get() = AtomicFile(File(directory, "pins.json"))
    override fun load(): List<Pin> = synchronized(directoryLock) { readPins().filter { it.deletedAt==null } }
    override fun deleted():List<Pin> = synchronized(directoryLock) { readPins().filter { it.deletedAt!=null } }
    override fun restore(id:String) = synchronized(directoryLock) { persist(readPins().map { if(it.id==id)it.copy(deletedAt=null) else it }) }
    private fun readPins(): List<Pin> {
        if (!File(directory, "pins.json").exists() && !File(directory, "pins.json.bak").exists()) return emptyList()
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        check(json.getInt("version") in 1..3) { "지원하지 않는 아카이브 버전" }
        val pins = json.getJSONArray("pins")
        return (0 until pins.length()).map { i ->
            val p = pins.getJSONObject(i)
            val t = p.optJSONObject("track")
            val place = p.optJSONObject("place")
            Pin(p.getString("id"), p.getString("savedAt"), p.text("driveId"),
                t?.let { Track(it.getString("id"), it.getString("title"), it.getString("artist")) },
                place?.let { validPlace(it.number("latitude"), it.number("longitude")) },
                p.getJSONArray("tags").let { a -> (0 until a.length()).map { a.getString(it) } },
                p.optString("note"), p.text("weather"), p.optJSONArray("attachments")?.let { a ->
                    (0 until a.length()).map { index -> a.getJSONObject(index).let { attachment ->
                        ArchiveAttachment(attachment.getString("id"), attachment.getString("kind"),
                            attachment.getString("localReference"), attachment.getString("label"), attachment.text("mimeType"))
                    } }
                }.orEmpty(),p.text("sourceDriveKey"),p.text("occurredAt"),p.text("deletedAt"),p.optJSONObject("heartsReview")?.let { h ->
                    fun strings(k:String)=h.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
                    HeartsReviewMetadata(h.getString("localUserId"),h.getString("endedAt"),h.getString("zoneId"),
                        strings("includedMusicIds"),strings("excludedMusicIds"),strings("playbackSessionIds"),
                        strings("qualityFlags").toSet(),strings("dataSources").toSet()) },p.optJSONObject("runtimeHeart")?.let { h ->
                    require(h.getInt("version")==1)
                    val captures=h.getJSONArray("captures")
                    RuntimeHeartMetadata(h.getString("localUserId"),h.getString("appRunId"),h.getString("sourceDeviceId"),
                     (0 until captures.length()).map{index->captures.getJSONObject(index).let{x->RuntimeHeartCapture(x.getString("pinEventId"),x.getString("playbackSessionId"),
                      x.getString("occurredAt"),x.getLong("receivedElapsedMs"),x.getLong("positionMs"),if(x.isNull("durationMs"))null else x.getLong("durationMs"),
                      x.text("contextSnapshotId"),x.text("contextSnapshotJson"),x.text("recommendationId"),x.text("recommendationSnapshotJson"),x.getString("environment"),x.text("locationSource"))}},h.getString("syncState"))
                },p.text("coverAttachmentId"))
        }
    }
    override fun save(pin: Pin) = synchronized(directoryLock) {
        val existing=readPins()
        require(pin.sourceDriveKey==null || existing.none { it.id!=pin.id && it.sourceDriveKey==pin.sourceDriveKey && it.track?.id==pin.track?.id }) { "Duplicate drive/track archive" }
        persist(existing.filterNot { it.id == pin.id } + pin)
    }
    override fun saveRuntime(request:RuntimeHeartRequest):Pin=synchronized(directoryLock) {
        val old=readPins();val key="${request.appRunId}/${request.driveSessionId}"
        val existing=old.singleOrNull{it.sourceDriveKey==key&&it.track?.id==request.track.id}
        val next=RuntimeHeartProjection.merge(existing,request)
        if(next!=existing)persist(old.filterNot{it.id==next.id}+next)
        next
    }
    /** One AtomicFile transaction, stable existing drive/track identities; no raw journal writes. */
    override fun saveReview(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String):List<Pin> = saveReviewInternal(record,tracks,included,localId,zone,at,null)
    override fun saveReviewWithMedia(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String,media:ArchiveMediaDraft):List<Pin> = saveReviewInternal(record,tracks,included,localId,zone,at,media)
    private fun saveReviewInternal(record:DriveRecord,tracks:List<Track>,included:List<String>,localId:String,zone:String,at:String,media:ArchiveMediaDraft?):List<Pin> = synchronized(directoryLock) {
        require(localId.isNotBlank()); java.time.ZoneId.of(zone); Instant.parse(at)
        require(included.isNotEmpty() && included.distinct()==included && included.all { it in record.tracks })
        require(record.startedAt!=null && record.endedAt!=null && record.durationMs!=null)
        val old=readPins()
        require(old.none { it.sourceDriveKey==record.key && it.heartsReview?.localUserId?.let { id -> id!=localId }==true }) { "Archive profile conflict" }
        val meta=HeartsReviewMetadata(localId,record.endedAt,zone,included.toList(),record.tracks.filterNot { it in included },
            record.playbacks.map { it.playbackSessionId },record.qualityFlags,record.dataSources)
        val saved=included.map { id ->
            val track=tracks.singleOrNull { it.id==id } ?: Track(id,id,"메타데이터 미제공")
            val pin=ArchiveProjection.pinFromDrive(record,track,emptyList(),"",at)
            val existing=old.singleOrNull { it.sourceDriveKey==record.key && it.track?.id==id }
            require(existing?.heartsReview?.localUserId?.let { it==localId } != false) { "Archive profile conflict" }
            // Keep user note/tags/attachments, existing ID and first-save timestamp.
            val base=(existing ?: pin).copy(heartsReview=meta,deletedAt=null)
            if(media==null)base else {
                require(base.attachments.none{old->media.attachments.any{it.id==old.id && it!=old}}){"Conflicting attachment identity"}
                base.copy(attachments=(base.attachments+media.attachments).distinctBy{it.id},
                    coverAttachmentId=media.coverAttachmentId ?: base.coverAttachmentId,
                    note=media.note.takeIf{it.isNotBlank()} ?: base.note)
            }
        }
        val ids=saved.map { it.id }.toSet()
        val next=old.filterNot { it.id in ids }.map {
            if(it.sourceDriveKey==record.key && it.heartsReview!=null && it.runtimeHeart==null && it.track?.id !in included) it.copy(deletedAt=it.deletedAt ?: at) else it
        }+saved
        if(next!=old)persist(next)
        saved
    }
    override fun delete(id: String) = synchronized(directoryLock) { persist(readPins().map {
        if(it.id==id && it.deletedAt==null)it.copy(deletedAt=Instant.now().toString()) else it }) }
    private fun persist(pins: List<Pin>) {
        check(directory.isDirectory || directory.mkdirs())
        val data = JSONObject().put("version", 3).put("pins", JSONArray(pins.map { p ->
            JSONObject().put("id", p.id).put("savedAt", p.savedAt).put("driveId", p.driveId ?: JSONObject.NULL)
                .put("sourceDriveKey",p.sourceDriveKey ?: JSONObject.NULL).put("occurredAt",p.occurredAt ?: JSONObject.NULL)
                .put("deletedAt",p.deletedAt ?: JSONObject.NULL)
                .also { if(p.coverAttachmentId!=null)it.put("coverAttachmentId",p.coverAttachmentId) }
                .put("track", p.track?.let { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist) } ?: JSONObject.NULL)
                .put("place", p.place?.let { JSONObject().put("latitude", it.latitude).put("longitude", it.longitude) } ?: JSONObject.NULL)
                .put("tags", JSONArray(p.tags)).put("note", p.note).put("weather", p.weather ?: JSONObject.NULL)
                .also { json -> p.heartsReview?.let { h -> json.put("heartsReview",JSONObject()
                    .put("localUserId",h.localUserId).put("endedAt",h.endedAt).put("zoneId",h.zoneId)
                    .put("includedMusicIds",JSONArray(h.includedMusicIds)).put("excludedMusicIds",JSONArray(h.excludedMusicIds))
                    .put("playbackSessionIds",JSONArray(h.playbackSessionIds)).put("qualityFlags",JSONArray(h.qualityFlags.toList()))
                    .put("dataSources",JSONArray(h.dataSources.toList()))) }
                    p.runtimeHeart?.let { h -> json.put("runtimeHeart",JSONObject().put("version",1).put("localUserId",h.localUserId).put("appRunId",h.appRunId)
                     .put("sourceDeviceId",h.sourceDeviceId).put("syncState",h.syncState).put("captures",JSONArray(h.captures.map{c->
                      JSONObject().put("pinEventId",c.pinEventId).put("playbackSessionId",c.playbackSessionId).put("occurredAt",c.occurredAt)
                       .put("receivedElapsedMs",c.receivedElapsedMs).put("positionMs",c.positionMs).put("durationMs",c.durationMs ?: JSONObject.NULL)
                       .put("contextSnapshotId",c.contextSnapshotId ?: JSONObject.NULL).put("contextSnapshotJson",c.contextSnapshotJson ?: JSONObject.NULL)
                       .put("recommendationId",c.recommendationId ?: JSONObject.NULL).put("recommendationSnapshotJson",c.recommendationSnapshotJson ?: JSONObject.NULL)
                       .put("environment",c.environment).put("locationSource",c.locationSource ?: JSONObject.NULL)}))) }
                    if (p.attachments.isNotEmpty()) json.put("attachments", JSONArray(p.attachments.map { a ->
                    JSONObject().put("id",a.id).put("kind",a.kind).put("localReference",a.localReference)
                        .put("label",a.label).put("mimeType",a.mimeType ?: JSONObject.NULL)
                })) }
        })).toString().toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try { out.write(data); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
}

interface DriveHistoryRepository { fun refresh(): HistorySnapshot }

/** Incremental read-only journal projection. Partial trailing writes are retried, not parsed. */
class JsonlHistoryRepository(private val directory: File, private val localId: String,
    private val contextDirectory:File=File(directory.parentFile,"music-context-experiments")) : DriveHistoryRepository {
    private data class Drive(val key: String, val id: String, var start: String? = null, var end: String? = null,
        val tracks: MutableSet<String> = linkedSetOf(), val playbackWalls: MutableMap<String, Double> = mutableMapOf(),
        var place: Place? = null, var test: Boolean = false, var speedSamples: Int = 0,
        val playbackIds:MutableSet<String> = linkedSetOf(),val sources:MutableSet<String> = linkedSetOf())
    private data class Listening(var lastIndex: Long = 0, var wall: Double = 0.0,var musicId:String?=null,
        var summary:JSONObject?=null,val flags:MutableSet<String> = linkedSetOf())
    private val offsets = mutableMapOf<String, Long>()
    private val drives = linkedMapOf<String, Drive>()
    private val listening = mutableMapOf<String, Listening>()
    private val contexts=mutableMapOf<String,JSONObject>()
    private var malformed = 0
    private var speed: Double? = null
    private var speedDrive: String? = null
    private var speedElapsed: Long? = null
    private var speedTest = false
    private var speedAt = Instant.MIN
    private var location: Place? = null
    private var locationDrive: String? = null
    private var locationElapsed: Long? = null
    private var locationAt = Instant.MIN

    @Synchronized override fun refresh(): HistorySnapshot {
        var error: String? = null
        try {
            (directory.listFiles().orEmpty().toList()+contextDirectory.listFiles().orEmpty().toList()).filter { it.extension == "jsonl" }.sortedBy { it.lastModified() }.forEach { f ->
                FileInputStream(f).use { raw ->
                    val offset = offsets[f.absolutePath] ?: 0L
                    val boundary = raw.channel.size()
                    check(offset <= boundary) { "기록 파일이 외부에서 변경되었습니다" }
                    raw.channel.position(offset)
                    val input = BufferedInputStream(raw, 64 * 1024)
                    val line = ByteArrayOutputStream()
                    var cursor = offset
                    // Do not chase a live writer; next refresh reads any later append.
                    while (cursor < boundary) {
                        val byte = input.read()
                        if (byte < 0) break
                        cursor++
                        if (byte == 10) {
                            try {
                                val row=JSONObject(line.toByteArray().toString(Charsets.UTF_8))
                                if(f.parentFile==contextDirectory) {
                                    require(row.optString("featureVersion")=="1")
                                    val key="${row.getString("runId")}/${row.getString("driveSessionId")}/${row.getString("playbackSessionId")}"
                                    val prior=contexts[key]
                                    if(prior==null)contexts[key]=row
                                    else if(prior.toString()!=row.toString()) {
                                        prior.put("contextQuality","unknown").put("qualityFlags",JSONArray(listOf("conflicting_context_rows")))
                                        throw IllegalStateException("Conflicting context")
                                    }
                                } else accept(row)
                            }
                            catch (_: Exception) { malformed++ }
                            offsets[f.absolutePath] = cursor
                            line.reset()
                        } else line.write(byte)
                    }
                    // Partial UTF-8 lines are retried from the last complete byte offset.
                }
            }
        } catch (_: Exception) { error = "기록을 읽지 못했습니다. 원본 파일은 변경하지 않았습니다." }
        val records = drives.values.map { d ->
            val playbacks=d.playbackIds.map { pb ->
                val s=listening.getValue("${d.key}/$pb");val j=s.summary
                val flags=s.flags.toMutableSet()
                if(j==null)flags+="missing_listening_summary"
                if(j!=null && kotlin.math.abs((j.number("totalListeningWallMs") ?: -1.0)-s.wall)>.01)flags+="interval_summary_mismatch"
                if(j!=null)j.optJSONArray("qualityFlags")?.let { a -> repeat(a.length()){flags+=a.getString(it)} }
                val context=contexts["${d.key}/$pb"]?.takeIf { it.text("musicId")==s.musicId }
                val contextFlags=context?.optJSONArray("qualityFlags")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
                val usable=context?.text("contextQuality")=="available" && contextFlags.isEmpty() &&
                    context.number("avgSpeedKmh")?.let { it>=0 }==true && context.number("observationCoverage")?.let { it in 0.0..1.0 }==true
                val vehicle=context?.let { ArchivedVehicleContext(if(usable)"available" else "unknown",
                    if(usable)it.number("avgSpeedKmh") else null,it.number("observationCoverage"),it.text("source"),contextFlags) }
                PlaybackArchive(pb,s.musicId,s.wall,j?.number("uniqueListenedMediaMs"),j?.number("uniqueListeningRate"),j?.text("confidence"),flags,vehicle)
            }
            val flags=linkedSetOf<String>()
            if(d.start==null)flags+="missing_session_start"
            if(d.end==null)flags+="missing_session_end"
            if(d.place==null)flags+="location_unavailable"
            if(d.test)flags+="emulator_or_test_source"
            if(malformed>0 || error!=null)flags+="journal_read_incomplete"
            flags+=playbacks.flatMap { it.qualityFlags }
            DriveRecord(d.key, d.id, d.start, d.end, d.tracks.toList(),
            d.playbackWalls.values.sum(), d.playbackWalls.isNotEmpty(), d.place, d.test, d.speedSamples,playbacks,flags,d.sources.toSet()) }
            .sortedByDescending { it.startedAt ?: it.endedAt ?: "" }
        return HistorySnapshot(records, speed, speedDrive, speedElapsed, speedTest,
            location, locationDrive, locationElapsed, records.sumOf { it.listeningWallMs },
            records.any { it.hasListening }, malformed, error)
    }

    private fun accept(r: JSONObject) {
        // Unattributed historical records remain visible; another identified profile does not.
        if (r.text("localUserId")?.let { it != localId } == true) return
        val id = r.text("driveSessionId") ?: return
        val run = r.text("runId") ?: return
        val key = "$run/$id"
        val d = drives.getOrPut(key) { Drive(key, id) }
        d.test = d.test || r.optString("environment") == "emulator" || r.optString("testData") == "true"
        d.sources+="${r.text("source") ?: "unknown"}/${r.text("inputOrigin") ?: "unknown"}/${r.text("environment") ?: "unknown"}"
        val at = r.text("occurredAt")?.let { runCatching { Instant.parse(it).toString() }.getOrNull() }
        val event = r.optJSONObject("event")
        val music = event?.optJSONObject("music")
        if (event?.optString("domain") == "MUSIC") {
            music?.text("musicId")?.let { d.tracks += it }
            r.text("playbackSessionId")?.let { pb -> d.playbackIds+=pb
                val state=listening.getOrPut("$key/$pb"){Listening()}
                music?.text("musicId")?.let { state.musicId=it } }
        }
        when (r.optString("kind")) {
            "session_started" -> if(d.start==null)d.start = at
            "session_stopped" -> if(d.end==null)d.end = at
            "speed_sample" -> {
                d.speedSamples++
                if (at != null && Instant.parse(at) >= speedAt) {
                speed = r.number("speedKmh"); speedDrive = id
                speedElapsed = r.number("receivedElapsedMs")?.toLong(); speedTest = d.test
                speedAt = Instant.parse(at)
                }
            }
            "listening_interval" -> {
                val pb = r.text("playbackSessionId") ?: return
                val state = listening.getOrPut("$key/$pb") { Listening() }
                d.playbackIds+=pb;state.musicId=r.text("musicId") ?: state.musicId
                val index = r.number("intervalIndex")?.toLong() ?: return
                if (index > state.lastIndex) {
                    if(index>state.lastIndex+1)state.flags+="missing_or_reordered_intervals"
                    state.lastIndex = index
                    state.wall += (r.number("creditedWallMs") ?: 0.0).coerceAtLeast(0.0)
                    d.playbackWalls[pb] = state.wall
                    if ((r.number("creditedWallMs") ?: 0.0) > 0) r.text("musicId")?.let { d.tracks += it }
                }
            }
            "listening_summary" -> {
                val pb=r.text("playbackSessionId") ?: return
                d.playbackIds+=pb
                val state=listening.getOrPut("$key/$pb"){Listening()}
                state.musicId=r.text("musicId") ?: state.musicId
                state.musicId?.let { d.tracks+=it }
                if(state.summary==null)state.summary=r else if(state.summary.toString()!=r.toString())state.flags+="conflicting_listening_summaries"
                // Keep wall credit from intervals; never replace it with a summary total.
            }
        }
        event?.optJSONObject("location")?.let { loc ->
            validPlace(loc.number("latitude"), loc.number("longitude"))?.let { p ->
                d.place = p
                val elapsed = r.number("receivedElapsedMs")?.toLong()
                if (elapsed != null && at != null && Instant.parse(at) >= locationAt) {
                    location = p; locationDrive = id; locationElapsed = elapsed
                    locationAt = Instant.parse(at)
                }
            }
        }
    }
}

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }
internal fun JSONObject.number(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
fun validPlace(latitude: Double?, longitude: Double?): Place? =
    if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) Place(latitude, longitude) else null
