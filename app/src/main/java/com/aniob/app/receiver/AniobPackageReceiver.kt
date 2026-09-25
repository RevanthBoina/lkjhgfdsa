package com.aniob.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.knowledge.AppCatalogEntry
import java.io.File

/**
 * Reflex 1: Package-change spinal reflex (Tasker/macroDroid trigger provider).
 * PACKAGE_ADDED / PACKAGE_REMOVED / PACKAGE_REPLACED update the persistent AppCatalog
 * JSON + markdown with zero LLM involvement, mirroring the accessibility-service build.
 */
class AniobPackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_PACKAGE_ADDED &&
            action != ACTION_PACKAGE_REMOVED &&
            action != ACTION_PACKAGE_REPLACED
        ) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        val catalog = AniobAppCatalog.getInstance()
        try {
            when (action) {
                ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REPLACED -> {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo)?.toString() ?: packageName
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    catalog.registerApp(AppCatalogEntry(packageName, appName, isSystem))
                }
                ACTION_PACKAGE_REMOVED -> {
                    catalog.removeApp(packageName)
                }
            }
            saveCatalog(context, catalog)
        } catch (e: Exception) {
            Log.w(TAG, "Package reflex failed for $packageName", e)
        }
    }

    private fun saveCatalog(context: Context, catalog: AniobAppCatalog) {
        val jsonFile = File(context.filesDir, "app_catalog.json")
        val mdFile = File(context.filesDir, "apps/app-registry.md")
        mdFile.parentFile?.mkdirs()
        catalog.saveToFile(jsonFile, mdFile)
    }

    companion object {
        private const val TAG = "AniobPackageReceiver"
        private const val ACTION_PACKAGE_ADDED = Intent.ACTION_PACKAGE_ADDED
        private const val ACTION_PACKAGE_REMOVED = Intent.ACTION_PACKAGE_REMOVED
        private const val ACTION_PACKAGE_REPLACED = Intent.ACTION_PACKAGE_REPLACED
    }
}