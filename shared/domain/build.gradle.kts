plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm()           // JVM (Desktop + Server)
    androidTarget() // Android
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // API interfaces (with Ktorfit)
            api(project(":shared:common-api"))

            // Kotlin coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Ktor HTTP client (multiplatform)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            // Ktorfit runtime
            implementation("de.jensklingenberg.ktorfit:ktorfit-lib:2.6.4")
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
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
