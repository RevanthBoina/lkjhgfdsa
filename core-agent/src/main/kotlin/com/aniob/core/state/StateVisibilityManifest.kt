package com.aniob.core.state

/**
 * Plan-7.2 — STATE VISIBILITY MANIFEST.
 *
 * Declares, per stage, the exact set of state fields it may READ and WRITE. Enforced only under a
 * test flag with a policing wrapper, so production pays nothing.
 *
 * The bug class this catches is the GHOST READ: code doing `getOrDefault(key, default)` where
 * `key` no longer exists, so it silently gets the default forever. No type checker and no unit
 * test finds that — the code compiles, the test asserts the default, and the feature is quietly
 * dead. A 2,410-line view model is exactly the shape where those breed.
 */
class StateVisibilityManifest(
    private val permissions: Map<Stage, Permissions>,
    /** The real field list, so a renamed field cannot leave a ghost entry behind. */
    private val actualFields: Set<String>
) {

    enum class Stage {
        PERCEPTION, PLANNING, DECIDING, GATING, EXECUTING, VERIFYING, SUMMARIZING, TERMINATING
    }

    data class Permissions(val reads: Set<String>, val writes: Set<String>)

    data class Violation(val stage: Stage, val field: String, val kind: Kind, val detail: String) {
        enum class Kind { UNDECLARED_READ, UNDECLARED_WRITE, GHOST_FIELD, GHOST_READ }
        override fun toString() = "[${kind.name}] ${stage.name}.$field: $detail"
    }

    /**
     * Validates the MANIFEST ITSELF against the real field list. Without this the manifest rots:
     * a field gets renamed, the manifest keeps the old name, and the policing wrapper happily
     * enforces a rule about a field that no longer exists.
     */
    fun validateManifest(): List<Violation> = buildList {
        permissions.forEach { (stage, p) ->
            (p.reads + p.writes).forEach { field ->
                if (field !in actualFields) {
                    add(Violation(stage, field, Violation.Kind.GHOST_FIELD,
                        "declared in the manifest but not present in the real state"))
                }
            }
        }
    }

    fun policed(stage: Stage, state: Map<String, Any?>): PolicedState =
        PolicedState(stage, state, permissions[stage] ?: Permissions(emptySet(), emptySet()), actualFields)

    /**
     * Validates that an update produced by [stage] only touched its declared write set.
     */
    fun validateUpdate(stage: Stage, updatedFields: Set<String>): List<Violation> {
        val allowed = permissions[stage]?.writes ?: emptySet()
        return updatedFields.filterNot { it in allowed }.map {
            Violation(stage, it, Violation.Kind.UNDECLARED_WRITE,
                "stage wrote a field outside its declared write set")
        }
    }

    /**
     * The policing wrapper. Only instantiated under the strict flag.
     */
    class PolicedState(
        private val stage: Stage,
        private val backing: Map<String, Any?>,
        private val permissions: Permissions,
        private val actualFields: Set<String>
    ) {
        private val _violations = mutableListOf<Violation>()
        val violations: List<Violation> get() = _violations.toList()

        fun read(field: String): Any? {
            if (field !in permissions.reads) {
                _violations += Violation(stage, field, Violation.Kind.UNDECLARED_READ,
                    "stage read a field outside its declared read set")
            }
            return backing[field]
        }

        /**
         * The ghost-read detector. A default-returning read of a field that does not exist is the
         * exact shape of the bug: it looks like a safe read and it is a permanent silent default.
         */
        fun <T> readOrDefault(field: String, default: T): T {
            if (field !in actualFields) {
                _violations += Violation(stage, field, Violation.Kind.GHOST_READ,
                    "read with a default, but no such field exists — this will silently return " +
                        "'$default' forever")
                return default
            }
            if (field !in permissions.reads) {
                _violations += Violation(stage, field, Violation.Kind.UNDECLARED_READ,
                    "stage read a field outside its declared read set")
            }
            @Suppress("UNCHECKED_CAST")
            return (backing[field] as? T) ?: default
        }
    }

    companion object {
        const val STRICT_FLAG = "ANIOB_STRICT_STATE"

        /** Zero production cost: the wrapper is only built when the flag is on. */
        fun isStrict(env: (String) -> String? = System::getenv): Boolean = env(STRICT_FLAG) == "1"
    }
}
