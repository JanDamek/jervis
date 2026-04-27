package com.jervis.ui.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSOpenPanel
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.stringWithContentsOfURL

@OptIn(ExperimentalForeignApi::class)
actual fun pickTextFileContent(title: String): String? {
    val openPanel = NSOpenPanel.openPanel()
    openPanel.title = title
    openPanel.canChooseFiles = true
    openPanel.canChooseDirectories = false
    openPanel.allowsMultipleSelection = false

    return if (openPanel.runModal() == NSModalResponseOK) {
        val url = openPanel.URL ?: return null
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        val size = data.length.toLong()
        val bytes = ByteArray(size.toInt())
        bytes.usePinned { pinned ->
            val srcPtr = data.bytes
            if (srcPtr != null) {
                platform.posix.memcpy(pinned.addressOf(0), srcPtr, size.toULong())
            }
        }
        bytes.decodeToString()
    } else {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun pickFile(title: String): PickedFile? {
    val openPanel = NSOpenPanel.openPanel()
    openPanel.title = title
    openPanel.canChooseFiles = true
    openPanel.canChooseDirectories = false

    return if (openPanel.runModal() == NSModalResponseOK) {
        val url = openPanel.URL ?: return null
        val filename = url.lastPathComponent ?: "file"
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        val size = data.length.toLong()
        val bytes = ByteArray(size.toInt())
        bytes.usePinned { pinned ->
            val srcPtr = data.bytes
            if (srcPtr != null) {
                platform.posix.memcpy(pinned.addressOf(0), srcPtr, size.toULong())
            }
        }

        PickedFile(
            filename = filename,
            mimeType = "application/octet-stream", // Fallback
            sizeBytes = size,
            contentBytes = bytes,
        )
    } else {
        null
    }
}
