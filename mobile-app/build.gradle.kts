plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        }
        publishLibraryVariants("release")
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "MobileApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Shared DTOs from common-dto KMP module
            api("com.jervis:common-dto:1.0.0")

            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Kotlin
            implementation(libs.kotlinx.coroutines.core)

            // Ktor for HTTP (multiplatform compatible)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.websocket)
            implementation(libs.ktor.client.logging)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation("androidx.activity:activity-compose:1.9.3")
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}

android {
    namespace = "com.jervis.mobile"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Android release build task
tasks.register("assembleAndroidRelease") {
    group = "distribution"
    description = "Build Android release AAB for Google Play"
    dependsOn("bundleRelease")
    doLast {
        val outputDir =
            layout.buildDirectory
                .dir("outputs/bundle/release")
                .get()
                .asFile
        println("═══════════════════════════════════════")
        println("✓ Android release AAB created")
        println("Location: ${outputDir.absolutePath}")
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                if (it.extension == "aab") {
                    println("  → ${it.name}")
                }
            }
        }
        println("═══════════════════════════════════════")
    }
}

// iOS build tasks
tasks.register("buildIosRelease") {
    group = "distribution"
    description = "Build iOS release framework"
    dependsOn("linkReleaseFrameworkIosArm64")
    doLast {
        val outputDir =
            layout.buildDirectory
                .dir("bin/iosArm64/releaseFramework")
                .get()
                .asFile
        println("═══════════════════════════════════════")
        println("✓ iOS framework created")
        println("Location: ${outputDir.absolutePath}")
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                println("  → ${it.name}")
            }
        }
        println("Note: Import this framework into Xcode project")
        println("═══════════════════════════════════════")
    }
}
