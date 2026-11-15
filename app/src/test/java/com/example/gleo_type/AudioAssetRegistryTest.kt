package com.example.gleo_type

import com.example.gleo_type.music.AudioAssetRegistry
import com.example.gleo_type.music.AudioAssetRegistry.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AudioAssetRegistryTest {
    private fun fixture(): Triple<File, Descriptor, Installed> {
        val root = Files.createTempDirectory("audio-registry-").toFile()
        val file = File(root, "audio/jamendo_api_123.mp3").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        val d = Descriptor("jamendo_api_123", "verified-source-123", file.name, 4, "Synthetic bytes", "Not audio")
        return Triple(root, d, Installed(d.musicId, d.driveFileId, "audio/${file.name}", 4, AudioAssetRegistry.sha256(file)))
    }
    @Test fun explicitMappingReopensButNeverClaimsDecodeFromFileExistence() {
        val (root,d,i)=fixture(); val r=AudioAssetRegistry(root,listOf(d),listOf(i))
        assertEquals(Status.AVAILABLE,r.resolve(d.musicId).status)
        assertTrue(r.observedProgressIds().isEmpty())
        assertEquals(Status.AVAILABLE,AudioAssetRegistry(root,listOf(d),listOf(i)).resolve(d.musicId).status)
    }
    @Test fun missingTruncatedAndSameSizeTamperingAreUnavailable() {
        val (root,d,i)=fixture();val r=AudioAssetRegistry(root,listOf(d),listOf(i));r.resolve(d.musicId)
        val f=File(root,i.relativePath);f.writeBytes(byteArrayOf(9,8,7,6))
        assertEquals(Status.HASH_MISMATCH,r.resolve(d.musicId).status)
        f.writeBytes(byteArrayOf(9));assertEquals(Status.SIZE_MISMATCH,r.resolve(d.musicId).status)
        f.delete();assertEquals(Status.MISSING,r.resolve(d.musicId).status)
    }
    @Test fun sourceMismatchPathTraversalAndDuplicateInstallFailClosed() {
        val (root,d,i)=fixture()
        for(bad in listOf(i.copy(driveFileId="other"),i.copy(relativePath="../outside.mp3"),i.copy(sha256="bad")))
            assertEquals(Status.INVALID_MAPPING,AudioAssetRegistry(root,listOf(d),listOf(bad)).resolve(d.musicId).status)
        assertEquals(Status.DUPLICATE_INSTALL,AudioAssetRegistry(root,listOf(d),listOf(i,i)).resolve(d.musicId).status)
    }
    @Test fun unavailableCandidatesNeverGainProgressAndDecoderErrorStaysExcluded() {
        val (root,d,i)=fixture();val r=AudioAssetRegistry(root,listOf(d),listOf(i));r.validateAll()
        r.observeProgress(d.musicId,300,true);assertEquals(setOf(d.musicId),r.observedProgressIds())
        r.markPlaybackError(d.musicId);assertFalse(r.isAvailable(d.musicId))
        assertEquals(Status.PLAYBACK_ERROR,r.resolve(d.musicId).status)
        val absent=AudioAssetRegistry(root,listOf(d),emptyList())
        assertEquals(Status.NOT_INSTALLED,absent.resolve(d.musicId).status)
        absent.observeProgress(d.musicId,1000,true);assertTrue(absent.observedProgressIds().isEmpty())
    }
}
