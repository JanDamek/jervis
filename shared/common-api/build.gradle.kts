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
        iosX64() // iOS Simulator (Intel Mac)
        iosArm64() // iOS Device
        iosSimulatorArm64() // iOS Simulator (Apple Silicon)
    }

    sourceSets {
        commonMain.dependencies {
            // Common dependencies
            api(project(":shared:common-dto"))
            api(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.datetime)
        }
    }
}

dependencies {
}

// Only configure Android when not building in Docker
if (System.getenv("DOCKER_BUILD") != "true") {
    configure<com.android.build.gradle.LibraryExtension> {
        namespace = "com.jervis.common.api"
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
