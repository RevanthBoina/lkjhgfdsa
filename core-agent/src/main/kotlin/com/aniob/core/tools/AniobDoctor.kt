package com.aniob.core.tools

import java.io.File

enum class ReadinessTier {
    READY,
    READY_WITH_LIMITS,
    NEEDS_SETUP
}

data class DoctorCheck(
    val name: String,
    val passed: Boolean,
    val reason: String,
    val isCritical: Boolean
)

data class DoctorResult(
    val allPassed: Boolean,
    val checks: List<DoctorCheck>,
    val failReason: String?,
    val readinessTier: ReadinessTier = if (allPassed) ReadinessTier.READY else ReadinessTier.NEEDS_SETUP,
    val orderedRemedies: List<String> = emptyList()
)

object AniobDoctor {
    fun preflightCheck(
        taskPrompt: String,
        targetPackage: String?,
        isAccessibilityConnected: Boolean, // RIGHT NOW not was
        modelsDir: File?,
        installedModelId: String?,
        isNetworkAvailable: Boolean,
        autoRouterMode: String,
        totalRamGb: Int,
        isAppInstalled: ((String) -> Boolean)? = null,
        isOfflineShortcut: Boolean = false
    ): DoctorResult {
        val checks = mutableListOf<DoctorCheck>()
        
        // Check 1: Accessibility connected RIGHT NOW (offline shortcuts exempt)
        val a11yPassed = isOfflineShortcut || isAccessibilityConnected
        checks.add(DoctorCheck(
            name = "accessibility_connected",
            passed = a11yPassed,
            reason = when {
                isOfflineShortcut -> "Offline shortcut (accessibility exempt)"
                isAccessibilityConnected -> "Accessibility connected"
                else -> "Accessibility Service Disabled - Enable Aniob in Settings"
            },
            isCritical = !isOfflineShortcut
        ))
        
        // Check 2: Target app installed if open_app intent
        if (targetPackage != null) {
            val isInstalled = isAppInstalled?.invoke(targetPackage) ?: true
            checks.add(DoctorCheck(
                name = "target_app_installed",
                passed = isInstalled,
                reason = if (isInstalled) "Target app $targetPackage installed" else "Target app $targetPackage not installed",
                isCritical = true
            ))
        }
        
        // Check 3: Model file exists + RAM headroom if local route (offline shortcuts exempt)
        if (autoRouterMode != "cloud-only" || isOfflineShortcut) {
            val modelExists = isOfflineShortcut || (installedModelId != null && modelsDir != null && File(modelsDir, "$installedModelId.gguf").exists())
            val ramOk = isOfflineShortcut || (installedModelId?.let { id ->
                val required = when {
                    id.contains("7b") -> 8
                    id.contains("4b") || id.contains("3.8b") -> 6
                    else -> 4
                }
                totalRamGb >= required
            } ?: true)
            
            checks.add(DoctorCheck(
                name = "model_file_exists",
                passed = isOfflineShortcut || modelExists || autoRouterMode == "cloud-only",
                reason = when {
                    isOfflineShortcut -> "Offline shortcut (no local model required)"
                    modelExists -> "Model $installedModelId exists"
                    else -> "Local model file missing - install in Models screen or use cloud"
                },
                isCritical = !isOfflineShortcut && autoRouterMode == "local-only"
            ))
            checks.add(DoctorCheck(
                name = "ram_headroom",
                passed = ramOk,
                reason = when {
                    isOfflineShortcut -> "RAM headroom ok (offline shortcut)"
                    ramOk -> "RAM $totalRamGb GB ok"
                    else -> "RAM $totalRamGb GB insufficient for $installedModelId needs 8GB - may OOM"
                },
                isCritical = false
            ))
        }
        
        // Check 4: Network if cloud route selected (offline shortcuts exempt)
        if (autoRouterMode == "cloud-only" || (autoRouterMode == "auto" && installedModelId == null) || isOfflineShortcut) {
            val networkPassed = isOfflineShortcut || isNetworkAvailable
            checks.add(DoctorCheck(
                name = "network_available",
                passed = networkPassed,
                reason = when {
                    isOfflineShortcut -> "Offline shortcut (network exempt)"
                    isNetworkAvailable -> "Network available for cloud"
                    else -> "Offline and no local model - install model or check connection"
                },
                isCritical = !isOfflineShortcut
            ))
        }
        
        val criticalFailed = checks.filter { it.isCritical && !it.passed }
        val optionalFailed = checks.filter { !it.isCritical && !it.passed }
        val allPassed = criticalFailed.isEmpty()
        val tier = when {
            criticalFailed.isNotEmpty() -> ReadinessTier.NEEDS_SETUP
            optionalFailed.isNotEmpty() -> ReadinessTier.READY_WITH_LIMITS
            else -> ReadinessTier.READY
        }
        val remedies = (criticalFailed + optionalFailed).mapIndexed { idx, check ->
            "${idx + 1}. ${check.reason}"
        }
        return DoctorResult(
            allPassed = allPassed,
            checks = checks,
            failReason = criticalFailed.firstOrNull()?.reason,
            readinessTier = tier,
            orderedRemedies = remedies
        )
    }
}
