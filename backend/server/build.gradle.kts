plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

// Set default main class to server application (not CLI)
springBoot {
    mainClass.set("com.jervis.JervisApplicationKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
            useVersion(libs.versions.serialization.get())
            because("Spring Boot BOM has outdated version; align with Ktor requirements")
        }
    }
}

configurations.configureEach {
    exclude(group = "javax.activation", module = "activation")
    exclude(group = "com.sun.mail", module = "javax.mail")
    exclude(group = "com.sun.mail", module = "mail")
}

dependencies {
    implementation(enforcedPlatform("org.jetbrains.kotlinx:kotlinx-serialization-bom:${libs.versions.serialization.get()}"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":shared:common-api"))
    implementation(project(":backend:common-services"))
    implementation(libs.spring.boot.starter)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Ktor HTTP client for integrations
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Logging
    implementation(libs.kotlin.logging)

    // Jackson
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // MongoDB Reactive
    implementation(libs.spring.boot.starter.data.mongodb.reactive)

    // Weaviate client (HTTP/GraphQL)
    implementation(libs.weaviate.client)

    // ArangoDB (Graph DB)
    implementation(libs.arangodb.driver)

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

    // Jakarta Mail (API + implementation)
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    // Explicitly use matching Angus Activation to avoid javax.activation handlers
    implementation(libs.angus.activation)

    // Text processing and parsers
    implementation(libs.commons.text)
    implementation(libs.javaparser.core)
    implementation(libs.jsoup)

    // Kotlin compiler embeddable
    implementation(libs.kotlin.compiler.embeddable)

    // Diff utils
    implementation(libs.java.diff.utils)

    // LangChain4j for text chunking
    implementation(libs.langchain4j)

    // JTokkit for token counting
    implementation(libs.jtokkit)

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Koog agents
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.features.a2a.client)
    implementation(libs.koog.a2a.transport.client.jsonrpc.http)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

(tasks.test) {
    useJUnitPlatform()
}

// Joern version source of truth from version catalog (libs.versions.toml); can be overridden with -PjoernVersion
val joernVersion: String = providers.gradleProperty("joernVersion").orElse(libs.versions.joern).get()

// Helper task to print Joern version for Docker build
tasks.register("printJoernVersion") {
    doLast {
        println(joernVersion)
    }
}
