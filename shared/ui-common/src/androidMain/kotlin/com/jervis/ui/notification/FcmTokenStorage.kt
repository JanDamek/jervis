package com.jervis.ui.notification

import android.content.Context
import android.content.SharedPreferences
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * SharedPreferences wrapper for FCM token persistence.
 *
 * Stores:
 * - FCM token (updated on every onNewToken callback)
 * - Device ID (stable UUID generated once per install)
 * - Last registered token (tracks if the current token value has already
 *   been uploaded to the backend; no clientId here — client scope is
 *   announced separately via announceContext)
 */
object FcmTokenStorage {
    private const val PREFS_NAME = "jervis_fcm"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTERED_TOKEN = "registered_token"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveToken(context: Context, token: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            // Invalidate last-registered — new token needs re-registration
            .remove(KEY_REGISTERED_TOKEN)
            .apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    @OptIn(ExperimentalUuidApi::class)
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val deviceId = Uuid.random().toString()
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    fun markRegistered(context: Context, token: String) {
        prefs(context).edit()
            .putString(KEY_REGISTERED_TOKEN, token)
            .apply()
    }

    fun needsRegistration(context: Context): Boolean {
        val prefs = prefs(context)
        val registeredToken = prefs.getString(KEY_REGISTERED_TOKEN, null)
        val currentToken = prefs.getString(KEY_TOKEN, null)

        // Need registration if: no current token yet, never registered, or token rotated
        return currentToken != null && (registeredToken == null || registeredToken != currentToken)
    }
}
