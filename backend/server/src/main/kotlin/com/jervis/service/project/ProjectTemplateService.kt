package com.jervis.service.project

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Provides project scaffolding templates for common technology stacks.
 *
 * Returns file trees and content that can be committed to a new repository
 * by the coding agent or orchestrator. Supports:
 * - KMP (Kotlin Multiplatform) with Compose
 * - Spring Boot (Kotlin)
 * - KMP + Spring Boot (full-stack)
 *
 * Templates are minimal but production-ready: proper Gradle setup,
 * .gitignore, CI config, and basic project structure.
 */
@Service
class ProjectTemplateService {

    /**
     * Get available template types with descriptions.
     */
    fun listTemplates(): List<ProjectTemplate> = TEMPLATES

    /**
     * Generate file tree for a given template type.
     *
     * @param templateType Template identifier (kmp, spring-boot, kmp-spring)
     * @param projectName  Project/module name (used in package names, settings)
     * @param packageName  Base package (e.g. com.example.myapp)
     * @return List of [TemplateFile] with relative paths and content
     */
    fun generateTemplate(
        templateType: String,
        projectName: String,
        packageName: String = "com.example.$projectName",
    ): List<TemplateFile> {
        return when (templateType.lowercase()) {
            "kmp" -> generateKmpTemplate(projectName, packageName)
            "spring-boot", "spring", "springboot" -> generateSpringBootTemplate(projectName, packageName)
            "kmp-spring", "fullstack", "full-stack" -> generateKmpSpringTemplate(projectName, packageName)
            else -> throw IllegalArgumentException(
                "Unknown template type: $templateType. Available: kmp, spring-boot, kmp-spring",
            )
        }
    }

    private fun generateKmpTemplate(projectName: String, packageName: String): List<TemplateFile> {
        val packagePath = packageName.replace('.', '/')
        return listOf(
            TemplateFile("settings.gradle.kts", """
                rootProject.name = "$projectName"

                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
            """.trimIndent()),
            TemplateFile("build.gradle.kts", """
                plugins {
                    alias(libs.plugins.kotlinMultiplatform)
                    alias(libs.plugins.composeMultiplatform)
                    alias(libs.plugins.composeCompiler)
                }

                kotlin {
                    jvm("desktop")

                    sourceSets {
                        val commonMain by getting {
                            dependencies {
                                implementation(compose.runtime)
                                implementation(compose.foundation)
                                implementation(compose.material3)
                            }
                        }
                        val desktopMain by getting
                    }
                }
            """.trimIndent()),
            TemplateFile("src/commonMain/kotlin/$packagePath/App.kt", """
                package $packageName

                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun App() {
                    Text("Hello from $projectName!")
                }
            """.trimIndent()),
            TemplateFile(".gitignore", GITIGNORE_KOTLIN),
        )
    }

    private fun generateSpringBootTemplate(projectName: String, packageName: String): List<TemplateFile> {
        val packagePath = packageName.replace('.', '/')
        return listOf(
            TemplateFile("settings.gradle.kts", """
                rootProject.name = "$projectName"
            """.trimIndent()),
            TemplateFile("build.gradle.kts", """
                plugins {
                    kotlin("jvm") version "2.3.0"
                    kotlin("plugin.spring") version "2.3.0"
                    id("org.springframework.boot") version "3.4.0"
                    id("io.spring.dependency-management") version "1.1.6"
                }

                group = "$packageName"
                version = "0.0.1-SNAPSHOT"

                java {
                    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
                    implementation("org.jetbrains.kotlin:kotlin-reflect")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                }
            """.trimIndent()),
            TemplateFile("src/main/kotlin/$packagePath/Application.kt", """
                package $packageName

                import org.springframework.boot.autoconfigure.SpringBootApplication
                import org.springframework.boot.runApplication

                @SpringBootApplication
                class Application

                fun main(args: Array<String>) {
                    runApplication<Application>(*args)
                }
            """.trimIndent()),
            TemplateFile("src/main/resources/application.yml", """
                spring:
                  application:
                    name: $projectName

                server:
                  port: 8080
            """.trimIndent()),
            TemplateFile(".gitignore", GITIGNORE_KOTLIN),
        )
    }

    private fun generateKmpSpringTemplate(projectName: String, packageName: String): List<TemplateFile> {
        val backendFiles = generateSpringBootTemplate("$projectName-server", "$packageName.server")
            .map { TemplateFile("backend/${it.path}", it.content) }
        val frontendFiles = generateKmpTemplate("$projectName-ui", "$packageName.ui")
            .map { TemplateFile("frontend/${it.path}", it.content) }

        val rootSettings = TemplateFile("settings.gradle.kts", """
            rootProject.name = "$projectName"

            include(":backend")
            include(":frontend")
        """.trimIndent())

        return listOf(rootSettings, TemplateFile(".gitignore", GITIGNORE_KOTLIN)) +
            backendFiles + frontendFiles
    }

    companion object {
        private val TEMPLATES = listOf(
            ProjectTemplate(
                type = "kmp",
                name = "Kotlin Multiplatform + Compose",
                description = "KMP project with Compose Multiplatform UI (Desktop/Android/iOS)",
            ),
            ProjectTemplate(
                type = "spring-boot",
                name = "Spring Boot (Kotlin)",
                description = "Spring Boot 3.x backend with Kotlin, Web starter, and YAML config",
            ),
            ProjectTemplate(
                type = "kmp-spring",
                name = "KMP + Spring Boot (Full-stack)",
                description = "Multi-module project: KMP Compose frontend + Spring Boot backend",
            ),
        )

        private val GITIGNORE_KOTLIN = """
            .gradle/
            build/
            !gradle/wrapper/gradle-wrapper.jar
            .idea/
            *.iml
            out/
            .kotlin/
            local.properties
        """.trimIndent()
    }
}

@Serializable
data class ProjectTemplate(
    val type: String,
    val name: String,
    val description: String,
)

@Serializable
data class TemplateFile(
    val path: String,
    val content: String,
)
