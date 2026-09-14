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

    @Synchronized
    fun findPackageForName(query: String): String? {
        val q = query.trim().lowercase()
        // Exact match on package name
        if (catalog.containsKey(q)) return q

        // Exact match on app name
        catalog.values.firstOrNull { it.appName.lowercase() == q }?.let { return it.packageName }

        // Alias match
        catalog.values.firstOrNull { entry -> entry.aliases.any { it.lowercase() == q } }?.let { return it.packageName }

        // Substring match
        catalog.values.firstOrNull { it.appName.lowercase().contains(q) }?.let { return it.packageName }

        return null
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
