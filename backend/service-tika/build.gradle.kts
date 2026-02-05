import sun.jvmstat.monitor.MonitoredVmUtil.mainClass
import java.io.File
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
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

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serialization.kotlinx.cbor)

    // RPC
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Apache Tika for document processing with OCR support
    implementation(libs.tika.core)
    implementation(libs.tika.parsers.standardpkg) {
        // Tika transitively brings jakarta.activation, ensure no javax leaks
        exclude(group = "javax.activation", module = "activation")
        exclude(group = "javax.activation", module = "javax.activation-api")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass.set("com.jervis.ocr.TikaKtorServerKt")
}

val mergedServicesDir = layout.buildDirectory.dir("merged-services")

tasks.jar {
    dependsOn(":backend:common-services:jar")
    manifest {
        attributes["Main-Class"] = "com.jervis.ocr.TikaKtorServerKt"
    }
    val runtimeClasspath = configurations.runtimeClasspath.get()
    val dependencies = runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) }
    val servicesOutputDir = mergedServicesDir.get().asFile

    doFirst {
        servicesOutputDir.deleteRecursively()
        servicesOutputDir.mkdirs()

        val services = linkedMapOf<String, MutableSet<String>>()

        runtimeClasspath.forEach { file ->
            val tree = if (file.isDirectory) fileTree(file) else zipTree(file)
            tree.matching { include("META-INF/services/**") }
                .visit(
                    object : FileVisitor {
                        override fun visitDir(dirDetails: FileVisitDetails) = Unit

                        override fun visitFile(fileDetails: FileVisitDetails) {
                            val path = fileDetails.relativePath.pathString
                            val entries =
                                fileDetails.file.readLines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                            if (entries.isNotEmpty()) {
                                val merged = services.getOrPut(path) { linkedSetOf() }
                                merged.addAll(entries)
                            }
                        }
                    },
                )
        }

        services.forEach { (path, entries) ->
            val target = File(servicesOutputDir, path)
            target.parentFile.mkdirs()
            target.writeText(entries.sorted().joinToString("\n", postfix = "\n"))
        }
    }

    from(dependencies) {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/services/**")
    }
    from(mergedServicesDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}
