package com.jervis.cli

import org.springframework.boot.runApplication

/**
 * Main entry point for Koog CLI Test Application.
 *
 * This is separated from KoogCliApplication to make it easier for IntelliJ
 * to find the main method.
 */
fun main(args: Array<String>) {
    // Force CLI profile if not set
    if (!args.any { it.contains("spring.profiles.active") }) {
        System.setProperty("spring.profiles.active", "cli")
    }
    runApplication<KoogCliApplication>(*args)
}
