package com.aniob.core.providers

import java.io.File

/**
 * Single source of truth for on-device model file naming.
 *
 * Why this exists: the downloader and the routers used to disagree about what an installed model
 * file is called. `AniobModelDownloader` saved the download under the catalog entry's upstream
 * `fileName` (for example `Phi-4-mini-instruct-Q4_K_M.gguf`) while `AniobHybridEngineRouter`,
 * `AniobDoctor` and `AniobAutoRouter` each looked for `"$modelId.gguf"` (for example
 * `phi4-mini-3.8b-q4.gguf`). Every catalog entry has `id != fileName`, so those lookups could never
 * match: after a fully successful, SHA-verified download the AutoRouter still classified the model
 * as missing and escalated to cloud, the Doctor still reported it absent, and the engine was handed
 * a path that did not exist.
 *
 * The installed file is now always `<modelId>.<EXTENSION>`, derived from the id alone, so any layer
 * can resolve it without knowing the upstream filename. The upstream `fileName` remains what the
 * catalog downloads *from*; this is what it is stored *as*.
 *
 * Lives in `core-agent` so both the pure-JVM routers and the Android downloader import the same
 * constant. Zero `android.*` imports, per the module purity guard.
 */
object AniobModelFiles {

    /**
     * Extension of a model file this runtime can actually load.
     *
     * `.litertlm` is the Google AI Edge LiteRT-LM format consumed by
     * `com.google.ai.edge.litertlm:litertlm-android` (RUNTIME_DECISION.md Option A). The previous
     * `.gguf` value was llama.cpp's format, which needs a bundled `libllama.so` that this project
     * does not build and does not ship — Option B was explicitly rejected in that decision record.
     */
    const val EXTENSION = "litertlm"

    /** Canonical installed filename for a catalog id: `<modelId>.litertlm`. */
    fun fileName(modelId: String): String = "$modelId.$EXTENSION"

    /** Canonical installed file for a catalog id inside [modelsDir]. */
    fun file(modelsDir: File, modelId: String): File = File(modelsDir, fileName(modelId))

    /**
     * True when a non-empty installed model file exists for [modelId].
     *
     * Null-tolerant because callers (AutoRouter, Doctor) hold optional state; a missing directory or
     * an unset model id means "not installed", never "throw".
     */
    fun isInstalled(modelsDir: File?, modelId: String?): Boolean {
        if (modelsDir == null || modelId.isNullOrBlank()) return false
        val f = file(modelsDir, modelId)
        return f.exists() && f.length() > 0L
    }

    /**
     * True only when there IS filesystem context and the file is absent.
     *
     * The distinction from [isInstalled] matters: a null [modelsDir] or null [modelId] means the
     * caller has no filesystem context to consult, which must NOT be read as "the model is missing".
     * `AniobAutoRouter` relies on that to avoid escalating to cloud merely because optional state was
     * never populated. Using `!isInstalled(...)` here would flip those cases to "missing" and break
     * local-only routing.
     */
    fun isMissing(modelsDir: File?, modelId: String?): Boolean =
        modelsDir != null && !modelId.isNullOrBlank() && !isInstalled(modelsDir, modelId)

    /** Absolute path form used by engine constructors. */
    fun absolutePath(modelsDir: File, modelId: String): String = file(modelsDir, modelId).absolutePath
}
