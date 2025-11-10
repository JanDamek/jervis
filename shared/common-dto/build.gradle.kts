plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    jvm() // withJava() is deprecated and not needed

    // Only configure iOS targets when not building in Docker (to avoid Kotlin Native dependencies)
    if (System.getenv("DOCKER_BUILD") != "true") {
        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "CommonDto"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        groupId = "com.jervis"
        artifactId = "common-dto"
        version = "1.0.0"
    }
}
