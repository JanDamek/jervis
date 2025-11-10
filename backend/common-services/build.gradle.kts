plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Align with Spring Boot dependency versions
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    // Force correct kotlinx-serialization version (Spring Boot BOM has older version)
    implementation(libs.kotlinx.serialization.json) {
        version {
            strictly(libs.versions.serialization.get())
        }
    }

    // Spring 6 HTTP interfaces annotations (@HttpExchange)
    implementation("org.springframework:spring-web")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
