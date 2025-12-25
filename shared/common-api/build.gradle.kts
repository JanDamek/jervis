plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    if (System.getenv("DOCKER_BUILD") != "true") {
        alias(libs.plugins.android.library)
    }
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
    id("de.jensklingenberg.ktorfit") version "2.6.4"
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm()           // JVM (Desktop + Server)

    // Only configure Android and iOS targets when not building in Docker
    if (System.getenv("DOCKER_BUILD") != "true") {
        androidTarget() // Android
        iosX64()        // iOS Simulator (Intel Mac)
        iosArm64()      // iOS Device
        iosSimulatorArm64() // iOS Simulator (Apple Silicon)
    }

    sourceSets {
        commonMain.dependencies {
            // Common dependencies
            api(project(":shared:common-dto"))
            api("de.jensklingenberg.ktorfit:ktorfit-lib:2.6.4")
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
    }
}

dependencies {
    // Ktorfit code generation for each target (generates service providers properly)
    add("kspJvm", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")

    // Only add Android/iOS KSP processors when not building in Docker
    if (System.getenv("DOCKER_BUILD") != "true") {
        add("kspAndroid", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
        add("kspIosX64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
        add("kspIosArm64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
        add("kspIosSimulatorArm64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
    }
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
