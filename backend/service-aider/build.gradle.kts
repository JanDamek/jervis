import sun.jvmstat.monitor.MonitoredVmUtil.mainClass

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.gradle.application")
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

    implementation(project(":backend:common-services"))

    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    // Ktor Server for kRPC
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    // kRPC
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

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

application {
    mainClass.set("com.jervis.aider.AiderApplicationKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jervis.aider.AiderApplicationKt"
    }
    val dependencies = configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}
