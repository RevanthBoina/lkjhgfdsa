package com.aniob.app.model

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.aniob.app.network.AniobHttpClientSingleton
import com.aniob.core.narration.FailureReasonCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-Device Model Information representation.
 */
data class AniobModelInfo(
    val id: String,
    val name: String,
    val displayName: String,
    val params: String,
    val sizeBytes: Long,
    val ramRequiredGb: Double,
    val minDeviceRamGb: Int,
    val repoId: String,
    val fileName: String,
    val bestFor: String,
    val description: String,
    val requiresCharging: Boolean,
    val sha256: String = "",
    val isCustom: Boolean = false,
    val customUrl: String? = null,
    val isInstalled: Boolean = false,
    val downloadProgress: Int = 0,
    val isDownloading: Boolean = false
) {
    val url: String
        get() = customUrl ?: "https://huggingface.co/$repoId/resolve/main/$fileName"

    val sizeGb: Double
        get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)

    // Backward-compatibility constructor
    constructor(
        id: String,
        name: String,
        displayName: String,
        params: String,
        sizeGb: Double,
        ramRequiredGb: Double,
        minDeviceRamGb: Int,
        url: String,
        bestFor: String,
        description: String,
        requiresCharging: Boolean,
        isInstalled: Boolean = false,
        downloadProgress: Int = 0,
        isDownloading: Boolean = false
    ) : this(
        id = id,
        name = name,
        displayName = displayName,
        params = params,
        sizeBytes = (sizeGb * 1024 * 1024 * 1024).toLong(),
        ramRequiredGb = ramRequiredGb,
        minDeviceRamGb = minDeviceRamGb,
        repoId = "",
        fileName = "$id.gguf",
        bestFor = bestFor,
        description = description,
        requiresCharging = requiresCharging,
        sha256 = "",
        isCustom = true,
        customUrl = url,
        isInstalled = isInstalled,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading
    )
}

data class DeviceInfo(
    val totalRamGb: Int,
    val freeRamGb: Int,
    val freeStorageGb: Double,
    val batteryPct: Int,
    val isCharging: Boolean,
    val isWifi: Boolean
)

