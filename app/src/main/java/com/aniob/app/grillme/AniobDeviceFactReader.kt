package com.aniob.app.grillme

import android.content.Context
import com.aniob.core.grillme.AniobCalibratedAsking
import java.util.Locale

/**
 * Plan-7.2: Device fact reader implementation for Android.
 * Allows Aniob to resolve device context without querying the user.
 */
class AniobDeviceFactReader(private val context: Context) : AniobCalibratedAsking.DeviceFactReader {
    override fun read(fact: AniobCalibratedAsking.DeviceFact, hint: String?): String? = when (fact) {
        AniobCalibratedAsking.DeviceFact.LOCALE -> Locale.getDefault().toLanguageTag()
        AniobCalibratedAsking.DeviceFact.DEFAULT_MESSAGING_APP -> {
            try {
                android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            } catch (_: Exception) {
                "SMS / Messages"
            }
        }
        AniobCalibratedAsking.DeviceFact.CONTACTS -> null
        AniobCalibratedAsking.DeviceFact.CALENDAR -> null
        AniobCalibratedAsking.DeviceFact.RECENT_APPS -> null
        AniobCalibratedAsking.DeviceFact.HOME_ADDRESS -> null
    }
}
