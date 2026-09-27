package com.aniob.core.verification

/**
 * Plan-6.1 — READ-ONLY CHECKER, ZERO SIDE EFFECTS.
 *
 * The checker may read step history, notes, and an ENUMERATED table of read-only device probes.
 * It may NOT act on the device, write notes, or call sub-agents.
 *
 * **Enforced through the tool table, not the prompt.** You cannot ask a model nicely not to cheat;
 * you remove the capability. Probes outside this table do not exist. That is why this file defines
 * a closed enum and an interface with exactly one read method, rather than handing the checker the
 * executor and a strongly-worded instruction.
 */
enum class ProbeKind(val toolName: String, val description: String) {
    ALARMS("probe_alarms", "list scheduled alarms: label, hour, minute, enabled"),
    BATTERY("probe_battery", "battery level, charging state, power-save state"),
    FOREGROUND_ACTIVITY("probe_foreground_activity", "current foreground package and activity"),
    NOTIFICATIONS("probe_notifications", "active notifications: package, title, text"),
    SETTINGS_VALUE("probe_settings_value", "read one Settings key by namespace and name"),
    CONTENT_PROVIDER_QUERY("probe_content_provider", "read-only query against an allowlisted provider uri"),
    INSTALLED_PACKAGES("probe_installed_packages", "list installed launchable packages"),
    SYSTEM_PROPERTY("probe_system_property", "read one system property by name");

    companion object {
        private val BY_TOOL = values().associateBy { it.toolName }

        /** Null means the tool does not exist, which is the only honest answer to give a model. */
        fun fromToolName(name: String): ProbeKind? = BY_TOOL[name.trim().lowercase()]

        /** The tool declarations handed to the checker. Nothing else is declared. */
        fun declarations(): String = values().joinToString("\n") { "- ${it.toolName}: ${it.description}" }
    }
}

data class ProbeRequest(val kind: ProbeKind, val args: Map<String, String> = emptyMap())

data class ProbeResult(
    val kind: ProbeKind,
    val ok: Boolean,
    val output: String,
    val error: String? = null
) {
    /** Absence of a probe result is INCONCLUSIVE, never a failure (6.2). */
    val isUsableEvidence: Boolean get() = ok && output.isNotBlank()
}

/**
 * The only device capability a checker is given. Implementations live in `:app`.
 */
interface DeviceProbeTable {
    val supported: Set<ProbeKind>
    suspend fun read(request: ProbeRequest): ProbeResult
}

/**
 * Wraps a probe table so an out-of-table tool name cannot be executed even if the model emits it,
 * and so an unsupported-but-declared probe returns a usable "not available here" rather than a
 * crash the caller will interpret as a failed check.
 */
class EnumeratedProbeTable(private val delegate: DeviceProbeTable) {

    sealed interface Outcome {
        data class Ran(val result: ProbeResult) : Outcome
        /** The model asked for something that is not in the table. */
        data class NoSuchProbe(val requested: String) : Outcome {
            val message: String get() =
                "There is no tool called '$requested'. Probes outside the declared table do not " +
                    "exist. Available:\n" + ProbeKind.declarations()
        }
        /** Declared, but this device/build cannot answer it. */
        data class Unsupported(val kind: ProbeKind) : Outcome {
            val message: String get() =
                "${kind.toolName} is not available on this device, so any check relying on it is " +
                    "INCONCLUSIVE rather than failed."
        }
    }

    suspend fun run(toolName: String, args: Map<String, String> = emptyMap()): Outcome {
        val kind = ProbeKind.fromToolName(toolName) ?: return Outcome.NoSuchProbe(toolName)
        if (kind !in delegate.supported) return Outcome.Unsupported(kind)
        return Outcome.Ran(
            runCatching { delegate.read(ProbeRequest(kind, args)) }
                .getOrElse { ProbeResult(kind, ok = false, output = "", error = it.message) }
        )
    }
}
