package com.example.gleo_type.event

import com.example.gleo_type.event.vehicle.SpeedTimestampValidator
import com.example.gleo_type.realtime.RealtimePipeline
import com.example.gleo_type.recommendation.DrivingContextProvider
import com.example.gleo_type.context.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class SpeedTimestampValidationTest {
    @Test fun integerNsToMsIsExactAndWallClockIsNotAnInput() {
        val e = SpeedTimestampValidator().observe("sample", 10_000_999_999, 10002)
        assertTrue(e.eligible); assertEquals(10000L, e.sourceElapsedMs); assertEquals(2L, e.sourceAgeMs)
        assertEquals("10000", e.fields()["normalizedSourceElapsedMs"])
    }
    @Test fun futureInjectionOffsetIsRejectedNotSubtracted() {
        val e = SpeedTimestampValidator().observe("sample", 22_000_000_000, 10000)
        assertFalse(e.eligible); assertEquals("FUTURE_SOURCE_TIMESTAMP", e.status)
        assertEquals(22000L, e.sourceElapsedMs); assertNull(e.fields()["normalizedSourceElapsedMs"])
    }
    @Test fun staleAndWrongUnitAreNotRefreshedAtReception() {
        assertEquals("STALE_SOURCE_TIMESTAMP", SpeedTimestampValidator().observe("s", 1_000_000_000, 10000).status)
        assertFalse(SpeedTimestampValidator().observe("s", 10000, 10000).eligible)
    }
    @Test fun absentZeroAndNegativeAreExplicitlyUnknownOrInvalid() {
        for (raw in listOf(null, 0L, -1L)) assertFalse(SpeedTimestampValidator().observe("s", raw, 1000).eligible)
    }
    @Test fun duplicateAndSourceReversalRejectedWithoutPoisoningRecovery() {
        val v=SpeedTimestampValidator(); assertTrue(v.observe("a",10_000_000_000,10001).eligible)
        assertEquals("DUPLICATE_SOURCE_TIMESTAMP",v.observe("b",10_000_000_000,10002).status)
        assertEquals("REVERSED_SOURCE_TIMESTAMP",v.observe("c",9_999_000_000,10003).status)
        assertTrue(v.observe("d",10_004_000_000,10005).eligible)
    }
    @Test fun futureValueDoesNotAdvanceValidWatermark() {
        val v=SpeedTimestampValidator();v.observe("a",22_000_000_000,10000)
        assertTrue(v.observe("b",10_001_000_000,10002).eligible)
    }
    @Test fun knownFutureCachedEventCannotAgeIntoValidMeasurement() {
        val v=SpeedTimestampValidator();v.observe("a",22_000_000_000,10000)
        assertEquals("KNOWN_INVALID_SOURCE_TIMESTAMP",v.observe("cached",22_000_000_000,22001).status)
    }
    @Test fun oneMsClockResolutionIsDisclosedButLargeToleranceCannotBeConfigured() {
        assertTrue(SpeedTimestampValidator().observe("s",10_001_000_000,10000).eligible)
        assertFalse(SpeedTimestampValidator().observe("s",10_002_000_000,10000).eligible)
        assertTrue(runCatching { SpeedTimestampValidator(futureToleranceMs=12000) }.isFailure)
    }
    @Test fun receivingClockReversalAndSessionReset() {
        val v=SpeedTimestampValidator();v.observe("a",10_000_000_000,10000)
        assertEquals("RECEIVE_CLOCK_REVERSED",v.observe("b",9_999_000_000,9999).status)
        v.reset();assertNull(v.blockingReason());assertTrue(v.observe("c",100_000_000,101).eligible)
    }
    @Test fun longNanosConversionDoesNotUseFloatOrOverflow() {
        val e=SpeedTimestampValidator().observe("s",Long.MAX_VALUE,Long.MAX_VALUE/1_000_000)
        assertEquals(Long.MAX_VALUE/1_000_000,e.sourceElapsedMs);assertTrue(e.eligible)
    }
    @Test fun invalidSensorBlocksOldAvailableContextAndFreshSamplesRecoverWithTraceIds() {
        var now=0L;val v=SpeedTimestampValidator();val p=RealtimePipeline("run",{now})
        val provider=DrivingContextProvider("run",{"drive"},p,{now},sensorTimeBlock={v.blockingReason(now)})
        p.start("drive");provider.attach("owner");provider.connection("owner",CarConnectionSnapshot(CarConnectionState.CONNECTED,1))
        for(n in 1..10){now=n*1000L;val e=v.observe("sample-$n",now*1_000_000,now)
            p.speed("drive",0.0,now,"t",null,null,e.sampleId)}
        assertEquals("available",provider.current("drive").quality)
        assertTrue(provider.current("drive").sourceSampleIds.contains("sample-10"))
        v.observe("future",(now+12000)*1_000_000,now)
        assertEquals("unknown",provider.current("drive").quality)
        now+=1000;v.observe("fresh",now*1_000_000,now);p.speed("drive",0.0,now,"t",null,null,"fresh")
        val input=provider.current("drive");assertEquals("available",input.quality)
        assertFalse(input.sourceSampleIds.contains("future"))
        val snap=ContextSnapshotProvider("user","run",ContextObservationStore{"drive"},provider::current,{now},"isolated_test").capture("drive")
        val evidence=snap.toMap()["speedTimeEvidence"] as Map<*,*>
        assertTrue((evidence["sourceSampleIds"] as List<*>).contains("fresh"))
        now+=3000;assertEquals("unknown",provider.current("drive").quality)
    }
    @Test fun sourceAgeKeepsAdvancingAfterReceptionInsteadOfBeingRefreshed() {
        val v=SpeedTimestampValidator();assertTrue(v.observe("sample",8_000_000_000,10000).eligible)
        assertNull(v.blockingReason(10500))
        assertEquals("sensor_stale_source_timestamp",v.blockingReason(10501))
    }
    @Test fun cachedSourceBeforeReconnectDoesNotContributeEvenIfReceivedAfterward() {
        var now=1000L;val p=RealtimePipeline("run",{now});p.start("drive")
        val provider=DrivingContextProvider("run",{"drive"},p,{now})
        provider.attach("owner");provider.connection("owner",CarConnectionSnapshot(CarConnectionState.CONNECTED,2))
        for(n in 1..10){now=1000+n*1000L;p.speed("drive",0.0,now,"t",null,null,"cached-$n",999)}
        assertEquals("unknown",provider.current("drive").quality)
    }
    @Test fun evidenceObeysExistingStringOnlyJsonWriterContract() {
        for(raw in listOf(10_000_000_000L,22_000_000_000L)) {
            val e=SpeedTimestampValidator().observe("sample",raw,10000)
            val json=com.example.gleo_type.event.json.JsonWriter.write(e.fields())
            val parsed=TestJson.parse(json) as Map<*,*>
            assertEquals(e.eligible.toString(),parsed["sourceTimeEligible"])
        }
    }
    @Test fun rawAndNormalizedFieldsPersistAcrossWriterRestart() {
        val dir=java.nio.file.Files.createTempDirectory("speed-clock-journal").toFile()
        val raw=22_000_000_000L;val e=SpeedTimestampValidator().observe("sample",raw,10000)
        val fields=mapOf("vehicleTimestampNanos" to raw.toString(),"receivedElapsedMs" to "10000")+e.fields()
        assertTrue(com.example.gleo_type.event.send.JsonlEventSender(dir).append("speed_sample","drive","t","AAOS","raw_and_converted",fields).isSuccess)
        val first=dir.listFiles()!!.single();val bytes=first.readBytes()
        assertTrue(com.example.gleo_type.event.send.JsonlEventSender(dir).append("speed_sample","drive","t","AAOS","raw_and_converted",fields).isSuccess)
        assertArrayEquals(bytes,first.readBytes())
        val row=TestJson.parse(first.readText().trim()) as Map<*,*>
        assertEquals(raw.toString(),row["vehicleTimestampNanos"]);assertNull(row["normalizedSourceElapsedMs"])
        assertEquals("false",row["sourceTimeEligible"])
    }
}
