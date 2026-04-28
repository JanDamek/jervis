plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    // Shadow plugin merges SPI files (META-INF/services/*) when fattening the jar.
    // gRPC discovers LoadBalancerProvider / NameResolverProvider / ManagedChannelProvider
    // via SPI; without service merging, `DuplicatesStrategy.EXCLUDE` drops grpc-core's
    // providers and the channel build fails with "Could not find policy 'pick_first'".
    // We deliberately omit the `application` plugin — Shadow's
    // `shadowDistTar/Zip` reads the deprecated `application.mainClassName`
    // property which is removed in current Gradle.
    id("com.gradleup.shadow") version "8.3.5"
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
            because("Align with Ktor requirements")
        }
    }
}

dependencies {
    implementation(enforcedPlatform("org.jetbrains.kotlinx:kotlinx-serialization-bom:${libs.versions.serialization.get()}"))

    // Pod-to-pod gRPC contracts (RouterInferenceService + RouterAdminService)
    implementation(project(":shared:service-contracts"))

    // protobuf-java-util — JsonFormat for Struct ↔ JSON conversion in gRPC servicer.
    implementation("com.google.protobuf:protobuf-java-util:${libs.versions.protobuf.kotlin.get()}")

    // grpc-core registers PickFirstLoadBalancerProvider + DnsNameResolverProvider
    // via SPI. The shaded `grpc-netty-shaded` jar omits both providers; channel
    // builds fail at first use without grpc-core on the runtime classpath.
    implementation("io.grpc:grpc-core:1.80.0")

    // Common services bundle — kRPC client/server libs, Ktor, common-api,
    // serialization, Spring BOM. Router uses kRPC client to call jervis-server
    // (IOpenRouterSettingsService etc. — Kotlin↔Kotlin via kRPC).
    implementation(project(":backend:common-services"))

    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.datetime)

    // Ktor server — only health/liveness endpoints. Inference traffic is gRPC.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor HTTP client for Ollama backend (NDJSON streaming) and OpenRouter.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websocket)

    // kRPC client (router → jervis-server callbacks)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

    // MongoDB reactive driver — direct lookup of clients.cloudModelPolicy.maxOpenRouterTier.
    // Mirrors Python `client_tier_cache.py` (Motor driver). Cached 5min in router.
    implementation("org.mongodb:mongodb-driver-reactivestreams:5.1.4")
    implementation(libs.kotlinx.coroutines.reactor)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}


tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.jervis.router.RouterApplicationKt"
    }
    mergeServiceFiles()  // SPI merging: gRPC LoadBalancer/NameResolver/ManagedChannel providers
}

// `build` produces the shadowJar so the Docker `COPY *.jar` glob picks it up.
tasks.named("build") { dependsOn("shadowJar") }
tasks.named("jar") { enabled = false }
