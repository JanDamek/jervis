plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
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
    // Spring Boot (bez webflux - používáme Ktor)
    implementation(libs.spring.boot.starter)
    // implementation("org.springframework.boot:spring-boot-starter-actuator") // Odstraněno
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.datetime)

    // kotlinx-rpc (server + client)
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

    // Ktor Server for RPC
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)

    // Ktor HTTP client for integrations
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websocket)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serialization.kotlinx.cbor)

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


    testImplementation(libs.jupiter.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
