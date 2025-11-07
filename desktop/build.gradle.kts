plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.badass.runtime)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}


dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation(project(":common-api"))

    // Spring Framework
    implementation(libs.spring.boot.starter)
    // Use WebFlux starter to ensure proper configuration and Kotlin coroutine support for HTTP interfaces
    implementation(libs.spring.boot.starter.webflux)
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    // Bridge between Reactor types and Kotlin coroutines (required for suspend HTTP interfaces)
    implementation(libs.kotlinx.coroutines.reactor)

    // Ktor WebSocket clients (moved from api-client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websocket)

    // JSON (Jackson) for REST client
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // UI & Swing
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.flatlaf)

    // Kotlin reflection needed by Spring to adapt suspend HTTP interfaces
    implementation(libs.kotlin.reflect)

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

// JPackage configuration for native installers with embedded JRE
application {
    mainClass.set("com.jervis.JervisApplicationKt")
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))

    modules.set(
        listOf(
            "java.base",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.prefs",
            "java.sql",
            "jdk.unsupported",
            "jdk.crypto.ec",
        ),
    )

    jpackage {
        imageName = "Jervis"
        installerName = "Jervis"

        val iconPath =
            when {
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isMacOsX -> "src/main/resources/icons/jervis.icns"
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows -> "src/main/resources/icons/jervis.ico"
                else -> "src/main/resources/icons/jervis.png"
            }

        imageOptions =
            listOfNotNull(
                "--app-version",
                project.version.toString().takeIf { it != "unspecified" } ?: "1.0.0",
                file(iconPath).takeIf { it.exists() }?.let { "--icon" },
                file(iconPath).takeIf { it.exists() }?.absolutePath,
            )

        // macOS specific
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isMacOsX
        ) {
            installerType = "dmg"
            installerOptions =
                listOf(
                    "--vendor",
                    "Jervis Team",
                    "--copyright",
                    "Copyright © 2025 Jervis Team",
                )
        }

        // Windows specific
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isWindows
        ) {
            installerType = "msi"
            installerOptions =
                listOfNotNull(
                    "--vendor",
                    "Jervis Team",
                    "--copyright",
                    "Copyright © 2025 Jervis Team",
                    "--win-dir-chooser",
                    "--win-menu",
                    "--win-shortcut",
                    file("${rootProject.projectDir}/LICENSE").takeIf { it.exists() }?.let { "--license-file" },
                    file("${rootProject.projectDir}/LICENSE").takeIf { it.exists() }?.absolutePath,
                )
        }

        // Linux specific
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux
        ) {
            installerType = "deb"
            installerOptions =
                listOfNotNull(
                    "--vendor",
                    "Jervis Team",
                    "--copyright",
                    "Copyright © 2025 Jervis Team",
                    "--linux-shortcut",
                    file("${rootProject.projectDir}/LICENSE").takeIf { it.exists() }?.let { "--license-file" },
                    file("${rootProject.projectDir}/LICENSE").takeIf { it.exists() }?.absolutePath,
                )
        }
    }
}

// Individual packaging tasks for each platform
tasks.register("packageDesktopWindows") {
    group = "distribution"
    description = "Build Windows installer with embedded JRE"
    dependsOn("jpackage")
    doLast {
        val outputDir =
            layout.buildDirectory
                .dir("jpackage")
                .get()
                .asFile
        println("═══════════════════════════════════════")
        println("✓ Windows package created")
        println("Location: ${outputDir.absolutePath}")
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                if (it.extension in listOf("msi", "exe")) {
                    println("  → ${it.name}")
                }
            }
        }
        println("═══════════════════════════════════════")
    }
}

tasks.register("packageDesktopLinux") {
    group = "distribution"
    description = "Build Linux installer with embedded JRE"
    dependsOn("jpackage")
    doLast {
        val outputDir =
            layout.buildDirectory
                .dir("jpackage")
                .get()
                .asFile
        println("═══════════════════════════════════════")
        println("✓ Linux package created")
        println("Location: ${outputDir.absolutePath}")
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                if (it.extension in listOf("deb", "rpm")) {
                    println("  → ${it.name}")
                }
            }
        }
        println("═══════════════════════════════════════")
    }
}

tasks.register("packageDesktopMacOS") {
    group = "distribution"
    description = "Build macOS installer with embedded JRE"
    dependsOn("jpackage")
    doLast {
        val outputDir = layout.buildDirectory
            .dir("jpackage")
            .get()
            .asFile
        println("═══════════════════════════════════════")
        println("✓ macOS package created")
        println("Location: ${outputDir.absolutePath}")
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                if (it.extension in listOf("dmg", "pkg")) {
                    println("  → ${it.name}")
                }
            }
        }
        println("═══════════════════════════════════════")
    }
}
