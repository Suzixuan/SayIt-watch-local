package com.sayit.watch.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * App-private debug storage for the receiver destination and development token.
 * Plain SharedPreferences inside the application sandbox; nothing is exported.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sayit_watch_debug", Context.MODE_PRIVATE)

    var receiverIp: String
        get() = prefs.getString(KEY_IP, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var receiverPort: String
        get() = prefs.getString(KEY_PORT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PORT, value).apply()

    var devToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun isValidDestination(): Boolean =
        DestinationValidator.validate(receiverIp, receiverPort) is DestinationValidator.ValidationResult.Valid

    fun hasToken(): Boolean = devToken.isNotBlank()

    private companion object {
        const val KEY_IP = "receiver_ip"
        const val KEY_PORT = "receiver_port"
        const val KEY_TOKEN = "dev_token"
    }
}
