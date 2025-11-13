package com.jervis.ui.util

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
