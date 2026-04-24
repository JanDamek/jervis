import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPOutputStream

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
    implementation(project(":shared:service-contracts"))
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

    // Kotlin compiler embeddable
    implementation(libs.kotlin.compiler.embeddable)

    // Diff utils
    implementation(libs.java.diff.utils)

    // LangChain4j for text chunking
    implementation(libs.langchain4j)

    // JTokkit for token counting
    implementation(libs.jtokkit)

    // Firebase Admin SDK for FCM push notifications
    implementation(libs.firebase.admin)

    // APNs push notifications via HTTP/2
    implementation(libs.pushy)

    // Bucket4j for rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Kubernetes client for creating K8s Jobs (Whisper, etc.)
    implementation(libs.fabric8.kubernetes.client)


    testImplementation(libs.jupiter.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        javaParameters.set(true)
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

/**
 * Refresh Czech wordlist from hermitdave/FrequencyWords if upstream has a newer commit.
 * Runs before processResources — network failures are logged but do not fail the build
 * (existing local wordlist is kept).
 */
tasks.register("updateCzechWordlist") {
    val resourceDir = layout.projectDirectory.dir("src/main/resources")
    val wordlistFile = resourceDir.file("cs_words.txt.gz").asFile
    val versionFile = resourceDir.file("cs_words.version").asFile
    val path = "content/2018/cs/cs_full.txt"
    val apiUrl = "https://api.github.com/repos/hermitdave/FrequencyWords/commits?path=$path&per_page=1"
    val rawUrl = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/$path"

    outputs.file(wordlistFile)
    outputs.file(versionFile)
    outputs.upToDateWhen { false } // Always check upstream version

    doLast {
        try {
            val api = URI(apiUrl).toURL().openConnection() as HttpURLConnection
            api.requestMethod = "GET"
            api.connectTimeout = 10_000
            api.readTimeout = 15_000
            api.setRequestProperty("User-Agent", "jervis-build")
            val json = api.inputStream.bufferedReader().use { it.readText() }
            val shaRegex = Regex("""^\s*\[\s*\{\s*"sha"\s*:\s*"([0-9a-f]{40})"""")
            val latestSha = shaRegex.find(json)?.groupValues?.get(1)
                ?: error("Could not parse commit SHA from GitHub API response")

            val localSha = if (versionFile.exists()) versionFile.readText().trim() else ""
            if (localSha == latestSha && wordlistFile.exists()) {
                logger.lifecycle("Czech wordlist up-to-date (sha=${latestSha.take(8)})")
                return@doLast
            }

            logger.lifecycle("Czech wordlist: ${localSha.take(8).ifEmpty { "none" }} → ${latestSha.take(8)}, downloading...")
            val raw = URI(rawUrl).toURL().openConnection() as HttpURLConnection
            raw.connectTimeout = 30_000
            raw.readTimeout = 120_000
            raw.setRequestProperty("User-Agent", "jervis-build")
            val allowed = Regex("""^[a-záäčďéěíľĺňóôöőřšťúůűüýž]+$""")
            val words = sortedSetOf<String>()
            raw.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size != 2) return@forEach
                    val freq = parts[1].toIntOrNull() ?: return@forEach
                    if (freq < 2) return@forEach
                    val w = parts[0].lowercase()
                    if (w.length !in 1..30) return@forEach
                    if (!allowed.matches(w)) return@forEach
                    words.add(w)
                }
            }
            wordlistFile.parentFile.mkdirs()
            GZIPOutputStream(wordlistFile.outputStream()).use { gz ->
                BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { out ->
                    words.forEach { word ->
                        out.write(word)
                        out.newLine()
                    }
                }
            }
            versionFile.writeText(latestSha)
            logger.lifecycle("Czech wordlist: ${words.size} words written (${wordlistFile.length() / 1024} KB)")
        } catch (e: Exception) {
            logger.warn("Czech wordlist update failed (using existing local copy): ${e.message}")
            if (!wordlistFile.exists()) {
                throw GradleException("No local Czech wordlist and update failed: ${e.message}", e)
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn("updateCzechWordlist")
}
