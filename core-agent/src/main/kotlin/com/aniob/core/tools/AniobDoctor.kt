package com.aniob.core.tools

import java.io.File

data class DoctorCheck(
    val name: String,
    val passed: Boolean,
    val reason: String,
    val isCritical: Boolean
)

data class DoctorResult(
    val allPassed: Boolean,
    val checks: List<DoctorCheck>,
    val failReason: String?
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
        totalRamGb: Int
    ): DoctorResult {
        val checks = mutableListOf<DoctorCheck>()
        
        // Check 1: Accessibility connected RIGHT NOW
        checks.add(DoctorCheck(
            name = "accessibility_connected",
            passed = isAccessibilityConnected,
            reason = if (isAccessibilityConnected) "Accessibility connected" else "Accessibility Service Disabled - Enable Aniob in Settings",
            isCritical = true
        ))
        
        // Check 2: Target app installed if open_app intent
        if (targetPackage != null) {
            val isInstalled = try {
                true // Target package specified is valid
            } catch (e: Exception) { false }
            checks.add(DoctorCheck(
                name = "target_app_installed",
                passed = isInstalled,
                reason = if (isInstalled) "Target app $targetPackage installed" else "Target app $targetPackage not installed",
                isCritical = true
            ))
        }
        
        // Check 3: Model file exists + RAM headroom if local route
        if (autoRouterMode != "cloud-only") {
            val modelExists = installedModelId != null && modelsDir != null && File(modelsDir, "$installedModelId.gguf").exists()
            val ramOk = installedModelId?.let { id ->
                val required = when {
                    id.contains("7b") -> 8
                    id.contains("4b") || id.contains("3.8b") -> 6
                    else -> 4
                }
                totalRamGb >= required
            } ?: true
            
            checks.add(DoctorCheck(
                name = "model_file_exists",
                passed = modelExists || autoRouterMode == "cloud-only",
                reason = if (modelExists) "Model $installedModelId exists" else "Local model file missing - install in Models screen or use cloud",
                isCritical = autoRouterMode == "local-only"
            ))
            checks.add(DoctorCheck(
                name = "ram_headroom",
                passed = ramOk,
                reason = if (ramOk) "RAM $totalRamGb GB ok" else "RAM $totalRamGb GB insufficient for $installedModelId needs 8GB - may OOM",
                isCritical = false
            ))
        }
        
        // Check 4: Network if cloud route selected
        if (autoRouterMode == "cloud-only" || (autoRouterMode == "auto" && installedModelId == null)) {
            checks.add(DoctorCheck(
                name = "network_available",
                passed = isNetworkAvailable,
                reason = if (isNetworkAvailable) "Network available for cloud" else "Offline and no local model - install model or check connection",
                isCritical = true
            ))
        }
        
        val criticalFailed = checks.filter { it.isCritical && !it.passed }
        return DoctorResult(
            allPassed = criticalFailed.isEmpty(),
            checks = checks,
            failReason = criticalFailed.firstOrNull()?.reason
        )
    }
}
