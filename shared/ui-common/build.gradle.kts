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
    jvm()           // JVM (Desktop + Server)

    // Only configure Android and iOS targets when not building in Docker
    if (System.getenv("DOCKER_BUILD") != "true") {
        androidTarget()
        iosX64()
        iosArm64()
        iosSimulatorArm64()
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

            // Ktor client for WebSocket
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websocket)

            // AtomicFU for multiplatform atomic operations
            implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
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
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                // Lifecycle ViewModel (Compose Multiplatform)
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            }

            jvmMain.dependencies {
                implementation(compose.desktop.common)
                implementation(libs.ktor.client.cio)
            }

            androidMain.dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
                implementation("io.ktor:ktor-client-okhttp:3.3.2")
            }

            iosMain.dependencies {
                // iOS specific dependencies
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
