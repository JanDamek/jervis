package com.jervis.ui.util

actual fun platformLog(tag: String, message: String) {
    // Use println — on iOS it goes to stderr which Console.app captures
    // when running via devicectl/Xcode
    println("[$tag] $message")
}
