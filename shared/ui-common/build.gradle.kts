plugins {
    alias(libs.plugins.kotlin.multiplatform)
    if (System.getenv("DOCKER_BUILD") != "true") {
        alias(libs.plugins.android.library)
        alias(libs.plugins.compose.multiplatform)
        alias(libs.plugins.compose.compiler)
    }
    alias(libs.plugins.kotlin.serialization)
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm() // JVM (Desktop + Server)

    // Only configure Android, iOS and macOS targets when not building in Docker
    if (System.getenv("DOCKER_BUILD") != "true") {
        androidTarget()
        iosX64()
        iosArm64()
        iosSimulatorArm64()
        macosX64()
        macosArm64()

        val appleTargets =
            listOf(
                iosX64(),
                iosArm64(),
                iosSimulatorArm64(),
                macosX64(),
                macosArm64(),
            )

        appleTargets.forEach { target ->
            target.binaries.framework {
                baseName = "JervisShared"
                isStatic = true
                export(project(":shared:domain"))
                transitiveExport = true
            }
        }
    }

    // Opt-in for experimental APIs
    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            // Domain layer (repositories, services)
            api(project(":shared:domain"))

            // Kotlin coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Kotlinx serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        if (System.getenv("DOCKER_BUILD") != "true") {
            val appleMain by creating {
                dependsOn(commonMain.get())
            }
            val macosMain by creating {
                dependsOn(appleMain)
            }
            val iosMain by creating {
                dependsOn(appleMain)
            }
            iosX64Main.get().dependsOn(iosMain)
            iosArm64Main.get().dependsOn(iosMain)
            iosSimulatorArm64Main.get().dependsOn(iosMain)
            macosX64Main.get().dependsOn(macosMain)
            macosArm64Main.get().dependsOn(macosMain)

            // Re-adding the gets to ensure they exist for the dependsOn above
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            val macosX64Main by getting
            val macosArm64Main by getting
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Only add Compose dependencies when not building in Docker (requires Compose plugin)
if (System.getenv("DOCKER_BUILD") != "true") {
    kotlin {
        sourceSets {
            commonMain.dependencies {
                // Compose Multiplatform (only when not in Docker)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                // Lifecycle ViewModel (Compose Multiplatform)
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

                // Markdown rendering
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.38.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.38.0")

                // Compose Multiplatform WebView — used by sidebar VNC embed.
                // Backed by KCEF on Desktop (JCEF wrapper, ~10-20 MB runtime
                // download at first launch, cached afterwards), AndroidView
                // wrap of WebView on Android, UIKitView wrap of WKWebView on
                // iOS. Single dependency vs three custom expect/actual impls
                // per `feedback-no-quickfix` (max use of existing libs).
                // SSOT: docs/vnc-sidebar-discovery.md.
                // implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")
            }

            jvmMain.dependencies {
                implementation(compose.desktop.common)
                implementation(libs.ktor.client.cio)
                implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")
            }

            androidMain.dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
                implementation("io.ktor:ktor-client-okhttp:3.3.2")
                implementation(project.dependencies.platform(libs.firebase.bom))
                implementation(libs.firebase.messaging)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
                implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")
            }

            iosMain.dependencies {
                // iOS specific dependencies
                implementation(libs.ktor.client.darwin)
                implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")
            }

            macosMain.dependencies {
                // macOS specific dependencies
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

// Only configure Android when not building in Docker
if (System.getenv("DOCKER_BUILD") != "true") {
    configure<com.android.build.gradle.LibraryExtension> {
        namespace = "com.jervis.ui"
        compileSdk = 35

        defaultConfig {
            minSdk = 24
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        buildFeatures {
            compose = true
        }
    }
}
