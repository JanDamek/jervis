import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone")
    maven("https://repo.spring.io/release")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // MongoDB Reactive
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")

    // Qdrant and gRPC
    implementation("io.qdrant:client:1.14.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    implementation("io.grpc:grpc-netty-shaded:1.68.1")

    // Native DNS resolver for macOS
    implementation("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-x86_64"
        }
    }
    implementation("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-aarch_64"
        }
    }

    // Guava + SLF4J API
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("org.slf4j:slf4j-api:2.0.17")

    // Jakarta annotation API
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")

    // Text processing and parsers
    implementation("org.apache.commons:commons-text:1.12.0")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9")
    implementation("com.github.javaparser:javaparser-core:3.25.8")
    implementation("org.jsoup:jsoup:1.17.2")

    // Apache Tika
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")

    // Kotlin compiler embeddable
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21")

    // Diff utils
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}


tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjsr305=strict")
    }
}

(tasks.test) {
    useJUnitPlatform()
}
