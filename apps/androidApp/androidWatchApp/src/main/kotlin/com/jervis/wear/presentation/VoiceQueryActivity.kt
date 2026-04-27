package com.jervis.wear.presentation

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
 * Wear OS Activity invoked by Google Assistant voice actions. Forwards a text
 * query to Jervis over kRPC (IChatService.sendSiriQuery) and speaks the
 * response back via local TTS. One-shot only — Wear OS battery budget forbids
 * long-lived WebSocket streams, so the connection opens on intent and closes
 * as soon as the reply arrives.
 */
class VoiceQueryActivity : Activity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var pendingResponse: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val query = intent?.getStringExtra("query")
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

    private suspend fun sendQuery(query: String): String = try {
        NetworkModule.withEphemeralServices(SERVER_URL) { services ->
            services.chatService.sendSiriQuery(query, source = "google_assistant_wear").response
        }
    } catch (e: Exception) {
        "Chyba pripojeni: ${e.message ?: "neznama"}"
    }

    private fun speakAndFinish(text: String) {
        pendingResponse = text
        if (tts?.isSpeaking == false) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jervis_response")
        }
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
