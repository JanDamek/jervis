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
    implementation(libs.spring.boot.starter.webflux)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)

    // Jackson
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // MongoDB Reactive
    implementation(libs.spring.boot.starter.data.mongodb.reactive)

    // Qdrant and gRPC
    implementation(libs.qdrant.client) {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)

    // Native DNS resolver for macOS
    implementation(libs.netty.resolver.dns.native.macos) {
        artifact { classifier = "osx-x86_64" }
    }
    implementation(libs.netty.resolver.dns.native.macos) {
        artifact { classifier = "osx-aarch_64" }
    }

    // Guava + SLF4J API
    implementation(libs.guava)
    implementation(libs.slf4j.api)

    // Jakarta annotation API
    implementation(libs.jakarta.annotation.api)

    // Text processing and parsers
    implementation(libs.commons.text)
    implementation(libs.springdoc.openapi.webflux.ui)
    implementation(libs.javaparser.core)
    implementation(libs.jsoup)

    // Apache Tika
    implementation(libs.tika.core)
    implementation(libs.tika.parsers.standardpkg)

    // Kotlin compiler embeddable
    implementation(libs.kotlin.compiler.embeddable)

    // Diff utils
    implementation(libs.java.diff.utils)

    // FlatLaf for UI
    implementation(libs.flatlaf)

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

// Align bootRun with Maven configuration for macOS reflective access
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED")
}

// Disable Spring Boot executable jar at the root project (no main class here)
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
