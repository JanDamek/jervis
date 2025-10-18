package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for Apache Tika OCR (Tesseract) integration.
 * Values can be set via application.yml under `tika.ocr` or environment variables.
 */
@ConfigurationProperties(prefix = "tika.ocr")
data class TikaOcrProperties(
    val enabled: Boolean = true,
    /**
     * Preferred OCR languages as ISO-639-2 codes compatible with Tesseract.
     * Example: ["eng", "ces", "spa", "slk", "fin", "nor", "dan", "pol", "deu", "hun"].
     */
    val languages: List<String> = listOf("eng", "ces", "spa", "slk", "fin", "nor", "dan", "pol", "deu", "hun"),
    /**
     * OCR timeout in milliseconds per page.
     */
    val timeoutMs: Long = 120_000,
)
