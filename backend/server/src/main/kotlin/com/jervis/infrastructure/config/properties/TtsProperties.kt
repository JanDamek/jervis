package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tts")
data class TtsProperties(
    /** XTTS v2 GPU service URL on VD */
    val url: String = "http://ollama.lan.mazlusek.com:8787",
    /** Speaking speed multiplier (1.0 = normal) */
    val speed: Float = 1.0f,
    /** Path to speaker reference WAV on VD for voice cloning (null = default XTTS voice) */
    val speakerWav: String? = null,
    /** Language for XTTS v2 (cs = Czech) */
    val language: String = "cs",
    /** Client scope forwarded into the TTS request context so the router can
     *  resolve tier for the normalize LLM call. Without this the normalize
     *  request lands with tier=NONE and stays local, which means it fights
     *  background Ollama jobs on the chat GPU. Configured in the K8s
     *  configmap; empty string = no scope (tier=NONE). */
    val normalizeClientId: String = "",
    val normalizeProjectId: String = "",
    /** Max OpenRouter tier the normalize LLM call may use.
     *  "NONE" = local only.
     *  "T1"   = FREE queue (default-ish, prone to daily rate limits).
     *  "T2"   = PAID queue (bypass free-tier quotas while we iterate
     *          on the orchestrator — switch back to T1 once the
     *          free-tier budget isn't being eaten by background work).
     *  Configured via configmap, string for readability. */
    val normalizeMaxTier: String = "T2",
)
