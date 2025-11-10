plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    // Targets
    jvm()           // JVM (Desktop + Server)
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Opt-in for experimental APIs
    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            // Domain layer (repositories, services)
            api(project(":shared:domain"))

            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Kotlin coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Kotlinx serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Lifecycle ViewModel (Compose Multiplatform)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
        }

        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
        }

        iosMain.dependencies {
            // iOS specific dependencies
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
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
