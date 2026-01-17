plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    if (System.getenv("DOCKER_BUILD") != "true") {
        alias(libs.plugins.android.library)
    }
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm() // JVM (Desktop + Server)

    // Only configure Android and iOS targets when not building in Docker
    if (System.getenv("DOCKER_BUILD") != "true") {
        androidTarget() // Android
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            // API interfaces
            api(project(":shared:common-api"))

            // Kotlin coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Ktor HTTP client (multiplatform)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.serialization.kotlinx.cbor)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websocket)

            // Serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)

            // kotlinx-rpc
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

            // Koog A2A
            api(libs.koog.a2a.client)
            api(libs.koog.a2a.transport.client.jsonrpc.http)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        if (System.getenv("DOCKER_BUILD") != "true") {
            androidMain.dependencies {
                implementation(libs.ktor.client.cio)
            }

            iosMain.dependencies {
                // Darwin engine is the native iOS engine
                implementation(libs.ktor.client.darwin)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Only configure Android when not building in Docker
if (System.getenv("DOCKER_BUILD") != "true") {
    configure<com.android.build.gradle.LibraryExtension> {
        namespace = "com.jervis.domain"
        compileSdk = 35

        defaultConfig {
            minSdk = 24
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}
