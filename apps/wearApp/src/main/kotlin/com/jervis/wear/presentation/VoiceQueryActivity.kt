package com.jervis.wear.presentation

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Lightweight Activity for handling Google Assistant voice queries on Wear OS.
 * Receives text query, sends to Jervis backend, speaks the response via TTS.
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

    private fun sendQuery(query: String): String {
        return try {
            val url = URL("https://jervis.damek-soft.eu/api/v1/chat/siri")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            val body = """{"query":"${query.replace("\"", "\\\"")}","source":"google_assistant_wear"}"""
            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val responseMatch = Regex(""""response"\s*:\s*"([^"]+)"""").find(responseText)
                responseMatch?.groupValues?.get(1) ?: responseText
            } else {
                "Jervis neodpovida."
            }
        } catch (e: Exception) {
            "Chyba pripojeni: ${e.message}"
        }
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
}
