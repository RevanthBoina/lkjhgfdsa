package com.aniob.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings screen, the ViewModel gate and the provider all share these rules, so they are
 * pinned here. Pure JVM: no Robolectric, no Android runtime.
 */
class AniobProviderConfigTest {

    @Test
    fun resolveEndpointAppendsTheChatPathExactlyOnce() {
        assertEquals(
            "http://192.168.1.20:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("http://192.168.1.20:20128/v1")
        )
        assertEquals(
            "http://192.168.1.20:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("  http://192.168.1.20:20128/v1/  ")
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("https://gateway.example.com/v1/")
        )
        val alreadyComplete = "https://gateway.example.com/v1/chat/completions"
        assertEquals(alreadyComplete, AniobProviderConfig.resolveEndpoint(alreadyComplete))
        assertEquals(
            "http://localhost:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("")
        )
        // A bare host (the address the dashboard prints) gets /v1 added; explicit paths are kept.
        assertEquals(
            "http://192.168.1.20:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("http://192.168.1.20:20128")
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("https://gateway.example.com")
        )
        assertEquals(
            "https://gateway.example.com/proxy/chat/completions",
            AniobProviderConfig.resolveEndpoint("https://gateway.example.com/proxy")
        )
    }

    @Test
    fun privateAndLocalAddressesAreAcceptedOverPlainHttp() {
        val accepted = listOf(
            "http://localhost:20128/v1",
            "http://127.0.0.1:20128/v1",
            "http://10.0.2.2:20128/v1",
            "http://192.168.1.20:20128/v1",
            "http://172.16.0.5:20128/v1",
            "http://172.31.255.254:20128",
            "http://169.254.10.10:20128/v1",
            "http://my-laptop.local:20128/v1",
            "http://gateway.lan:20128/v1",
            "http://[fe80::1]:20128/v1",
            "https://gateway.example.com/v1"
        )
        accepted.forEach { address ->
            assertNull("expected '$address' to be valid", AniobProviderConfig.validateBaseUrl(address))
        }
    }

    @Test
    fun publicPlainHttpAndMalformedAddressesAreRejected() {
        val rejected = listOf(
            "http://gateway.example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
            "ftp://192.168.1.20:20128/v1",
            "not a url",
            "https://",
            "http://192.168.1.20:20128/v1?token=abc",
            "http://192.168.1.20:20128/v1#fragment"
        )
        rejected.forEach { address ->
            assertNotNull("expected '$address' to be rejected", AniobProviderConfig.validateBaseUrl(address))
        }
        assertNotNull(AniobProviderConfig.validateBaseUrl(""))
        assertNotNull(AniobProviderConfig.validateBaseUrl("   "))
    }

    @Test
    fun localNetworkHostClassificationCoversPrivateRanges() {
        assertTrue(AniobProviderConfig.isLocalNetworkHost("localhost"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("192.168.0.7"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("10.1.2.3"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("172.16.0.1"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("172.31.255.255"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("[::1]"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("100.101.102.103"))
        assertTrue(AniobProviderConfig.isLocalNetworkHost("studio.local"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("172.15.0.1"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("100.63.0.1"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("100.128.0.1"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("172.32.0.1"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("8.8.8.8"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("gateway.example.com"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost("999.1.1.1"))
        assertFalse(AniobProviderConfig.isLocalNetworkHost(""))
    }

    @Test
    fun modelIdsAreValidatedAndUnusableValuesFallBackToAuto() {
        assertNull(AniobProviderConfig.validateModel(""))
        assertNull(AniobProviderConfig.validateModel("auto"))
        assertNull(AniobProviderConfig.validateModel("auto/coding"))
        assertNull(AniobProviderConfig.validateModel("gpt-4o"))
        assertNull(AniobProviderConfig.validateModel("kr/claude-sonnet-4.5"))
        assertNull(AniobProviderConfig.validateModel("ollama/llama3.1:8b"))
        assertNotNull(AniobProviderConfig.validateModel("bad model name"))
        assertNotNull(AniobProviderConfig.validateModel("auto?debug=1"))

        assertEquals("auto", AniobProviderConfig.sanitizeModel(""))
        assertEquals("auto", AniobProviderConfig.sanitizeModel("   "))
        assertEquals("auto/fast", AniobProviderConfig.sanitizeModel("  auto/fast "))
        assertEquals("kr/claude-sonnet-4.5", AniobProviderConfig.sanitizeModel("kr/claude-sonnet-4.5"))
        assertEquals("ollama/llama3.1:8b", AniobProviderConfig.sanitizeModel("ollama/llama3.1:8b"))
        assertEquals("auto", AniobProviderConfig.sanitizeModel("bad model name"))
    }

    @Test
    fun cloudNeedsAnAddressAndAKeyOnlyOutsideTheLocalNetwork() {
        assertTrue(
            AniobProviderConfig.isConfigured("sk-endpoint", "http://192.168.1.20:20128/v1")
        )
        // OmniRoute runs with REQUIRE_API_KEY=false by default, so a gateway on this network is a
        // complete setup without a key. A public address still needs one.
        assertTrue(AniobProviderConfig.isConfigured("", "http://192.168.1.20:20128/v1"))
        assertTrue(AniobProviderConfig.isConfigured("", "http://localhost:20128"))
        assertTrue(AniobProviderConfig.isConfigured("", "http://10.0.2.2:20128"))
        assertFalse(AniobProviderConfig.isConfigured("", "https://omniroute.online"))
        assertTrue(AniobProviderConfig.isConfigured("sk-endpoint", "https://omniroute.online"))
        assertFalse(AniobProviderConfig.isConfigured("sk-endpoint", ""))
        assertFalse(AniobProviderConfig.isConfigured("sk-endpoint", "http://gateway.example.com/v1"))
    }
}
