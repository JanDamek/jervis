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

// JCEF (the underlying CefBrowser the WebView uses) reaches into the
// JDK's internal `sun.awt` / `sun.lwawt` packages to attach its native
// browser window. Java 9+ module system forbids that by default, so the
// runtime must explicitly export those packages to unnamed modules.
// Same flags are needed both for jpackage launchers (compose.desktop
// nativeDistributions) and for `gradle :apps:desktop:run*` JavaExec tasks.
val jcefAddExports = listOf(
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
)

compose.desktop {
    application {
        mainClass = "com.jervis.desktop.MainKt"

        // Pass server URL as system property
        jvmArgs += "-Djervis.server.url=$serverUrl"

        // JCEF AWT internals — see jcefAddExports above.
        jvmArgs += jcefAddExports

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
    jvmArgs = jcefAddExports
}

tasks.register<JavaExec>("runRemote") {
    group = "application"
    description = "Run desktop app with remote server (192.168.100.117:5500)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jervis.desktop.MainKt")
    systemProperty("jervis.server.url", serverUrls["remote"]!!)
    jvmArgs = jcefAddExports
}

tasks.register<JavaExec>("runPublic") {
    group = "application"
    description = "Run desktop app with public server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jervis.desktop.MainKt")
    systemProperty("jervis.server.url", serverUrls["public"]!!)
    jvmArgs = jcefAddExports
}

// ===== macOS APNs helper bundling =====
//
// Architecture: Jervis.app is the top-level jpackage Compose Desktop bundle.
// The Swift `JervisAPNs.app` helper (apps/macApp) is built via xcodegen +
// xcodebuild and embedded into `Jervis.app/Contents/Resources/JervisAPNs.app`.
// At runtime, ApnsHelperLauncher spawns it via `open -a` so its
// aps-environment entitlement triggers APNs registration; tokens stream
// to the JVM through the Unix socket bridge.
//
// Win/Linux: no helper, push notifications deferred (see
// project-win-linux-desktop-push-deferred.md).

val isMacOs = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

val apnsHelperAppPath = layout.projectDirectory.dir(
    "../macApp/build/Build/Products/Debug/JervisAPNs.app"
)

// ===== CEF runtime bundling =====
//
// JCEF binaries (~150 MB) back the in-app VNC WebView. Pre-download
// them once on the build host into apps/desktop/cef-bundle/ and copy
// into the .app so end users (single-user PoC) never wait for a
// runtime download on first launch.

val cefBundleSourceDir = layout.projectDirectory.dir("cef-bundle")
val devCefDir = File(System.getProperty("user.home"), ".jervis/kcef")

tasks.register("prepareCefBundle") {
    group = "build"
    description = "Stage the CEF runtime in apps/desktop/cef-bundle/ from ~/.jervis/kcef (populated on the first IntelliJ Desktop run). Idempotent — does nothing if cef-bundle/install.lock already exists."
    onlyIf { isMacOs && !file("${cefBundleSourceDir.asFile}/install.lock").exists() }
    doLast {
        val devLock = File(devCefDir, "install.lock")
        check(devLock.exists()) {
            "CEF runtime not found at ${devCefDir.absolutePath}. Run the IntelliJ Desktop config once and wait for KCEF to finish downloading (~3 minutes), then re-run the build."
        }
        copy {
            from(devCefDir) { exclude("cache/**") }
            into(cefBundleSourceDir)
        }
        println("CEF runtime staged at ${cefBundleSourceDir.asFile.absolutePath}")
    }
}

tasks.register<Copy>("bundleCefRuntime") {
    group = "build"
    description = "Copy staged CEF runtime into Jervis.app/Contents/Resources/cef-bundle."
    onlyIf { isMacOs }
    dependsOn("prepareCefBundle")
    from(cefBundleSourceDir) {
        exclude("cache/**")
    }
    into(
        layout.buildDirectory.dir(
            "compose/binaries/main/app/Jervis.app/Contents/Resources/cef-bundle"
        )
    )
}

tasks.register<Exec>("buildApnsHelper") {
    group = "build"
    description = "Build the JervisAPNs Swift helper (.app) for macOS via xcodegen + xcodebuild."
    onlyIf { isMacOs }
    workingDir = file("../macApp")
    commandLine = listOf(
        "/bin/bash", "-c",
        "xcodegen generate && " +
        "xcodebuild -project JervisAPNs.xcodeproj -scheme JervisAPNs " +
        "-configuration Debug -derivedDataPath build " +
        "-allowProvisioningUpdates -skipPackagePluginValidation -skipMacroValidation build"
    )
}

tasks.register<Copy>("bundleApnsHelper") {
    group = "build"
    description = "Copy JervisAPNs.app into the jpackage Jervis.app/Contents/Resources."
    onlyIf { isMacOs }
    dependsOn("buildApnsHelper")
    from(apnsHelperAppPath)
    into(
        layout.buildDirectory.dir(
            "compose/binaries/main/app/Jervis.app/Contents/Resources/JervisAPNs.app"
        )
    )
}

tasks.register<Exec>("resignWithHelper") {
    group = "build"
    description = "Re-sign Jervis.app deeply after embedding the JervisAPNs helper + CEF runtime."
    onlyIf { isMacOs }
    dependsOn("bundleApnsHelper", "bundleCefRuntime")
    val appPath = layout.buildDirectory.dir("compose/binaries/main/app/Jervis.app")
    // Pin to the Jan Damek (78BCD2R9V5) Apple Development cert — same as
    // the one xcodebuild picks for apps/macApp/JervisAPNs.app, so the
    // helper's signature is preserved through the deep re-sign.
    // Override via -Pjervis.signing.identity=<other> if you have only
    // one "Apple Development" identity in your keychain.
    val signingIdentity = findProperty("jervis.signing.identity")?.toString()
        ?: "Apple Development: Jan Damek (78BCD2R9V5)"
    commandLine = listOf(
        "/bin/bash", "-c",
        "codesign --force --deep --sign \"$signingIdentity\" --options runtime " +
        "--entitlements ${file("runtime-entitlements.plist").absolutePath} " +
        "${appPath.get().asFile.absolutePath}"
    )
}

// Hook into jpackage outputs so the helper is bundled before DMG creation.
afterEvaluate {
    tasks.findByName("createDistributable")?.finalizedBy("resignWithHelper")
    tasks.findByName("packageDmg")?.dependsOn("resignWithHelper")
}
