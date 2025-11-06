plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.jervis"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Common DTO module (KMP - from composite build)
    api("com.jervis:common-dto:1.0.0")

    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    // Spring Web for HTTP Interface annotations
    implementation("org.springframework:spring-web")

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)

    // Mongo BSON for ObjectId
    implementation(libs.mongodb.bson)

    // Logging
    implementation(libs.kotlin.logging)

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

// Publish common-api module
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.jervis"
            artifactId = "common-api"
            version = "1.0.0"
        }
    }
}
