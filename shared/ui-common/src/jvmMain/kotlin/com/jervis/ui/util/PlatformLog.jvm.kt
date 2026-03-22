package com.jervis.ui.util

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}
