package com.example.gleo_type

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class HeartMapProjectionTest {
 private fun pin(id:String,place:Place?,deleted:String?=null)=Pin(id,"2026-11-13T00:00:00Z",null,null,place,emptyList(),"",deletedAt=deleted)
 @Test fun invalidMissingAndDeletedNeverBecomeMarkersAndDoNotInventCoordinates() {
  val records=listOf(pin("first",Place(37.0,127.0)),pin("same",Place(37.0,127.0)),pin("other",Place(38.0,128.0)),
   pin("missing",null),pin("nan",Place(Double.NaN,127.0)),pin("invalid",Place(91.0,127.0)),pin("deleted",Place(37.0,127.0),"2026-11-13T00:00:01Z"))
  val groups=HeartMapProjection.groups(records)
  assertEquals(2,groups.size);assertEquals(3,groups.sumOf{it.pins.size})
  assertEquals(setOf("missing","nan","invalid"),HeartMapProjection.unlocated(records).map{it.id}.toSet())
  assertEquals(setOf("first","same"),groups.single{it.place==Place(37.0,127.0)}.pins.map{it.id}.toSet())
  assertEquals(0,HeartMapProjection.groups(emptyList()).size)
 }
}
