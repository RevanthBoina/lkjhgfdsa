package com.aniob.app.memory

import android.content.Context
import android.util.Log
import com.aniob.core.memory.RememberCache
import java.io.File

/**
 * File backing for [RememberCache]: `filesDir/remember_cache.json`.
 *
 * The cache itself is platform-free; this class only owns the bytes. Writes go to a sibling
 * temporary file and are renamed over the target, so a process death mid-write leaves the previous
 * cache intact instead of a truncated JSON document.
 */
class RememberCacheFile(private val file: File) {

    fun load(): RememberCache {
        if (!file.exists()) return RememberCache()
        return try {
            RememberCache.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "remember cache unreadable (${e.message}); starting empty")
            RememberCache()
        }
    }

    fun save(cache: RememberCache): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(cache.toJson())
            if (!temp.renameTo(file)) {
                // Some file systems refuse to rename over an existing file.
                if (file.exists()) file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "remember cache not saved (${e.message})")
            false
        }
    }

    fun exists(): Boolean = file.exists()

    fun delete(): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun path(): String = file.absolutePath

    companion object {
        private const val TAG = "RememberCache"

        fun forContext(context: Context): RememberCacheFile =
            RememberCacheFile(File(context.filesDir, RememberCache.FILE_NAME))
    }
}
