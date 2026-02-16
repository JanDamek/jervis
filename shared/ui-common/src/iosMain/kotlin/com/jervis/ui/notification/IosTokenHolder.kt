package com.jervis.ui.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the APNs device token set from Swift when the app registers for remote notifications.
 *
 * Swift calls: IosTokenHolder.shared.setToken(hexToken: "...", deviceId: "...")
 * Kotlin reads: IosTokenHolder.apnsToken / IosTokenHolder.deviceId
 * PushTokenRegistrar collects tokenFlow to wait for async token arrival.
 */
object IosTokenHolder {
    private val _tokenFlow = MutableStateFlow<Pair<String, String>?>(null)
    val tokenFlow: StateFlow<Pair<String, String>?> = _tokenFlow.asStateFlow()

    var apnsToken: String? = null
        private set
    var deviceId: String? = null
        private set

    fun setToken(hexToken: String, deviceId: String) {
        this.apnsToken = hexToken
        this.deviceId = deviceId
        _tokenFlow.value = hexToken to deviceId
    }
}
