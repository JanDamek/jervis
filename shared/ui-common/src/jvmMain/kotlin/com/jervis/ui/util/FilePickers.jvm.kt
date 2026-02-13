package com.jervis.ui.util

import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual fun pickTextFileContent(title: String): String? {
    return try {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Text/Key Files", "txt", "key", "pem", "pub")
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.readText()
        } else null
    } catch (_: Throwable) {
        null
    }
}

actual fun pickFile(title: String): PickedFile? {
    return try {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            // Accept all common file types
            addChoosableFileFilter(FileNameExtensionFilter("Textové soubory", "txt", "csv", "json", "xml", "md", "log"))
            addChoosableFileFilter(FileNameExtensionFilter("Obrázky", "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"))
            addChoosableFileFilter(FileNameExtensionFilter("PDF dokumenty", "pdf"))
            addChoosableFileFilter(FileNameExtensionFilter("Archivy", "zip", "tar", "gz", "7z"))
            isAcceptAllFileFilterUsed = true
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            val bytes = file.readBytes()
            val mimeType = Files.probeContentType(file.toPath()) ?: guessMimeType(file.name)
            PickedFile(
                filename = file.name,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                contentBytes = bytes,
            )
        } else null
    } catch (_: Throwable) {
        null
    }
}

private fun guessMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "log", "md", "csv" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "zip" -> "application/zip"
        "gz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "7z" -> "application/x-7z-compressed"
        else -> "application/octet-stream"
    }
}
