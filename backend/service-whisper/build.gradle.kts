plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":backend:common-services"))

    implementation(libs.spring.boot.starter.webflux)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.kotlin.logging)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    // Force correct kotlinx-serialization version (Spring Boot BOM has older version)
    implementation(libs.kotlinx.serialization.json) {
        version {
            strictly(libs.versions.serialization.get())
        }
    }

    testImplementation(libs.junit.jupiter)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}
