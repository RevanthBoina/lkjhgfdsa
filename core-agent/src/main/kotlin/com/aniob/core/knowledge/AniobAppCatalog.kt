package com.aniob.core.knowledge

import java.io.File

data class AppCatalogEntry(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val aliases: List<String> = emptyList()
)

/**
 * Persistent App Catalog (Phase 2.2).
 * Enables instant (<15ms) app name resolution without invoking PackageManager queries repeatedly.
 */
class AniobAppCatalog private constructor() {

    private val catalog = mutableMapOf<String, AppCatalogEntry>()

    @Synchronized
    fun registerApp(entry: AppCatalogEntry) {
        catalog[entry.packageName] = entry
    }

    @Synchronized
    fun removeApp(packageName: String) {
        catalog.remove(packageName)
    }

    @Synchronized
    fun getAll(): List<AppCatalogEntry> = catalog.values.toList()

    sealed class AppResolution {
        data class ExactMatch(val entry: AppCatalogEntry) : AppResolution()
        data class Ambiguous(val candidates: List<AppCatalogEntry>) : AppResolution()
        object NotFound : AppResolution()
    }

    @Synchronized
    fun findCandidates(query: String): List<AppCatalogEntry> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        catalog[q]?.let { return listOf(it) }

        val exactMatches = catalog.values.filter { entry ->
            entry.appName.lowercase() == q || entry.aliases.any { it.lowercase() == q }
        }
        if (exactMatches.isNotEmpty()) return exactMatches

        return catalog.values.filter { entry ->
            entry.appName.lowercase().contains(q) || entry.aliases.any { it.lowercase().contains(q) }
        }
    }

    @Synchronized
    fun resolveApp(query: String): AppResolution {
        val candidates = findCandidates(query)
        return when {
            candidates.isEmpty() -> AppResolution.NotFound
            candidates.size == 1 -> AppResolution.ExactMatch(candidates.first())
            else -> AppResolution.Ambiguous(candidates)
        }
    }

    @Synchronized
    fun findPackageForName(query: String): String? {
        return when (val res = resolveApp(query)) {
            is AppResolution.ExactMatch -> res.entry.packageName
            is AppResolution.Ambiguous -> null // Ambiguous match requires chooser
            is AppResolution.NotFound -> null
        }
    }

    @Synchronized
    fun toJson(): String {
        val entries = catalog.values.joinToString(",") { entry ->
            val aliasJson = entry.aliases.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
            """{"packageName":"${entry.packageName}","appName":"${entry.appName.replace("\"", "\\\"")}","isSystem":${entry.isSystemApp},"aliases":[$aliasJson]}"""
        }
        return "[$entries]"
    }

    @Synchronized
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# Aniob Installed Applications Catalog")
        sb.appendLine("| App Name | Package Name | System App |")
        sb.appendLine("|---|---|---|")
        catalog.values.sortedBy { it.appName.lowercase() }.forEach {
            sb.appendLine("| ${it.appName} | `${it.packageName}` | ${if (it.isSystemApp) "Yes" else "No"} |")
        }
        return sb.toString()
    }

    @Synchronized
    fun saveToFile(jsonFile: File, mdFile: File) {
        try {
            jsonFile.writeText(toJson())
            mdFile.writeText(toMarkdown())
        } catch (_: Exception) {}
    }

    companion object {
        private val instance = AniobAppCatalog()
        fun getInstance(): AniobAppCatalog = instance
    }
}
