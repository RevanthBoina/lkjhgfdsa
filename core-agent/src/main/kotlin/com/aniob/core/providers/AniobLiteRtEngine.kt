package com.aniob.core.providers

/**
 * Native LiteRT-LM binding surface.
 *
 * Mirrors the [AniobLlamaCppEngine] `nativeLoadModel` / `nativeGenerate` contract so both
 * on-device engines can be driven through the same call sites. Implementations are backed by
 * JNI; keeping it an interface lets the provider be exercised on a pure JVM test host.
 */
interface AniobNativeLlmEngine {
    /** True when the native library is loadable and the model file is present. */
    fun isReady(): Boolean

    fun load(path: String): Boolean

    /** Blocking generation; returns null when the native layer failed. */
    fun generate(prompt: String): String?

    fun release()
}

/**
 * Real LiteRT-LM engine backed by `liblitertlm.so` (arm64-v8a).
 * No mock fallback lives here: if the library cannot be loaded, [isReady] is false and the
 * caller must surface an explicit failure rather than a fabricated action.
 */
class AniobLiteRtEngine(private val modelPath: String) : AniobNativeLlmEngine {

    private var nativeLoaded: Boolean = try {
        System.loadLibrary(LITE_RT_LIBRARY)
        true
    } catch (_: Throwable) {
        false
    }

    override fun isReady(): Boolean = nativeLoaded && java.io.File(modelPath).exists()

    override fun load(path: String): Boolean {
        if (!isReady()) return false
        return try {
            nativeLoadModel(path)
        } catch (_: Throwable) {
            false
        }
    }

    override fun generate(prompt: String): String? = try {
        nativeGenerate(prompt)
    } catch (_: Throwable) {
        null
    }

    /**
     * Drops the loaded model's native buffers. The shared library stays mapped (the JVM cannot
     * unload it), so the engine remains reusable by a subsequent [load] after memory pressure.
     */
    override fun release() {
        try {
            nativeRelease()
        } catch (_: Throwable) {
            // Native layer already unloaded; nothing to release.
        }
    }

    // Native methods - implemented in C++ via NDK arm64-v8a only.
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String): String
    private external fun nativeRelease()

    companion object {
        const val LITE_RT_LIBRARY = "litertlm"
    }
}