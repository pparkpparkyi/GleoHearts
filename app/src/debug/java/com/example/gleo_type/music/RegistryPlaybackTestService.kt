package com.example.gleo_type.music

import com.example.gleo_type.AppContainer
import java.io.File

/** Debug-only, non-exported. Same production Player/Session/callbacks, isolated repositories. */
class RegistryPlaybackTestService : MusicPlaybackService() {
    companion object {
        @Volatile var testRoot: File? = null
        @Volatile var activeContainer: AppContainer? = null
        @Volatile var destructions = 0
    }
    private val root by lazy {
        val candidate = checkNotNull(testRoot).canonicalFile
        check(candidate.path.startsWith(cacheDir.canonicalPath + File.separator))
        candidate
    }
    override val container: AppContainer by lazy {
        AppContainer(File(root, "journal"), "isolated_audio_registry_test", File(root, "identity"))
            .also { activeContainer = it }
    }
    override fun audioAssetRoot(): File = File(root, "audio-assets")
    override fun onDestroy() { super.onDestroy(); activeContainer = null; destructions++ }
}
