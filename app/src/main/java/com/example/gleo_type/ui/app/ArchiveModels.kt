package com.example.gleo_type.ui.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Track(val id: String, val title: String, val artist: String)
data class Place(val latitude: Double, val longitude: Double)
data class Pin(val id: String, val savedAt: String, val driveId: String?, val track: Track?,
               val place: Place?, val tags: List<String>, val note: String, val weather: String? = null,
               val attachments: List<ArchiveAttachment> = emptyList(),
               val sourceDriveKey: String? = null, val occurredAt: String? = null,
               val deletedAt: String? = null, val heartsReview: HeartsReviewMetadata? = null,
               val runtimeHeart:RuntimeHeartMetadata?=null,val coverAttachmentId:String?=null)

/** Optional metadata on explicitly saved CAR review pins; existing version-3 records remain valid. */
data class HeartsReviewMetadata(val localUserId:String,val endedAt:String,val zoneId:String,
    val includedMusicIds:List<String>,val excludedMusicIds:List<String>,val playbackSessionIds:List<String>,
    val qualityFlags:Set<String>,val dataSources:Set<String>)

data class PlaybackArchive(val playbackSessionId:String, val musicId:String?, val creditedWallMs:Double,
    val uniqueListenedMediaMs:Double?, val uniqueListeningRate:Double?, val confidence:String?,
    val qualityFlags:Set<String>,val vehicleContext:ArchivedVehicleContext?=null)
data class ArchivedVehicleContext(val quality:String,val avgSpeedKmh:Double?,val observationCoverage:Double?,
    val source:String?,val qualityFlags:Set<String>)
data class DriveRecord(val key: String, val id: String, val startedAt: String?, val endedAt: String?,
    val tracks: List<String>, val listeningWallMs: Double, val hasListening: Boolean,
    val place: Place?, val testData: Boolean, val speedSampleCount: Int = 0,
    val playbacks:List<PlaybackArchive> = emptyList(), val qualityFlags:Set<String> = emptySet(),
    val dataSources:Set<String> = emptySet()) {
    val date: LocalDate? get() = dateOf(startedAt ?: endedAt)
    val durationMs: Long? get() = runCatching {
        java.time.Duration.between(Instant.parse(startedAt), Instant.parse(endedAt)).toMillis().takeIf { it >= 0 }
    }.getOrNull()
}
data class HistorySnapshot(val drives: List<DriveRecord> = emptyList(), val speedKmh: Double? = null,
    val speedDrive: String? = null, val speedElapsedMs: Long? = null, val speedIsTest: Boolean = false,
    val location: Place? = null, val locationDrive: String? = null, val locationElapsedMs: Long? = null,
    val listeningWallMs: Double = 0.0, val listeningAvailable: Boolean = false,
    val malformedLines: Int = 0, val error: String? = null)

fun dateOf(at: String?, zone:ZoneId = ZoneId.systemDefault()): LocalDate? = runCatching { Instant.parse(at).atZone(zone).toLocalDate() }.getOrNull()

object ArchiveProjection {
    fun linked(pin:Pin,drive:DriveRecord)=if(pin.sourceDriveKey!=null)pin.sourceDriveKey==drive.key else pin.driveId==drive.id
    fun onDate(history:HistorySnapshot,pins:List<Pin>,date:LocalDate,zone:ZoneId=ZoneId.systemDefault()):Pair<List<DriveRecord>,List<Pin>> {
        val drives=history.drives.filter { dateOf(it.startedAt ?: it.endedAt,zone)==date }
        return drives to pins.filter { it.deletedAt==null && (dateOf(it.occurredAt ?: it.savedAt,zone)==date || drives.any { d -> linked(it,d) }) }
    }
    /** Stable identity only for an explicit save. No automatic archive is created at collection stop. */
    fun pinFromDrive(drive:DriveRecord,track:Track?,tags:List<String>,note:String,at:String):Pin {
        require(drive.startedAt!=null && drive.endedAt!=null && drive.durationMs!=null) { "Confirmed collection end required" }
        require(track==null || track.id in drive.tracks) { "Track not observed in this drive" }
        val id=java.util.UUID.nameUUIDFromBytes((drive.key+"/"+(track?.id ?: "no_music")).toByteArray(Charsets.UTF_8)).toString()
        return Pin(id,at,drive.id,track,drive.place,tags,note,sourceDriveKey=drive.key,occurredAt=drive.startedAt)
    }
}

/** Future model/server adapter replaces this provider; it never impersonates AI output. */
interface RecommendationProvider { fun recommendations(catalog: List<Track>): List<Track>; val label: String }
class LocalPreviewRecommendations : RecommendationProvider {
    override val label = "모델 연결 전 · 로컬 테스트 음악"
    override fun recommendations(catalog: List<Track>) = catalog.sortedBy { if (it.id.startsWith("jamendo_")) "0${it.id}" else "1${it.id}" }
}
interface MapGateway { val available: Boolean }
interface WeatherGateway { suspend fun weather(place: Place): String? }
interface DestinationGateway { val available: Boolean }
interface GleoGateway { val available: Boolean }
/** Unconnected SDKs return no fabricated values. */
object UnconnectedServices : MapGateway, DestinationGateway, GleoGateway, WeatherGateway {
    override val available = false
    override suspend fun weather(place: Place): String? = null
}

object GesturePolicy {
    fun dismiss(upwardPx: Float, thresholdPx: Float) = upwardPx <= -thresholdPx
    fun sheetLevel(current: Int, deltaPx: Float, thresholdPx: Float): Int = when {
        deltaPx < -thresholdPx -> (current + 1).coerceAtMost(2)
        deltaPx > thresholdPx -> (current - 1).coerceAtLeast(0)
        else -> current
    }
}
