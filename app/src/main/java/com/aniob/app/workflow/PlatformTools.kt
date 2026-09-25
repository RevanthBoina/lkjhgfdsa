package com.aniob.app.workflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import com.aniob.core.workflow.CapabilitySnapshot
import com.aniob.core.workflow.Destination

/**
 * Launch specification produced by PlatformTools and executed by WorkflowActionHost.
 */
sealed class LaunchSpec {
    data class ActivityIntent(val intent: Intent, val actionId: String) : LaunchSpec()
    data class CustomTabs(val url: String, val browserPackage: String?, val actionId: String) : LaunchSpec()
    data class Sharesheet(val intent: Intent, val actionId: String) : LaunchSpec()
}

/**
 * PlatformTools: Native intent resolution, Custom Tabs intent building, Sharesheet,
 * clipboard handling, and capability snapshots.
 *
 * Invariant: Does not retain any Activity or View references.
 */
class PlatformTools(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * Checks if a package is available to launch.
     */
    fun isAppAvailable(packageName: String): Boolean {
        return packageManager.getLaunchIntentForPackage(packageName) != null
    }

    /**
     * Builds an intent to launch an installed application.
     */
    fun buildAppLaunchIntent(packageName: String): Intent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent
    }

    /**
     * Finds the preferred or default browser that supports Custom Tabs.
     */
    fun findCustomTabsBrowser(preferredPackage: String? = null): String? {
        val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
        val resolveInfos = packageManager.queryIntentServices(serviceIntent, PackageManager.MATCH_DEFAULT_ONLY)

        val supportedPackages = resolveInfos.mapNotNull { it.serviceInfo?.packageName }
        if (preferredPackage != null && supportedPackages.contains(preferredPackage)) {
            return preferredPackage
        }

        // Check default browser
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val defaultResolve = packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultPkg = defaultResolve?.activityInfo?.packageName
        if (defaultPkg != null && supportedPackages.contains(defaultPkg)) {
            return defaultPkg
        }

        return supportedPackages.firstOrNull()
    }

    /**
     * Builds a Custom Tabs launch intent.
     */
    fun buildCustomTabsIntent(url: String, browserPackage: String?): Intent {
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)

        val customTabsIntent = builder.build()
        val intent = customTabsIntent.intent
        intent.data = Uri.parse(url)
        if (browserPackage != null) {
            intent.setPackage(browserPackage)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Builds a Sharesheet intent with narrow flags.
     */
    fun buildShareIntent(
        title: String,
        text: String? = null,
        uri: Uri? = null,
        mimeType: String = "text/plain"
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(sendIntent, title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooser
    }

    /**
     * Safely copies brief or result text to the system clipboard.
     */
    fun copyToClipboard(label: String, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Takes a live capability snapshot for a destination without background scanning.
     */
    fun checkCapabilities(destination: Destination): CapabilitySnapshot {
        val availability = if (destination.url != null) {
            val browserAvailable = findCustomTabsBrowser() != null
            if (browserAvailable) CapabilitySnapshot.HandlerAvailability.AVAILABLE
            else CapabilitySnapshot.HandlerAvailability.UNAVAILABLE
        } else {
            val appAvailable = isAppAvailable(destination.handler)
            if (appAvailable) CapabilitySnapshot.HandlerAvailability.AVAILABLE
            else CapabilitySnapshot.HandlerAvailability.UNAVAILABLE
        }

        return CapabilitySnapshot(
            supportedAdapter = if (destination.url != null) "CustomTabs" else "NativeIntent",
            requiredPermissions = emptyList(),
            handlerAvailability = availability,
            networkAvailable = true,
            supportedDocumentOperations = listOf("copy_brief", "open_external")
        )
    }
}
