package com.aniob.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobOmniRouteProviderTest {

    @Test
    fun defaultProviderHasJsonModeDisabled() {
        val p = AniobOmniRouteProvider(apiKey = "k")
        assertFalse(p.isJsonMode())
    }

    @Test
    fun withJsonModeReturnsJsonEnforcingInstance() {
        val p = AniobOmniRouteProvider(apiKey = "k")
        val json = p.withJsonMode()
        assertTrue(json.isJsonMode())
        assertFalse(p.isJsonMode())
    }
}