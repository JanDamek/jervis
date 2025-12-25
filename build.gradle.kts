// Root build file for Jervis monorepo

plugins {
    // Apply plugins with 'apply false' to make them available to subprojects without applying them to root
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.badass.runtime) apply false
}

allprojects {
    group = "com.jervis"
    version = "1.0.0"
}

// Task to build everything
tasks.register("buildAll") {
    group = "build"
    description = "Build all modules (backend + desktop + mobile)"

    dependsOn(
        ":backend:common-services:build",
        ":backend:service-tika:build",
        ":backend:service-joern:build",
        ":backend:service-whisper:build",
        ":backend:service-aider:build",
        ":backend:service-coding-engine:build",
        ":backend:service-atlassian:build",
        ":backend:koog-cli-tester:build",
        ":backend:server:build",
        ":apps:desktop:build",
        ":apps:mobile:build"
    )
}

// Task to run desktop app
tasks.register("runDesktop") {
    group = "application"
    description = "Run desktop application"

    dependsOn(":apps:desktop:run")
}

// Task to run server
tasks.register("runServer") {
    group = "application"
    description = "Run server application"

    dependsOn(":backend:server:bootRun")
}
