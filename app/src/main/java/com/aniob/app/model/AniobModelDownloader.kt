package com.aniob.app.model

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.aniob.app.network.AniobHttpClientSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
    val sizeGb: Double,
    val ramRequiredGb: Double,
    val minDeviceRamGb: Int,
    val url: String,
    val bestFor: String,
    val description: String,
    val requiresCharging: Boolean,
    val isInstalled: Boolean = false,
    val downloadProgress: Int = 0,
    val isDownloading: Boolean = false
)

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
        const val MIN_FREE_SPACE_MULTIPLIER = 2.0
        const val PREF_DEFAULT_MODEL = "default_model_id"

        val ALL_MODELS = listOf(
            AniobModelInfo(
                id = "nomic-embed-text-v1.5-q4",
                name = "Nomic Embed Text v1.5",
                displayName = "Nomic Embed - Semantic Search",
                params = "0.137B",
                sizeGb = 0.3,
                ramRequiredGb = 2.0,
                minDeviceRamGb = 4,
                url = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf",
                bestFor = "embeddings, semantic skill matching, memory RAG, +30% accuracy",
                description = "Nomic 137M, 0.3GB, 2GB RAM, 8K context, best small embedding, Apache 2.0, for skill matching",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "phi4-mini-3.8b-q4",
                name = "Phi-4 Mini 3.8B",
                displayName = "Phi-4 Mini (Recommended for 8GB+)",
                params = "3.8B",
                sizeGb = 2.1,
                ramRequiredGb = 2.7,
                minDeviceRamGb = 8,
                url = "https://huggingface.co/microsoft/Phi-4-mini-instruct-gguf/resolve/main/phi-4-mini-instruct-q4_k_m.gguf",
                bestFor = "Reasoning, coding, general chat, smartest small model",
                description = "Microsoft 3.8B, MMLU 68% HumanEval 70%, 30-50 tok/s CPU, smartest on 8GB+ phones",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "gemma3-4b-q4",
                name = "Gemma 3 4B",
                displayName = "Gemma 3 4B (Balanced Default)",
                params = "4B",
                sizeGb = 2.2,
                ramRequiredGb = 2.9,
                minDeviceRamGb = 8,
                url = "https://huggingface.co/google/gemma-3-4b-it-qat-q4_0-gguf/resolve/main/model.gguf",
                bestFor = "Balanced, efficient, Google, chat",
                description = "Google 4B, 2.9GB RAM at Q4, fits 8GB+ phones, tight on 6GB use Phi-4 Mini instead",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "llama3.2-3b-q4",
                name = "Llama 3.2 3B",
                displayName = "Llama 3.2 3B (Tool Calling Workhorse)",
                params = "3.2B",
                sizeGb = 1.8,
                ramRequiredGb = 2.2,
                minDeviceRamGb = 6,
                url = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                bestFor = "Tool calling, function calling, general, fastest 3B",
                description = "Meta 3.2B, 2.2GB RAM, 25-45 tok/s, best for tool calling, workhorse",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "qwen3-4b-q4",
                name = "Qwen 3 4B",
                displayName = "Qwen 3 4B (Multilingual Coding)",
                params = "4B",
                sizeGb = 2.2,
                ramRequiredGb = 2.85,
                minDeviceRamGb = 8,
                url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/qwen3-4b-q4_k_m.gguf",
                bestFor = "Multilingual, coding, 29+ languages, newest",
                description = "Alibaba Qwen3 4B, 2.85GB RAM at Q4, 20-28 tok/s, mid-range 6-8GB",
                requiresCharging = false
            ),
            AniobModelInfo(
                id = "qwen2.5-coder-7b-q4",
                name = "Qwen 2.5 Coder 7B",
                displayName = "Qwen 2.5 Coder 7B (Coding Specialist)",
                params = "7B",
                sizeGb = 4.7,
                ramRequiredGb = 4.7,
                minDeviceRamGb = 8,
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                bestFor = "Coding, debugging, HumanEval 88%",
                description = "Alibaba Coder 7B, 4.7GB RAM, 50 tok/s, gold standard for 8GB laptops, tight on 8GB phones, use plugged in",
                requiresCharging = true
            ),
            AniobModelInfo(
                id = "mistral-7b-q4",
                name = "Mistral 7B",
                displayName = "Mistral 7B (Fastest 7B)",
                params = "7.2B",
                sizeGb = 4.0,
                ramRequiredGb = 4.5,
                minDeviceRamGb = 8,
                url = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/mistral-7b-instruct-v0.3.Q4_K_M.gguf",
                bestFor = "Speed, lowest memory 7B, fast chat, Apache 2.0",
                description = "Mistral AI 7B, 4.5GB RAM, 52 tok/s, lowest memory of 7B class, most context headroom on 8GB",
                requiresCharging = true
            ),
            AniobModelInfo(
                id = "gemma3-1b-q4",
                name = "Gemma 3 1B",
                displayName = "Gemma 3 1B (Lightweight)",
                params = "1B",
                sizeGb = 0.6,
                ramRequiredGb = 0.72,
                minDeviceRamGb = 4,
                url = "https://huggingface.co/google/gemma-3-1b-it-qat-q4_0-gguf/resolve/main/model.gguf",
                bestFor = "Old phones 4GB RAM, lightweight, fast",
                description = "Google 1B, 720MB RAM at Q4, runs on 4GB RAM minimum, ideal for budget devices",
                requiresCharging = false
            )
        )
    }

    fun getModelsDir(): File {
        return File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    fun getTempDir(): File {
        return File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
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
            deviceInfo.totalRamGb >= 8 -> "phi4-mini-3.8b-q4" // Recommended for 8GB+
            deviceInfo.totalRamGb >= 6 -> "llama3.2-3b-q4"    // Tool calling workhorse for 6GB
            else -> "gemma3-1b-q4"                            // Lightweight for 4GB
        }
    }

    fun getAvailableModels(): List<AniobModelInfo> {
        val modelsDir = getModelsDir()
        val deviceInfo = getDeviceInfo()
        val active = _activeDownloads.value
        val progressMap = _downloadStates.value

        return ALL_MODELS.mapNotNull { model ->
            val isInstalled = File(modelsDir, "${model.id}.gguf").exists()
            // On devices with <6GB RAM, hide 7B models. On 6GB-7GB RAM devices, show warning
            if (deviceInfo.totalRamGb < 6 && model.minDeviceRamGb >= 8 && model.sizeGb > 3.0) {
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
        return File(getModelsDir(), "$modelId.gguf").exists()
    }

    fun getInstalledModelFile(modelId: String): File? {
        val file = File(getModelsDir(), "$modelId.gguf")
        return if (file.exists()) file else null
    }

    suspend fun downloadModelWithProgress(modelId: String, onProgress: (DownloadProgress) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val model = ALL_MODELS.find { it.id == modelId } ?: return@withContext Result.failure(Exception("Model not found"))
        val deviceInfo = getDeviceInfo()
        if (deviceInfo.freeStorageGb < model.sizeGb * 2.0) return@withContext Result.failure(Exception("Need ${model.sizeGb * 2} GB free"))
        if (model.requiresCharging && !deviceInfo.isCharging) return@withContext Result.failure(Exception("Plug in charging for 7B model"))

        val modelsDir = getModelsDir()
        val tmpDir = getTempDir()
        val finalFile = File(modelsDir, "$modelId.gguf")
        val tmpFile = File(tmpDir, "$modelId.gguf.tmp")
        val downloaded = if (tmpFile.exists()) tmpFile.length() else 0L
        val total = (model.sizeGb * 1024 * 1024 * 1024).toLong()
        val startTime = System.currentTimeMillis()
        _activeDownloads.value = _activeDownloads.value + modelId
        _pausedDownloads.value = _pausedDownloads.value - modelId
        cancelRequested.remove(modelId)
        pauseRequested.remove(modelId)

        try {
            val request = Request.Builder().url(model.url).apply {
                if (downloaded > 0) addHeader("Range", "bytes=$downloaded-")
            }.build()
            val client = AniobHttpClientSingleton.client
            val response = client.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                // Real, honest failure: never fabricate a placeholder .gguf that the native engine
                // cannot actually load (that silently reports "installed" while every inference fails).
                val reason = if (response.code == 416) {
                    "Partial download out of sync (HTTP 416) - retry from scratch"
                } else {
                    "Download failed (HTTP ${response.code})"
                }
                return@withContext Result.failure(IllegalStateException(reason))
            }
            val input = response.body?.byteStream() ?: return@withContext Result.failure(Exception("Empty body"))
            val output = FileOutputStream(tmpFile, downloaded > 0)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = downloaded
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
                    // Keep the .tmp file so a later resume picks up via the Range header.
                    val resumeSize = tmpFile.length()
                    val paused = DownloadProgress(modelId, progressOf(resumeSize, total), 0L, 0L, isPaused = true, downloadedBytes = resumeSize, totalBytes = total)
                    _downloadProgressFlow.value = _downloadProgressFlow.value + (modelId to paused)
                    _activeDownloads.value = _activeDownloads.value - modelId
                    _pausedDownloads.value = _pausedDownloads.value + modelId
                    onProgress(paused)
                    return@withContext Result.failure(IllegalStateException("Download paused"))
                }
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                val elapsed = (System.currentTimeMillis() - startTime) / 1000L
                val bps = if (elapsed > 0) (totalRead - downloaded) / elapsed else 0L
                val remaining = (total - totalRead).coerceAtLeast(0L)
                val eta = if (bps > 0) remaining / bps else 0L
                val prog = DownloadProgress(modelId, progressOf(totalRead, total), bps, eta, downloadedBytes = totalRead, totalBytes = total)
                _downloadProgressFlow.value = _downloadProgressFlow.value + (modelId to prog)
                _downloadStates.value = _downloadStates.value + (modelId to prog.progress)
                onProgress(prog)
            }
            output.close()
            input.close()
            if (tmpFile.exists()) {
                tmpFile.renameTo(finalFile)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            // Surface the real error. Previously this wrote a fake placeholder .gguf and returned
            // success, which made a failed 2GB download look installed and then break every inference.
            _downloadProgressFlow.value = _downloadProgressFlow.value - modelId
            _downloadStates.value = _downloadStates.value - modelId
            Result.failure(e)
        } finally {
            _activeDownloads.value = _activeDownloads.value - modelId
            cancelRequested.remove(modelId)
            pauseRequested.remove(modelId)
        }
    }

    /** Requests cooperative pause for an in-flight download. The .tmp file is kept for resume. */
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
            File(getTempDir(), "$modelId.gguf.tmp").delete()
            _pausedDownloads.value = _pausedDownloads.value - modelId
            return
        }
        cancelRequested.getOrPut(modelId) { AtomicBoolean() }.set(true)
    }

    fun isPaused(modelId: String): Boolean = _pausedDownloads.value.contains(modelId)

    private fun progressOf(downloadedBytes: Long, totalBytes: Long): Int =
        if (totalBytes <= 0) 0 else ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)

    suspend fun downloadModel(modelId: String, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val model = ALL_MODELS.find { it.id == modelId }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown model: $modelId"))

        val deviceInfo = getDeviceInfo()
        if (deviceInfo.freeStorageGb < model.sizeGb * MIN_FREE_SPACE_MULTIPLIER) {
            return@withContext Result.failure(
                IllegalStateException("Insufficient storage. Need ${model.sizeGb * MIN_FREE_SPACE_MULTIPLIER} GB free, only ${deviceInfo.freeStorageGb} GB available.")
            )
        }

        if (model.requiresCharging && !deviceInfo.isCharging && deviceInfo.batteryPct < 50) {
            return@withContext Result.failure(
                IllegalStateException("${model.name} requires device to be charging or >50% battery to download.")
            )
        }

        val modelsDir = getModelsDir()
        val tempFile = File(getTempDir(), "${model.id}.gguf.tmp")
        val finalFile = File(modelsDir, "${model.id}.gguf")

        _activeDownloads.value = _activeDownloads.value + modelId

        try {
            val existingLen = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(model.url)
            if (existingLen > 0) {
                requestBuilder.header("Range", "bytes=$existingLen-")
            }

            val client = AniobHttpClientSingleton.client
            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                return@withContext Result.failure(
                    IllegalStateException("Download failed (HTTP ${response.code})")
                )
            }

            val body = response.body
            val totalBytes = (body?.contentLength() ?: 0L) + existingLen
            val effectiveTotal = if (totalBytes > 0) totalBytes else (model.sizeGb * 1024 * 1024 * 1024).toLong()

            body?.byteStream()?.use { input ->
                FileOutputStream(tempFile, existingLen > 0).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var currentDownloaded = existingLen

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        currentDownloaded += bytesRead

                        val progress = ((currentDownloaded.toDouble() / effectiveTotal.toDouble()) * 100).toInt().coerceIn(0, 99)
                        _downloadStates.value = _downloadStates.value + (modelId to progress)
                        onProgress(progress)
                    }
                }
            }

            if (tempFile.exists()) {
                tempFile.renameTo(finalFile)
            }
            _downloadStates.value = _downloadStates.value + (modelId to 100)
            onProgress(100)
            Result.success(finalFile)
        } catch (e: Exception) {
            // Surface the real error rather than writing a placeholder model file.
            Result.failure(e)
        } finally {
            _activeDownloads.value = _activeDownloads.value - modelId
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val file = File(getModelsDir(), "$modelId.gguf")
        val deleted = file.delete()
        if (deleted) {
            _downloadStates.value = _downloadStates.value - modelId
            if (prefs.getString(PREF_DEFAULT_MODEL, "") == modelId) {
                prefs.edit().remove(PREF_DEFAULT_MODEL).apply()
            }
        }
        return deleted
    }

    fun getInstalledModels(): List<File> {
        return getModelsDir().listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList()
    }
}
