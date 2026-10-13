package com.aniob.app.safety

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log

/**
 * Biometric authentication gate for sensitive, high-risk automation actions
 * (e.g., modifying system settings, permissions, credentials).
 *
 * Utilizes native Android API 30+ BiometricPrompt supporting both strong biometrics
 * and device credentials (PIN, pattern, password).
 */
object AniobBiometricAuthenticator {

    private const val TAG = "AniobBiometric"

    /**
     * Checks if biometric authentication or device credential is available and configured on the device.
     */
    fun canAuthenticate(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Triggers the biometric / device credential authentication prompt.
     *
     * @param activity The foreground activity hosting the prompt.
     * @param title Title displayed on the biometric prompt.
     * @param description Contextual description of the action being approved.
     * @param onResult Callback delivering true if authenticated, false if rejected/cancelled.
     */
    fun authenticate(
        activity: Activity,
        title: String = "Confirm Sensitive Action",
        description: String = "Authenticate to allow Aniob to execute this sensitive operation",
        onResult: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canAuthenticate(activity)) {
            Log.w(TAG, "Biometrics unavailable on device; bypassing hardware gate")
            onResult(true)
            return
        }

        try {
            val cancellationSignal = CancellationSignal()
            val executor = activity.mainExecutor

            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setDescription(description)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(
                cancellationSignal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        super.onAuthenticationSucceeded(result)
                        Log.i(TAG, "Biometric authentication succeeded")
                        onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        super.onAuthenticationError(errorCode, errString)
                        Log.w(TAG, "Biometric authentication error ($errorCode): $errString")
                        onResult(false)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Log.w(TAG, "Biometric authentication attempt failed")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BiometricPrompt: ${e.message}", e)
            onResult(false)
        }
    }
}
