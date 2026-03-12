package com.jervis.wear.communication

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Listens for messages from the paired phone app.
 * Receives chat responses and recording status updates.
 */
class WearDataLayerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/jervis/chat-response" -> {
                val response = String(messageEvent.data, Charsets.UTF_8)
                // TODO: Forward to UI via broadcast or shared state
                println("[Wear] Chat response: $response")
            }
            "/jervis/recording-status" -> {
                val status = String(messageEvent.data, Charsets.UTF_8)
                println("[Wear] Recording status: $status")
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Handle data items from phone (e.g., meeting list sync)
    }
}
