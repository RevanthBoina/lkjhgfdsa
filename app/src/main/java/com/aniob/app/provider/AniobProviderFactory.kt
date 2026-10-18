package com.aniob.app.provider

import com.aniob.core.external.AniobCloudLlmProvider
import java.net.URI

/**
 * Single source of truth for OmniRoute endpoints plus the validation rules the Settings screen,
 * the ViewModel and the provider all share.
 *
 * OmniRoute (https://github.com/diegosouzapw/OmniRoute) is a self-hosted, OpenAI-compatible
 * gateway. Its documented default is plain HTTP on port 20128 with the API mounted under `/v1`:
 *
 * ```
 * Base URL: http://localhost:20128/v1
 * API key:  Dashboard -> Endpoints
 * Model:    auto (or provider/model)
 * ```
 *
 * That `localhost` default only makes sense on the machine running OmniRoute. On an Android phone
 * it points at the phone itself, so the app always asks the user for the address of the gateway
 * (the computer's LAN address, a Tailscale/MagicDNS name, or an HTTPS reverse proxy). A base URL is
 * stored and the chat-completions path is appended here; [resolveEndpoint] also accepts a legacy
 * full endpoint so older persisted values keep working.
 */
object AniobProviderConfig {

    /**
     * Gateway address used when no address has been configured. Kept for the emulator / on-device
     * gateway case; the Settings screen asks for a real address because a blank value would make
     * every phone request hit itself.
     */
    const val OMNIROUTE_DEFAULT_BASE_URL = "http://localhost:20128/v1"

    const val API_ROOT = "/v1"

    const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /** Smart routing: OmniRoute picks the provider/model with the best available quota. */
    const val OMNIROUTE_MODEL = "auto"

    /** Model presets offered as chips in Settings; any `provider/model` id is also accepted. */
    val OMNIROUTE_MODEL_PRESETS: List<String> = listOf("auto", "auto/coding", "auto/fast", "auto/cheap")

    private val MODEL_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,119}$")

    private val LOOPBACK_OR_EMULATOR_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal")

    /**
     * Normalises a stored or user-supplied base URL: trims whitespace and trailing slashes only.
     * The chat-completions path is appended by [resolveEndpoint], never stored.
     */
    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * Returns `null` when [baseUrl] is a usable gateway address, otherwise a short message that can
     * be shown under the Settings field.
     *
     * Plain `http://` is accepted only for loopback, emulator and private-network hosts. OmniRoute
     * ships with a plain-HTTP listener, so a LAN address must be allowed; sending the API key over
     * plain HTTP to a public host is never allowed.
     */
    fun validateBaseUrl(baseUrl: String): String? {
        val value = normalizeBaseUrl(baseUrl)
        if (value.isEmpty()) {
            return "Add the address shown in your OmniRoute dashboard."
        }
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            return "That address is not a valid web address."
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return "Start the address with http:// or https://."
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return "That address is missing a host name."
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return "That port number is out of range."
        }
        if (!uri.query.isNullOrBlank()) {
            return "Remove the part after ? and paste only the address."
        }
        if (!uri.fragment.isNullOrBlank()) {
            return "Remove the part after # and paste only the address."
        }
        if (scheme == "http" && !isLocalNetworkHost(host)) {
            return "Use https:// for this address. Plain http is only allowed on your local network."
        }
        return null
    }

    /**
     * True for loopback, emulator, RFC1918 private, RFC 6598 (Tailscale/CGNAT), link-local,
     * IPv6 local and mDNS hosts.
     */
    fun isLocalNetworkHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (h.isEmpty()) return false
        if (h in LOOPBACK_OR_EMULATOR_HOSTS) return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true

        // IPv6 literal: loopback, unique-local (fc00::/7) and link-local (fe80::/10).
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }

        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||          // 10.0.0.0/8
            first == 127 ||            // loopback
            (first == 172 && second in 16..31) || // 172.16.0.0/12
            (first == 192 && second == 168) ||    // 192.168.0.0/16
            (first == 169 && second == 254) ||    // link-local
            (first == 100 && second in 64..127)   // RFC 6598 / Tailscale
    }

    /** Returns `null` when [model] is blank (meaning `auto`) or a plausible OmniRoute model id. */
    fun validateModel(model: String): String? {
        val trimmed = model.trim()
        if (trimmed.isEmpty() || MODEL_PATTERN.matches(trimmed)) return null
        return "Use letters, numbers, dots, dashes, slashes, colons and underscores. For example auto or auto/coding."
    }

    /** Blank or unusable model ids fall back to [OMNIROUTE_MODEL] instead of breaking a request. */
    fun sanitizeModel(model: String): String {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return OMNIROUTE_MODEL
        return if (validateModel(trimmed) == null) trimmed else OMNIROUTE_MODEL
    }

    /** True when both a key and a valid gateway address are present. */
    fun isConfigured(apiKey: String, baseUrl: String): Boolean =
        apiKey.isNotBlank() && validateBaseUrl(baseUrl) == null

    /**
     * Normalises a stored or user-supplied base URL into a full chat-completions endpoint.
     *
     * Blank falls back to [OMNIROUTE_DEFAULT_BASE_URL] and a value that already ends in the path is
     * returned unchanged (after trailing-slash trimming), so the function is idempotent. A bare
     * host such as `http://192.168.1.20:20128` (the address the dashboard prints) gets [API_ROOT]
     * added; an explicit path — including a tokenized `/vscode/<key>/` alias or a reverse-proxy
     * prefix — is left alone.
     */
    fun resolveEndpoint(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl).ifBlank { OMNIROUTE_DEFAULT_BASE_URL }
        if (base.endsWith(CHAT_COMPLETIONS_PATH)) return base
        val root = if (hasExplicitPath(base)) base else base + API_ROOT
        return root + CHAT_COMPLETIONS_PATH
    }

    private fun hasExplicitPath(value: String): Boolean {
        val path = try {
            URI(value).path.orEmpty()
        } catch (e: Exception) {
            // Unparseable input is rejected by [validateBaseUrl]; do not guess here.
            return true
        }
        return path.isNotBlank() && path != "/"
    }

    /**
     * Forces any unusable persisted model back to [OMNIROUTE_MODEL]. Kept as the historical name;
     * [sanitizeModel] is the same operation.
     */
    fun coerceModel(model: String): String = sanitizeModel(model)
}

object AniobProviderFactory {
    fun createOmniRoute(
        apiKey: String,
        baseUrl: String = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
        model: String = AniobProviderConfig.OMNIROUTE_MODEL,
        jsonMode: Boolean = false,
        /** Per-request callback used by the research telemetry; see [AniobOmniRouteProvider]. */
        onLlmRequest: () -> Unit = {}
    ): AniobCloudLlmProvider {
        return AniobOmniRouteProvider(
            apiKey = apiKey,
            baseUrl = AniobProviderConfig.resolveEndpoint(baseUrl),
            model = AniobProviderConfig.sanitizeModel(model),
            jsonMode = jsonMode,
            onLlmRequest = onLlmRequest
        )
    }
}
