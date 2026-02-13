package com.jervis.ui.util

/**
 * Cross-platform file picker utilities for UI needs.
 * Returns file content as text or null if not supported/cancelled.
 */
expect fun pickTextFileContent(title: String = "Select File"): String?

/**
 * Picked file data from the file picker.
 */
data class PickedFile(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedFile) return false
        return filename == other.filename && sizeBytes == other.sizeBytes
    }

    override fun hashCode(): Int = filename.hashCode() * 31 + sizeBytes.hashCode()
}

/**
 * Cross-platform file picker that returns file bytes.
 * Supports: text, images, PDFs, ZIP archives, and other common file types.
 * Returns null if picker is not supported or user cancelled.
 */
expect fun pickFile(title: String = "Vybrat soubor"): PickedFile?
