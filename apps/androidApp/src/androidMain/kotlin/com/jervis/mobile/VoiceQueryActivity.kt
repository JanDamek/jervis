package com.jervis.mobile

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.jervis.di.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Activity invoked by Google Assistant App Actions to forward a text query
 * to Jervis. Queries travel over kRPC (IChatService.sendSiriQuery) — the
 * public `/api/v1/chat/siri` REST route is no longer used from Kotlin apps.
 *
 * One short-lived kRPC connection per invocation: Google Assistant intents
 * are inherently one-shot and the Activity is destroyed once TTS finishes.
 */
class VoiceQueryActivity :
    Activity(),
    TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var pendingResponse: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val query =
            intent?.getStringExtra("query")
                ?: intent?.data?.getQueryParameter("query")

        if (query.isNullOrBlank()) {
            finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val response = sendQuery(query)
            withContext(Dispatchers.Main) {
                speakAndFinish(response)
            }
        }
    }

    private suspend fun sendQuery(query: String): String =
        try {
            NetworkModule.withEphemeralServices(SERVER_URL) { services ->
                services.chatService.sendSiriQuery(query, source = "google_assistant").response
            }
        } catch (e: Exception) {
            "Chyba: ${e.message ?: "neznama"}"
        }

    private fun speakAndFinish(text: String) {
        if (tts?.engines?.isNotEmpty() == true) {
            pendingResponse = text
            if (tts?.isSpeaking == false) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jervis_response")
            }
        }
        // Auto-finish after a delay even if TTS fails
        window.decorView.postDelayed({ finish() }, 10_000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("cs", "CZ")
            pendingResponse?.let { text ->
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jervis_response")
                pendingResponse = null
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val SERVER_URL = "https://jervis.damek-soft.eu"
    }
}
