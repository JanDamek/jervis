package com.jervis.ui.notification

import com.jervis.service.IDeviceTokenService

actual object PushTokenRegistrar {
    actual suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService) {
        // No-op on desktop — uses kRPC event streams for notifications
    }
}
