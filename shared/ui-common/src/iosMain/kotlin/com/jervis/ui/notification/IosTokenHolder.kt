package com.jervis.ui.notification

/**
 * Holds the APNs device token set from Swift when the app registers for remote notifications.
 *
 * Swift calls: IosTokenHolder.shared.setToken(hexToken: "...", deviceId: "...")
 * Kotlin reads: IosTokenHolder.apnsToken / IosTokenHolder.deviceId
 */
object IosTokenHolder {
    var apnsToken: String? = null
        private set
    var deviceId: String? = null
        private set

    fun setToken(hexToken: String, deviceId: String) {
        this.apnsToken = hexToken
        this.deviceId = deviceId
    }
}
