plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
    id("de.jensklingenberg.ktorfit") version "2.6.4"
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm()           // JVM (Desktop + Server)
    androidTarget() // Android
    iosX64()        // iOS Simulator (Intel Mac)
    iosArm64()      // iOS Device
    iosSimulatorArm64() // iOS Simulator (Apple Silicon)

    sourceSets {
        commonMain.dependencies {
            // Common dependencies
            api("com.jervis:common-dto:1.0.0")
            api("de.jensklingenberg.ktorfit:ktorfit-lib:2.6.4")
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
    }
}

dependencies {
    // Ktorfit code generation for each target (generates service providers properly)
    add("kspJvm", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
    add("kspAndroid", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
    add("kspIosX64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
    add("kspIosArm64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
    add("kspIosSimulatorArm64", "de.jensklingenberg.ktorfit:ktorfit-ksp:2.6.4")
}

android {
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
