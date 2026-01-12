package com.jervis.ocr.configuration

/**
 * Configuration for Apache Tika OCR (Tesseract) integration.
 * OCR is always enabled. Tesseract will automatically use all languages installed in the system.
 */
data class TikaProperties(
    val timeoutMs: Long = 120_000,
) {
    companion object {
        fun fromEnv(): TikaProperties {
            val timeoutMs = System.getenv("TIKA_OCR_TIMEOUT_MS")?.toLongOrNull() ?: 120000L
            return TikaProperties(timeoutMs)
        }
    }
}
