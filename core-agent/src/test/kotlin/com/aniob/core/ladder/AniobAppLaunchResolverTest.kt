package com.aniob.core.ladder

import com.aniob.core.knowledge.AppCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The step-0 app shortcut: it must fire for "open <app> and …" and never for a sentence that
 * merely mentions an app, because a wrong launch is a visible, unrecoverable action.
 */
class AniobAppLaunchResolverTest {

    private val catalog = listOf(
        AppCatalogEntry(packageName = "com.google.android.deskclock", appName = "Clock", aliases = listOf("alarm")),
        AppCatalogEntry(packageName = "com.android.contacts", appName = "Contacts"),
        AppCatalogEntry(packageName = "com.android.settings", appName = "Settings"),
        AppCatalogEntry(packageName = "com.google.android.calculator", appName = "Calculator"),
        AppCatalogEntry(packageName = "com.android.chrome", appName = "Chrome"),
        AppCatalogEntry(packageName = "com.google.android.dialer", appName = "Phone"),
        AppCatalogEntry(packageName = "com.example.settings", appName = "Settings Hub")
    )

    @Test
    fun resolvesTheAppNamedAtTheStartOfAnImperative() {
        assertEquals(
            "com.google.android.deskclock",
            AniobAppLaunchResolver.resolve("Open Clock and set an alarm for 7:30 AM", catalog)?.packageName
        )
        assertEquals(
            "com.android.contacts",
            AniobAppLaunchResolver.resolve("Open Contacts and search for John", catalog)?.packageName
        )
        assertEquals(
            "com.google.android.calculator",
            AniobAppLaunchResolver.resolve("Launch Calculator", catalog)?.packageName
        )
    }

    @Test
    fun theLongestMatchingNameWins() {
        // "Settings Hub" and "Settings" both prefix-match; the more specific name is chosen.
        val target = AniobAppLaunchResolver.resolve("Open Settings Hub and tap About", catalog)
        assertEquals("com.example.settings", target?.packageName)
    }

    @Test
    fun aMentionInsideASentenceNeverLaunches() {
        assertNull(AniobAppLaunchResolver.resolve("Set an alarm in Clock for 7 am", catalog))
        assertNull(AniobAppLaunchResolver.resolve("What is the Clock app", catalog))
    }

    @Test
    fun aSubScreenNameDoesNotMatchAnApp() {
        // "Wi-Fi settings" must not be read as the Settings app: the name is checked as a prefix.
        assertNull(AniobAppLaunchResolver.resolve("Open Wi-Fi settings", catalog))
    }

    @Test
    fun thePolitenessPrefixIsTolerated() {
        assertEquals(
            "com.android.chrome",
            AniobAppLaunchResolver.resolve("please open the Chrome browser", catalog)?.packageName
        )
    }

    @Test
    fun anUninstalledAppIsNotLaunched() {
        assertNull(
            AniobAppLaunchResolver.resolve(
                prompt = "Open Clock and set an alarm",
                entries = catalog,
                isInstalled = { false }
            )
        )
    }

    @Test
    fun anEmptyCatalogOrPromptResolvesNothing() {
        assertNull(AniobAppLaunchResolver.resolve("Open Clock", emptyList()))
        assertNull(AniobAppLaunchResolver.resolve("", catalog))
        assertNull(AniobAppLaunchResolver.resolve("Open", catalog))
    }

    @Test
    fun aliasesResolveToTheirApp() {
        assertEquals(
            "com.google.android.deskclock",
            AniobAppLaunchResolver.resolve("Open the alarm app", catalog)?.packageName
        )
    }
}
