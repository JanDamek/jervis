plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.rpc)
}

group = "com.jervis"
version = "1.0.0"

// Configure Java toolchain to use Java 21 (highest LTS version with Android support)
kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // Shared UI components
    implementation(project(":shared:ui-common"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.runtime)

    // Ktor Client for WebSocket
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websocket)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serialization.kotlinx.cbor)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // kotlinx-rpc
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // SLF4J implementation (to suppress warning)
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

// Server URL configuration
val serverUrls =
    mapOf(
        "local" to "https://localhost:5500/",
        "remote" to "https://192.168.100.117:5500/",
        // Public server URL via Ingress
        "public" to "https://jervis.damek-soft.eu/",
    )

val currentProfile = findProperty("jervis.profile")?.toString() ?: "public"
val serverUrl = findProperty("jervis.server.url")?.toString() ?: serverUrls[currentProfile] ?: serverUrls["local"]

compose.desktop {
    application {
        mainClass = "com.jervis.desktop.MainKt"

        // Pass server URL as system property
        jvmArgs += "-Djervis.server.url=$serverUrl"

        // macOS: set app icon for dock, Stage Manager, Mission Control
        val dockIcon = project.file("src/main/resources/icons/jervis_icon.png")
        if (dockIcon.exists()) {
            jvmArgs += "-Xdock:icon=${dockIcon.absolutePath}"
            jvmArgs += "-Xdock:name=Jervis"
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )

            packageName = "Jervis"
            packageVersion = "1.0.0"
            description = "JERVIS AI Assistant - Desktop Application"
            copyright = "© 2025 Jervis Team"
            vendor = "Jervis Team"

            macOS {
                val iconFile = project.file("icons/jervis.icns")
                if (iconFile.exists()) {
                    this.iconFile.set(iconFile)
                }

                // Required for microphone access on macOS (TCC permission dialog)
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Jervis needs microphone access for voice assistant and meeting recording.</string>
                    """.trimIndent()
                }

                // Entitlements — required for Hardened Runtime to access microphone
                entitlementsFile.set(project.file("runtime-entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("runtime-entitlements.plist"))
            }
            windows {
                val iconFile = project.file("icons/jervis.ico")
                if (iconFile.exists()) {
                    this.iconFile.set(iconFile)
                }
            }
            linux {
                val iconFile = project.file("icons/jervis.png")
                if (iconFile.exists()) {
                    this.iconFile.set(iconFile)
                }
            }
        }
    }
}

// Convenience tasks for running with different profiles
tasks.register<JavaExec>("runLocal") {
    group = "application"
    description = "Run desktop app with local server (localhost:5500)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jervis.desktop.MainKt")
    systemProperty("jervis.server.url", serverUrls["local"]!!)
}

tasks.register<JavaExec>("runRemote") {
    group = "application"
    description = "Run desktop app with remote server (192.168.100.117:5500)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jervis.desktop.MainKt")
    systemProperty("jervis.server.url", serverUrls["remote"]!!)
}

tasks.register<JavaExec>("runPublic") {
    group = "application"
    description = "Run desktop app with public server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jervis.desktop.MainKt")
    systemProperty("jervis.server.url", serverUrls["public"]!!)
}
