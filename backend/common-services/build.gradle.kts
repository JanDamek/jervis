plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    `java-library`
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

dependencies {
    // Align with Spring Boot dependency versions
    implementation(enforcedPlatform("org.jetbrains.kotlinx:kotlinx-serialization-bom:${libs.versions.serialization.get()}"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    api(project(":shared:common-api"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor Server (for shared plugins)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.cbor)
    implementation(libs.kotlin.logging)

    // Ktor Client (for shared HTTP helpers: ResponseValidation, PaginationHelper)
    implementation(libs.ktor.client.core)

    // RPC
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

    // MongoDB BSON for ObjectId
    implementation(libs.mongodb.bson)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
