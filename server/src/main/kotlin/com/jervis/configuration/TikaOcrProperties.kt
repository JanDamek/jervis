package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for Apache Tika OCR (Tesseract) integration.
 * OCR is always enabled. Tesseract will automatically use all languages installed in the system.
 */
@ConfigurationProperties(prefix = "tika.ocr")
data class TikaOcrProperties(
    val timeoutMs: Long = 120_000,
)
