package com.example.gleo_type.ui.app

data class CarV10ReviewSnapshot(val driveKey:String,val selectedMusicId:String?,
    val excludedMusicIds:List<String>,val editing:Boolean)
enum class CarV10RememberStatus { NOT_CONNECTED, RESTRICTED }

/** In-memory archive draft for an observed, finished collection. Not recommendation feedback.
 * No writes, no playback commands, no success/loading simulation. STEP 5 supplies persistence.
 * complexAllowed must come from the existing freshness-checked DrivingUx gate at the UI boundary.
 */
class CarV10ReviewDraft private constructor(val driveKey:String,observed:List<String>,snapshot:CarV10ReviewSnapshot?) {
    val observedMusicIds=observed.toList()
    private val excluded=linkedSetOf<String>()
    var selectedMusicId:String?=null
        private set
    var editing=false
        private set
    val includedMusicIds get()=observedMusicIds.filterNot { it in excluded }
    val excludedMusicIds get()=excluded.toSet()
    val rememberStatus get()=CarV10RememberStatus.NOT_CONNECTED
    init {
        if(snapshot!=null) {
            require(snapshot.driveKey==driveKey) { "Different drive draft" }
            require(snapshot.excludedMusicIds.distinct().size==snapshot.excludedMusicIds.size &&
                snapshot.excludedMusicIds.all { it in observedMusicIds }) { "Unobserved exclusion" }
            require(snapshot.selectedMusicId==null || snapshot.selectedMusicId in observedMusicIds)
            excluded+=snapshot.excludedMusicIds;editing=snapshot.editing
        }
        selectedMusicId=snapshot?.selectedMusicId?.takeIf { it !in excluded } ?: includedMusicIds.firstOrNull()
    }
    fun select(id:String,complexAllowed:Boolean) {
        if(complexAllowed && id in includedMusicIds)selectedMusicId=id
    }
    fun beginEditing(complexAllowed:Boolean) { if(complexAllowed)editing=true }
    /** Cancelled gestures never call this. Invalid/short releases cannot change the draft. */
    fun releaseUpward(id:String,deltaPx:Float,thresholdPx:Float,complexAllowed:Boolean):Boolean {
        if(!complexAllowed || id !in includedMusicIds || !deltaPx.isFinite() ||
            !thresholdPx.isFinite() || thresholdPx<=0 || !GesturePolicy.dismiss(deltaPx,thresholdPx))return false
        excluded+=id;editing=true
        if(selectedMusicId==id)selectedMusicId=includedMusicIds.firstOrNull()
        return true
    }
    fun restore(id:String,complexAllowed:Boolean):Boolean {
        if(!complexAllowed || !excluded.remove(id))return false
        editing=true
        if(selectedMusicId==null)selectedMusicId=includedMusicIds.firstOrNull()
        return true
    }
    fun snapshot()=CarV10ReviewSnapshot(driveKey,selectedMusicId,excluded.toList(),editing)
    fun requestRemember(complexAllowed:Boolean):CarV10RememberStatus {
        // Explicitly no persistence adapter yet. Even a valid request cannot become SAVED.
        return if(complexAllowed)rememberStatus else CarV10RememberStatus.RESTRICTED
    }
    companion object {
        fun fromDrive(record:DriveRecord,snapshot:CarV10ReviewSnapshot?=null):CarV10ReviewDraft {
            require(record.startedAt!=null && record.endedAt!=null && record.durationMs!=null) { "Confirmed end required" }
            return CarV10ReviewDraft(record.key,record.tracks.distinct(),snapshot)
        }
        fun exclusionFraction(deltaPx:Float,thresholdPx:Float):Float =
            if(!deltaPx.isFinite() || !thresholdPx.isFinite() || thresholdPx<=0)0f else (-deltaPx/thresholdPx).coerceIn(0f,1f)
    }
}
