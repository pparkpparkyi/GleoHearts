package com.example.gleo_type.ui.app

/** Read-only map projection. Exact stored coordinates only; never geocodes or collects location. */
data class HeartMapGroup(val id: String, val place: Place, val pins: List<Pin>)
object HeartMapProjection {
    fun groups(pins: List<Pin>): List<HeartMapGroup> = pins.filter {
        it.deletedAt == null && it.place?.let { p -> validPlace(p.latitude,p.longitude) } != null
    }.groupBy { it.place!! }.map { (place, records) ->
        HeartMapGroup(records.minOf { it.id },place,records.sortedByDescending { it.occurredAt ?: it.savedAt })
    }.sortedBy { it.id }
    fun unlocated(pins: List<Pin>) = pins.filter {
        it.deletedAt == null && it.place?.let { p -> validPlace(p.latitude,p.longitude) } == null
    }
}