data class DownloadProgress(
    val modelId: String,
    val progress: Int,
    val bytesPerSecond: Long,
    val etaSeconds: Long,
    val isPaused: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

class AniobModelDownloader(private val context: Context) {

    private val _downloadStates = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadStates: StateFlow<Map<String, Int>> = _downloadStates.asStateFlow()

    private val _downloadProgressFlow = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressFlow: StateFlow<Map<String, DownloadProgress>> = _downloadProgressFlow.asStateFlow()

    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()

    private val _pausedDownloads = MutableStateFlow<Set<String>>(emptySet())
    val pausedDownloads: StateFlow<Set<String>> = _pausedDownloads.asStateFlow()

    /** Cooperative cancellation/pause flags polled inside the streaming loop. */
    private val cancelRequested = ConcurrentHashMap<String, AtomicBoolean>()
    private val pauseRequested = ConcurrentHashMap<String, AtomicBoolean>()

    private val prefs = context.getSharedPreferences("aniob_models_pref", Context.MODE_PRIVATE)

    companion object {
        const val MODELS_DIR = "models"
        const val TEMP_DIR = "models_tmp"
        const val PREF_DEFAULT_MODEL = "default_model_id"
        const val PREF_CUSTOM_MODELS = "aniob_custom_models"

        val ALL_MODELS = listOf(
            AniobModelInfo(
                id = "nomic-embed-text-v1.5-q4",
                name = "Nomic Embed Text v1.5",
                displayName = "Nomic Embed - Semantic Search",
                params = "0.137B",
                sizeBytes = 83_886_080L,
                ramRequiredGb = 2.0,
                minDeviceRamGb = 4,
                repoId = "nomic-ai/nomic-embed-text-v1.5-GGUF",
                fileName = "nomic-embed-text-v1.5.Q4_K_M.gguf",
                bestFor = "embeddings, semantic skill matching, memory search",
                description = "Nomic 137M, fast embedding model for semantic matching",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "phi4-mini-3.8b-q4",
                name = "Phi-4 Mini 3.8B",
                displayName = "Phi-4 Mini (Recommended)",
                params = "3.8B",
                sizeBytes = 2_254_438_400L,
                ramRequiredGb = 2.7,
                minDeviceRamGb = 8,
                repoId = "unsloth/Phi-4-mini-instruct-GGUF",
                fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
                bestFor = "Reasoning, coding, general tasks",
                description = "Microsoft 3.8B, high quality reasoning on capable devices",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "gemma3-4b-q4",
                name = "Gemma 3 4B",
                displayName = "Gemma 3 4B (Balanced)",
                params = "4B",
                sizeBytes = 2_362_232_832L,
                ramRequiredGb = 2.9,
                minDeviceRamGb = 8,
                repoId = "ggml-org/gemma-3-4b-it-GGUF",
                fileName = "gemma-3-4b-it-Q4_K_M.gguf",
                bestFor = "Balanced, efficient, everyday tasks",
                description = "Google 4B, balanced efficiency and reasoning",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "llama3.2-3b-q4",
                name = "Llama 3.2 3B",
                displayName = "Llama 3.2 3B (Fast Tasks)",
                params = "3.2B",
                sizeBytes = 1_932_735_283L,
                ramRequiredGb = 2.2,
                minDeviceRamGb = 6,
                repoId = "unsloth/Llama-3.2-3B-Instruct-GGUF",
                fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                bestFor = "Tool calling, quick actions, responsive general tasks",
                description = "Meta 3.2B, fast and reliable execution",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "qwen3-4b-q4",
                name = "Qwen 3 4B",
                displayName = "Qwen 3 4B (Multilingual)",
                params = "4B",
                sizeBytes = 2_362_232_832L,
                ramRequiredGb = 2.85,
                minDeviceRamGb = 8,
                repoId = "Qwen/Qwen3-4B-GGUF",
                fileName = "Qwen3-4B-Q4_K_M.gguf",
                bestFor = "Multilingual understanding and instructions",
                description = "Alibaba Qwen3 4B, comprehensive language coverage",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "qwen2.5-coder-7b-q4",
                name = "Qwen 2.5 Coder 7B",
                displayName = "Qwen 2.5 Coder 7B (Code Specialist)",
                params = "7B",
                sizeBytes = 4_681_359_360L,
                ramRequiredGb = 4.7,
                minDeviceRamGb = 8,
                repoId = "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
                fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                bestFor = "Complex coding and script execution",
                description = "Alibaba Coder 7B, specialized code generation",
                requiresCharging = true
            ),
            AniobModelInfo(
                id = "mistral-7b-q4",
                name = "Mistral 7B",
                displayName = "Mistral 7B (Fast Performance)",
                params = "7.2B",
                sizeBytes = 4_294_967_296L,
                ramRequiredGb = 4.5,
                minDeviceRamGb = 8,
                repoId = "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
                fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
                bestFor = "Fast throughput, general tasks",
                description = "Mistral AI 7B, fast and versatile",
                requiresCharging = true
            ),
            AniobModelInfo(
                id = "gemma3-1b-q4",
                name = "Gemma 3 1B",
                displayName = "Gemma 3 1B (Lightweight)",
                params = "1B",
                sizeBytes = 629_145_600L,
                ramRequiredGb = 0.72,
                minDeviceRamGb = 4,
                repoId = "ggml-org/gemma-3-1b-it-GGUF",
                fileName = "gemma-3-1b-it-Q4_K_M.gguf",
                bestFor = "Compact footprint, standard phones",
                description = "Google 1B, runs smoothly on smaller devices",
                requiresCharging = false
            )
        )
    }

    /** Persistent storage for installed models (non-evictable). */
    fun getModelsDir(): File {
        return File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Temporary download folder on the SAME partition as modelsDir to support atomic move. */
    fun getTempDir(): File {
        return File(context.filesDir, TEMP_DIR).apply { mkdirs() }
    }

    fun getDeviceInfo(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamGb = ((memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)) + 0.5).toInt().coerceAtLeast(4)
        val freeRamGb = (memInfo.availMem / (1024.0 * 1024.0 * 1024.0)).toInt().coerceAtLeast(1)

        val freeStorageGb = context.filesDir.freeSpace / (1024.0 * 1024.0 * 1024.0)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = bm?.isCharging ?: true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return DeviceInfo(
            totalRamGb = totalRamGb,
            freeRamGb = freeRamGb,
            freeStorageGb = freeStorageGb,
            batteryPct = batteryPct,
            isCharging = isCharging,
            isWifi = isWifi
        )
    }

    fun getRecommendedModel(deviceInfo: DeviceInfo): String {
        return when {
            deviceInfo.totalRamGb >= 8 -> "phi4-mini-3.8b-q4"
            deviceInfo.totalRamGb >= 6 -> "llama3.2-3b-q4"
            else -> "gemma3-1b-q4"
        }
    }

    fun getAllCatalogModels(): List<AniobModelInfo> {
        val custom = getCustomModels()
        return ALL_MODELS + custom
    }

    fun getAvailableModels(): List<AniobModelInfo> {
        val modelsDir = getModelsDir()
        val deviceInfo = getDeviceInfo()
        val active = _activeDownloads.value
        val progressMap = _downloadStates.value

        return getAllCatalogModels().mapNotNull { model ->
            val isInstalled = isModelInstalled(model.id)
            if (!model.isCustom && deviceInfo.totalRamGb < 6 && model.minDeviceRamGb >= 8 && model.sizeGb > 3.0) {
                null
            } else {
                model.copy(
                    isInstalled = isInstalled,
                    isDownloading = active.contains(model.id),
                    downloadProgress = progressMap[model.id] ?: if (isInstalled) 100 else 0
                )
            }
        }
    }

    fun getDefaultModelId(): String {
        val saved = prefs.getString(PREF_DEFAULT_MODEL, null)
        if (!saved.isNullOrBlank() && isModelInstalled(saved)) {
            return saved
        }
        val installed = getInstalledModels()
        return installed.firstOrNull()?.nameWithoutExtension ?: getRecommendedModel(getDeviceInfo())
    }

    fun setDefaultModelId(modelId: String) {
        prefs.edit().putString(PREF_DEFAULT_MODEL, modelId).apply()
    }

    fun isModelInstalled(modelId: String): Boolean {
        val model = getAllCatalogModels().find { it.id == modelId }
        val byId = File(getModelsDir(), "$modelId.gguf")
        if (byId.exists() && byId.length() > 0) return true
        if (model != null) {
            val byFile = File(getModelsDir(), model.fileName)
            if (byFile.exists() && byFile.length() > 0) return true
        }
        return false
    }

    fun getInstalledModelFile(modelId: String): File? {
        val model = getAllCatalogModels().find { it.id == modelId }
        if (model != null) {
            val byFile = File(getModelsDir(), model.fileName)
            if (byFile.exists() && byFile.length() > 0) return byFile
        }
        val byId = File(getModelsDir(), "$modelId.gguf")
        return if (byId.exists() && byId.length() > 0) byId else null
    }

    suspend fun downloadModelWithProgress(modelId: String, onProgress: (DownloadProgress) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val model = getAllCatalogModels().find { it.id == modelId }
            ?: return@withContext Result.failure(IllegalArgumentException("Model not found: $modelId"))

        val deviceInfo = getDeviceInfo()
        // Storage check: require model size + 512MB headroom (single gate, no double-counting)
        val requiredBytes = model.sizeBytes + (512L * 1024 * 1024)
        val freeBytes = context.filesDir.freeSpace
        if (freeBytes < requiredBytes) {
            val reqGb = String.format("%.2f", requiredBytes / (1024.0 * 1024 * 1024))
            val availGb = String.format("%.2f", freeBytes / (1024.0 * 1024 * 1024))
            return@withContext Result.failure(IllegalStateException("Insufficient storage. Need $reqGb GB free, only $availGb GB available."))
        }

        if (model.requiresCharging && !deviceInfo.isCharging && deviceInfo.batteryPct < 50) {
            return@withContext Result.failure(IllegalStateException("${model.name} requires device to be charging or >50% battery."))
        }

        val modelsDir = getModelsDir()
        val tmpDir = getTempDir()
        val finalFile = File(modelsDir, model.fileName)
        val tmpFile = File(tmpDir, "${model.id}.gguf.tmp")
        val downloaded = if (tmpFile.exists()) tmpFile.length() else 0L

        val startTime = System.currentTimeMillis()
        _activeDownloads.value = _activeDownloads.value + modelId
        _pausedDownloads.value = _pausedDownloads.value - modelId
        cancelRequested.remove(modelId)
        pauseRequested.remove(modelId)

        try {
            val requestBuilder = Request.Builder().url(model.url)
            if (downloaded > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloaded-")
            }
            val client = AniobHttpClientSingleton.client
            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                val reason = FailureReasonCopy.downloadError(response.code, model.name)
                return@withContext Result.failure(IllegalStateException(reason))
            }

            val isPartial = response.code == 206
            val actualStart = if (isPartial) downloaded else 0L
            val body = response.body ?: return@withContext Result.failure(IllegalStateException("Empty response body"))
            val remoteContentLen = body.contentLength()
            val totalBytes = if (isPartial && remoteContentLen > 0) {
                actualStart + remoteContentLen
            } else if (remoteContentLen > 0) {
                remoteContentLen
            } else {
                model.sizeBytes
            }

            val output = FileOutputStream(tmpFile, isPartial && downloaded > 0)
            val input = body.byteStream()
            val buffer = ByteArray(32768)
            var bytesRead: Int
            var totalRead = actualStart

            try {
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelRequested[modelId]?.get() == true) {
                        output.close()
                        input.close()
                        tmpFile.delete()
                        _downloadProgressFlow.value = _downloadProgressFlow.value - modelId
                        _downloadStates.value = _downloadStates.value - modelId
                        return@withContext Result.failure(IllegalStateException("Download cancelled"))
                    }
                    if (pauseRequested[modelId]?.get() == true) {
                        output.close()
                        input.close()
                        val resumeSize = tmpFile.length()
                        val paused = DownloadProgress(modelId, progressOf(resumeSize, totalBytes), 0L, 0L, isPaused = true, downloadedBytes = resumeSize, totalBytes = totalBytes)
                        _downloadProgressFlow.value = _downloadProgressFlow.value + (modelId to paused)
                        _activeDownloads.value = _activeDownloads.value - modelId
                        _pausedDownloads.value = _pausedDownloads.value + modelId
                        onProgress(paused)
                        return@withContext Result.failure(IllegalStateException("Download paused"))
                    }

                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000L
                    val bps = if (elapsed > 0) (totalRead - actualStart) / elapsed else 0L
                    val remaining = (totalBytes - totalRead).coerceAtLeast(0L)
                    val eta = if (bps > 0) remaining / bps else 0L
                    val prog = DownloadProgress(modelId, progressOf(totalRead, totalBytes), bps, eta, downloadedBytes = totalRead, totalBytes = totalBytes)

                    _downloadProgressFlow.value = _downloadProgressFlow.value + (modelId to prog)
                    _downloadStates.value = _downloadStates.value + (modelId to prog.progress)
                    onProgress(prog)
                }
            } finally {
                output.close()
                input.close()
            }

            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Temporary file was empty or missing"))
            }

            // Move temporary file to final location safely
            if (finalFile.exists()) finalFile.delete()
            val moveSuccess = try {
                Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (_: Exception) {
                tmpFile.renameTo(finalFile)
            }

            if (!moveSuccess || !finalFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Failed to move temporary download to final file"))
            }

            // Optional SHA-256 verification
            if (model.sha256.isNotBlank()) {
                val computedHash = computeSha256(finalFile)
                if (!computedHash.equals(model.sha256, ignoreCase = true)) {
                    finalFile.delete()
                    return@withContext Result.failure(IllegalStateException("Integrity check failed: SHA-256 mismatch"))
                }
            }

            _downloadStates.value = _downloadStates.value + (modelId to 100)
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadProgressFlow.value = _downloadProgressFlow.value - modelId
            _downloadStates.value = _downloadStates.value - modelId
            Result.failure(e)
        } finally {
            _activeDownloads.value = _activeDownloads.value - modelId
            cancelRequested.remove(modelId)
            pauseRequested.remove(modelId)
        }
    }

    /** Direct download function delegating to progress-enabled implementation. */
    suspend fun downloadModel(modelId: String, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val result = downloadModelWithProgress(modelId) { prog ->
            onProgress(prog.progress)
        }
        if (result.isSuccess) {
            val file = getInstalledModelFile(modelId) ?: File(getModelsDir(), "$modelId.gguf")
            Result.success(file)
        } else {
            Result.failure(result.exceptionOrNull() ?: IllegalStateException("Download failed"))
        }
    }

    /** Side-load escape hatch: import a local .gguf file from device. */
    suspend fun registerCustomFile(displayName: String, sourceFile: File): Result<AniobModelInfo> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Source file does not exist or is empty"))
            }
            val safeId = "custom-" + System.currentTimeMillis()
            val targetFile = File(getModelsDir(), "$safeId.gguf")
            sourceFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val info = AniobModelInfo(
                id = safeId,
                name = displayName.ifBlank { "Custom Model" },
                displayName = displayName.ifBlank { "Custom Model" },
                params = "Custom",
                sizeBytes = targetFile.length(),
                ramRequiredGb = 2.0,
                minDeviceRamGb = 4,
                repoId = "",
                fileName = targetFile.name,
                bestFor = "User imported model",
                description = "Imported from local file",
                requiresCharging = false,
                isCustom = true,
                isInstalled = true
            )
            saveCustomModel(info)
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Side-load escape hatch: register a custom URL for download. */
    fun registerCustomUrl(name: String, url: String, estimatedSizeBytes: Long = 1_000_000_000L): AniobModelInfo {
        val safeId = "custom-url-" + System.currentTimeMillis()
        val info = AniobModelInfo(
            id = safeId,
            name = name.ifBlank { "Custom Web Model" },
            displayName = name.ifBlank { "Custom Web Model" },
            params = "Custom",
            sizeBytes = estimatedSizeBytes,
            ramRequiredGb = 2.0,
            minDeviceRamGb = 4,
            repoId = "",
            fileName = "$safeId.gguf",
            bestFor = "Custom user URL",
            description = "Downloaded from $url",
            requiresCharging = false,
            isCustom = true,
            customUrl = url,
            isInstalled = false
        )
        saveCustomModel(info)
        return info
    }

    fun getCustomModels(): List<AniobModelInfo> {
        val jsonStr = prefs.getString(PREF_CUSTOM_MODELS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<AniobModelInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AniobModelInfo(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        displayName = obj.getString("displayName"),
                        params = obj.optString("params", "Custom"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        ramRequiredGb = obj.optDouble("ramRequiredGb", 2.0),
                        minDeviceRamGb = obj.optInt("minDeviceRamGb", 4),
                        repoId = obj.optString("repoId", ""),
                        fileName = obj.getString("fileName"),
                        bestFor = obj.optString("bestFor", "Custom model"),
                        description = obj.optString("description", ""),
                        requiresCharging = obj.optBoolean("requiresCharging", false),
                        isCustom = true,
                        customUrl = if (obj.isNull("customUrl")) null else obj.optString("customUrl")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomModel(model: AniobModelInfo) {
        val existing = getCustomModels().toMutableList()
        existing.removeAll { it.id == model.id }
        existing.add(model)
        val arr = JSONArray()
        for (m in existing) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("displayName", m.displayName)
                put("params", m.params)
                put("sizeBytes", m.sizeBytes)
                put("ramRequiredGb", m.ramRequiredGb)
                put("minDeviceRamGb", m.minDeviceRamGb)
                put("repoId", m.repoId)
                put("fileName", m.fileName)
                put("bestFor", m.bestFor)
                put("description", m.description)
                put("requiresCharging", m.requiresCharging)
                put("customUrl", m.customUrl)
            }
            arr.put(obj)
        }
        prefs.edit().putString(PREF_CUSTOM_MODELS, arr.toString()).apply()
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(65536)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Requests cooperative pause for an in-flight download. */
    fun pauseDownload(modelId: String) {
        if (!_activeDownloads.value.contains(modelId)) return
        pauseRequested.getOrPut(modelId) { AtomicBoolean() }.set(true)
    }

    /** Re-starts a paused download; the Range header resumes from the retained .tmp file. */
    suspend fun resumeDownload(modelId: String, onProgress: (DownloadProgress) -> Unit): Result<Unit> {
        _pausedDownloads.value = _pausedDownloads.value - modelId
        pauseRequested.remove(modelId)
        return downloadModelWithProgress(modelId, onProgress)
    }

    /** Requests cooperative cancel and drops the partial .tmp file. */
    fun cancelDownload(modelId: String) {
        if (!_activeDownloads.value.contains(modelId)) {
            File(getTempDir(), "${modelId}.gguf.tmp").delete()
            _pausedDownloads.value = _pausedDownloads.value - modelId
            return
        }
        cancelRequested.getOrPut(modelId) { AtomicBoolean() }.set(true)
    }

    fun isPaused(modelId: String): Boolean = _pausedDownloads.value.contains(modelId)

    private fun progressOf(downloadedBytes: Long, totalBytes: Long): Int =
        if (totalBytes <= 0) 0 else ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)

    fun deleteModel(modelId: String): Boolean {
        val file = getInstalledModelFile(modelId) ?: File(getModelsDir(), "$modelId.gguf")
        val deleted = file.delete()
        if (deleted) {
            _downloadStates.value = _downloadStates.value - modelId
            if (prefs.getString(PREF_DEFAULT_MODEL, "") == modelId) {
                prefs.edit().remove(PREF_DEFAULT_MODEL).apply()
            }
            // If custom model, remove from custom list
            val custom = getCustomModels()
            if (custom.any { it.id == modelId }) {
                val remaining = custom.filter { it.id != modelId }
                val arr = JSONArray()
                for (m in remaining) {
                    val obj = JSONObject().apply {
                        put("id", m.id)
                        put("name", m.name)
                        put("displayName", m.displayName)
                        put("fileName", m.fileName)
                        put("sizeBytes", m.sizeBytes)
                    }
                    arr.put(obj)
                }
                prefs.edit().putString(PREF_CUSTOM_MODELS, arr.toString()).apply()
            }
        }
        return deleted
    }

    fun getInstalledModels(): List<File> {
        return getModelsDir().listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList()
    }
}
