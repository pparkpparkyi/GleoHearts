package com.example.gleo_type.ui.app

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.pow

class CarMusicPresentationTest {
    @Test fun unverifiedMissingAndAmbiguousPlaybackAreRejected(){
        val t=CarMusicCandidate("model","media","title","artist",emptySet(),emptySet(),null,null,null,null,null,true,"VERIFIED_LOCAL_SHA256","unknown",null,"test")
        val catalog=listOf(Track("media","title","artist"))
        assertNotNull(CarMusicPlaybackGate.resolve(t,catalog))
        assertNull(CarMusicPlaybackGate.resolve(t.copy(mediaId=null),catalog))
        assertNull(CarMusicPlaybackGate.resolve(t.copy(mappingStatus="PENDING"),catalog))
        assertNull(CarMusicPlaybackGate.resolve(t,emptyList()));assertNull(CarMusicPlaybackGate.resolve(t,catalog+catalog))
    }
    @Test fun missingAndInvalidAcousticsUseTokens(){for(dark in listOf(true,false)){
        assertEquals("PLEOS_TOKENS",CarBackgroundPolicy.resolve(null,dark).origin)
        assertEquals("PLEOS_TOKENS",CarBackgroundPolicy.resolve(CarAcousticFeatures("id",Double.NaN,Double.NaN),dark).origin)
        assertEquals("PLEOS_TOKENS",CarBackgroundPolicy.resolve(CarAcousticFeatures("id",-1.0),dark).origin)
    }}
    @Test fun normalizedClampedDeterministicAndOrderIndependent(){
        val a=CarAcousticFeatures("id",136.0,null,setOf("acoustic","energetic"))
        assertEquals(CarBackgroundPolicy.resolve(a,true),CarBackgroundPolicy.resolve(a.copy(moods=setOf("energetic","acoustic")),true))
        assertEquals(CarBackgroundPolicy.resolve(a.copy(bpmEstimated=240.0),true),CarBackgroundPolicy.resolve(a.copy(bpmEstimated=1000.0),true))
        assertEquals(setOf("bpmEstimated","catalog_moods"),CarBackgroundPolicy.resolve(a,true).featuresUsed)
    }
    @Test fun energyAndMoodDoNotNeedInventedBpm(){
        val f=CarAcousticFeatures("id",energy=.7)
        assertEquals(setOf("energy"),CarBackgroundPolicy.resolve(f,true).featuresUsed)
        assertEquals(setOf("catalog_moods"),CarBackgroundPolicy.resolve(CarAcousticFeatures("id",moods=setOf("acoustic")),false).featuresUsed)
    }
    @Test fun normalTextContrastStaysAboveWcagAA(){
        fun luminance(c:Long):Double {
            fun ch(s:Int):Double{val x=((c shr s) and 255)/255.0;return if(x<=.04045)x/12.92 else ((x+.055)/1.055).pow(2.4)}
            return .2126*ch(16)+.7152*ch(8)+.0722*ch(0)
        }
        for(d in listOf(true,false))for(bpm in listOf(1.0,40.0,136.0,240.0,1000.0))for(e in listOf(-1.0,0.0,1.0,10.0)){
            val text=luminance(CarV10Design.colors(d).textPrimary)
            for(color in CarBackgroundPolicy.resolve(CarAcousticFeatures("id",bpm,e,setOf("energetic")),d).colors){
                val bg=luminance(color);assertTrue((maxOf(text,bg)+.05)/(minOf(text,bg)+.05)>=4.5)
            }
        }
    }
    @Test fun artworkRequiresExactIdsPermissionAndHash(){
        val bytes="SYNTHETIC_OWNED_TEST_BYTES".toByteArray();val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val r=CarArtworkReference("model","media","image","car_v10/artwork/test.png",hash,"owned_fixture","APPROVED_INTERNAL_USE","synthetic_test_only")
        assertTrue(CarArtworkPolicy.accepts(r,"model","media",bytes))
        assertFalse(CarArtworkPolicy.accepts(r,"other","media",bytes));assertFalse(CarArtworkPolicy.accepts(r,"model","other",bytes))
        assertFalse(CarArtworkPolicy.accepts(r.copy(permission="PENDING"),"model","media",bytes))
        assertFalse(CarArtworkPolicy.accepts(r.copy(assetPath="car_v10/artwork/../secret"),"model","media",bytes))
        assertFalse(CarArtworkPolicy.accepts(r,"model","media",bytes+1))
    }
}
