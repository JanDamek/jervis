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

val kotlinCoroutinesVersion = "1.10.2"
val kotlinSerializationVersion = "1.7.3"
val qdrantVersion = "1.14.0"
val grpcVersion = "1.68.1"
val jacksonKotlinVersion = "2.19.0"
val commonsTextVersion = "1.12.0"
val kotlinLoggingVersion = "3.0.5"
val javaparserVersion = "3.25.8"
val jsoupVersion = "1.17.2"
val tikaVersion = "2.9.1"
val javaDiffUtilsVersion = "4.12"
val guavaVersion = "33.3.1-jre"
val slf4jApiVersion = "2.0.17"
val jakartaAnnotationApiVersion = "3.0.0"
val flatlafVersion = "3.6.1"

repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone")
    maven("https://repo.spring.io/release")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinSerializationVersion")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonKotlinVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // MongoDB Reactive
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")

    // Qdrant and gRPC
    implementation("io.qdrant:client:$qdrantVersion") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")

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
    implementation("com.google.guava:guava:$guavaVersion")
    implementation("org.slf4j:slf4j-api:$slf4jApiVersion")

    // Jakarta annotation API
    implementation("jakarta.annotation:jakarta.annotation-api:$jakartaAnnotationApiVersion")

    // Text processing and parsers
    implementation("org.apache.commons:commons-text:$commonsTextVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9")
    implementation("com.github.javaparser:javaparser-core:$javaparserVersion")
    implementation("org.jsoup:jsoup:$jsoupVersion")

    // Apache Tika
    implementation("org.apache.tika:tika-core:$tikaVersion")
    implementation("org.apache.tika:tika-parsers-standard-package:$tikaVersion")

    // Kotlin compiler embeddable
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21")

    // Diff utils
    implementation("io.github.java-diff-utils:java-diff-utils:$javaDiffUtilsVersion")

    // FlatLaf for UI
    implementation("com.formdev:flatlaf:$flatlafVersion")
    
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Align bootRun with Maven configuration for macOS reflective access
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED")
}
