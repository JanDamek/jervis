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
    implementation(project(":common"))
    implementation(project(":api-client"))

    // Spring Framework
    implementation(libs.spring.boot.starter)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webflux")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

    // JSON (Jackson) for REST client
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // UI & Swing
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.flatlaf)

    // Logging
    implementation(libs.kotlin.logging)

    testImplementation(libs.junit.jupiter)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks.test {
    useJUnitPlatform()
}
