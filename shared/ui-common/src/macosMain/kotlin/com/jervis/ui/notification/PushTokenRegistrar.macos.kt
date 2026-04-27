package com.jervis.ui.notification

import com.jervis.service.notification.IDeviceTokenService

actual object PushTokenRegistrar {
    actual suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService) {
    }

    actual suspend fun announceContext(
        clientId: String,
        deviceTokenService: IDeviceTokenService,
    ) {
    }
}
