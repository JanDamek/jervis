import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import java.net.URL

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    id("org.openapi.generator") version "7.17.0"
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
    // Enforce kotlinx BOM to override Spring Boot BOM constraints
    implementation(enforcedPlatform("org.jetbrains.kotlinx:kotlinx-serialization-bom:${libs.versions.serialization.get()}"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":backend:common-services"))

    implementation(libs.spring.boot.starter.webflux)
    // Ensure availability of Spring 6 HTTP interfaces annotations like @HttpExchange
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.kotlin.logging)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)

    // Ktor HTTP client for calling Atlassian Cloud and for generated clients
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Annotations commonly used by generated sources
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.22")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // Jackson Kotlin module for data class deserialization
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

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

// Add generated sources to source sets
sourceSets {
    main {
        java {
            srcDir("${layout.buildDirectory.get()}/generated/openapi")
        }
    }
}

val specsDirPath = "${project.projectDir}/src/main/resources/swagger/atlassian"
val generatedBasePkg = "com.jervis.atlassian"

val atlassianSpecs =
    mapOf(
        "jira" to "jira-platform.v3.json",
        "jsm" to "jira-service-management.v3.json",
        "confluence" to "confluence.v3.json",
        "admin" to "admin-user-management.v3.json",
        "bitbucket" to "bitbucket.v2.json",
    )

fun GenerateTask.springHttpInterfaces(
    name: String,
    input: String,
    pkg: String,
) {
    generatorName.set("kotlin-spring")
    inputSpec.set(input)
    outputDir.set("${layout.buildDirectory.get()}/generated")
    apiPackage.set("$pkg.$name.api")
    modelPackage.set("$pkg.$name.model")
    invokerPackage.set("$pkg.$name.invoker")
    additionalProperties.putAll(
        mapOf(
            "interfaceOnly" to "true",
            "reactive" to "false",
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            // Use tags to split APIs across interfaces (needed for Jira/Confluence large specs)
            "useTags" to "true",
            "hideGenerationTimestamp" to "true",
            "enumPropertyNaming" to "UPPERCASE",
            "serializationLibrary" to "jackson",
            "sourceFolder" to "",
        ),
    )
    // Generate both APIs and models
    globalProperties.putAll(mapOf("apis" to "", "models" to ""))
    // Skip validation for problematic specs
    configOptions.putAll(mapOf("skipDefaultInterface" to "true"))
}

atlassianSpecs.forEach { (name, fileName) ->
    val specPath = "$specsDirPath/$fileName"
    tasks.register("generateAtlassianInterfaces_$name", GenerateTask::class.java) {
        group = "openapi"
        description = "Generate Spring HTTP interfaces for $name from vendored spec $fileName"

        inputs.file(specPath)
        outputs.dir("${layout.buildDirectory.get()}/generated/com/jervis/atlassian/$name")

        doFirst {
            val f = file(specPath)
            if (!f.exists()) {
                logger.warn("[openapi] Spec for $name not found at $specPath. Skipping generation for this product.")
                enabled = false
            }
        }
        springHttpInterfaces(name, specPath, generatedBasePkg)
        if (name == "jira" || name == "confluence") {
            additionalProperties.put("useTags", "false")
        }
    }
}

tasks.register("generateAllAtlassianInterfaces") {
    group = "openapi"
    description = "Generate Spring HTTP interfaces for all Atlassian products"
    dependsOn(tasks.matching { it.name.startsWith("generateAtlassianInterfaces_") })
}

// Post-process generated interfaces: convert Spring @*Mapping to Spring 6 @*Exchange and add @HttpExchange base path
tasks.register("postProcessAtlassianInterfaces") {
    group = "openapi"
    description = "Convert generated interfaces to Spring 6 HTTP interfaces (@HttpExchange)"
    // Intentionally no dependsOn to allow per-product generation
    doLast {
        val baseDir = file("${layout.buildDirectory.get()}/generated/openapi")
        baseDir.listFiles()?.forEach { productDir ->
            if (!productDir.isDirectory) return@forEach
            val product = productDir.name
            productDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                var t = f.readText()

                // Drop controller-level annotations that do not belong to HTTP client interfaces
                t = t.replace(Regex("""^\s*@RestController\b.*(\r?\n)?""", setOf(RegexOption.MULTILINE)), "")
                t = t.replace(Regex("""^\s*@Validated\b.*(\r?\n)?""", setOf(RegexOption.MULTILINE)), "")
                // Remove related imports if present
                t =
                    t.replace(
                        Regex(
                            """^\s*import\s+org\.springframework\.web\.bind\.annotation\.RestController\s*(\r?\n)?""",
                            setOf(RegexOption.MULTILINE),
                        ),
                        "",
                    )
                t =
                    t.replace(
                        Regex(
                            """^\s*import\s+org\.springframework\.validation\.annotation\.Validated\s*(\r?\n)?""",
                            setOf(RegexOption.MULTILINE),
                        ),
                        "",
                    )

                // Ensure HTTP service annotation imports are present (remove existing HttpExchange imports to avoid duplicates)
                t =
                    t.replace(
                        Regex(
                            """^\s*import\s+org\.springframework\.web\.service\.annotation\..*Exchange\s*(\r?\n)?""",
                            setOf(RegexOption.MULTILINE),
                        ),
                        "",
                    )
                t =
                    t.replace(
                        Regex("""(package\s+[a-zA-Z0-9_\.]+\s*\r?\n)"""),
                        "$1import org.springframework.web.service.annotation.HttpExchange\n" +
                            "import org.springframework.web.service.annotation.GetExchange\n" +
                            "import org.springframework.web.service.annotation.PostExchange\n" +
                            "import org.springframework.web.service.annotation.PutExchange\n" +
                            "import org.springframework.web.service.annotation.PatchExchange\n" +
                            "import org.springframework.web.service.annotation.DeleteExchange\n",
                    )

                // Convert multi-line @RequestMapping(...) blocks to @*Exchange annotations
                run {
                    val pattern = Regex("@RequestMapping\\s*\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
                    t =
                        pattern.replace(t) { mr ->
                            val inside = mr.groupValues[1]
                            // find HTTP method
                            val methodToken =
                                Regex("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD)").find(inside)?.groupValues?.get(
                                    1,
                                )
                            // find value/path
                            val path =
                                Regex("value\\s*=\\s*\\[?\"(.*?)\"\\]?").find(inside)?.groupValues?.get(1)
                                    ?: Regex("path\\s*=\\s*\\[?\"(.*?)\"\\]?").find(inside)?.groupValues?.get(1)
                                    ?: "/"
                            val ann =
                                when (methodToken) {
                                    "GET" -> {
                                        "@GetExchange(\"$path\")"
                                    }

                                    "POST" -> {
                                        "@PostExchange(\"$path\")"
                                    }

                                    "PUT" -> {
                                        "@PutExchange(\"$path\")"
                                    }

                                    "PATCH" -> {
                                        "@PatchExchange(\"$path\")"
                                    }

                                    "DELETE" -> {
                                        "@DeleteExchange(\"$path\")"
                                    }

                                    // No HeadExchange in our Spring version; fall back to GetExchange
                                    "HEAD" -> {
                                        "@GetExchange(\"$path\")"
                                    }

                                    else -> {
                                        // Try to infer from following function name suffix (Get/Post/Put/Patch/Delete)
                                        val tail = t.substring(mr.range.last + 1)
                                        val fn = Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\(").find(tail)?.groupValues?.get(1)
                                        val inferred =
                                            when {
                                                fn?.endsWith("Get") == true -> "GET"
                                                fn?.endsWith("Post") == true -> "POST"
                                                fn?.endsWith("Put") == true -> "PUT"
                                                fn?.endsWith("Patch") == true -> "PATCH"
                                                fn?.endsWith("Delete") == true -> "DELETE"
                                                else -> "GET"
                                            }
                                        when (inferred) {
                                            "GET" -> "@GetExchange(\"$path\")"
                                            "POST" -> "@PostExchange(\"$path\")"
                                            "PUT" -> "@PutExchange(\"$path\")"
                                            "PATCH" -> "@PatchExchange(\"$path\")"
                                            "DELETE" -> "@DeleteExchange(\"$path\")"
                                            else -> "@GetExchange(\"$path\")"
                                        }
                                    }
                                }
                            ann
                        }
                }

                // Also support single-line forms with explicit method before removing RequestMethod tokens
                t =
                    Regex(
                        "@RequestMapping\\s*\\(\\s*method\\s*=\\s*\\[?\\s*RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD)\\s*]?\\s*,\\s*value\\s*=\\s*\\[?\"(.*?)\"]?[^)]*\\)",
                    ).replace(t) { mr ->
                        val m = mr.groupValues[1]
                        val p = mr.groupValues[2]
                        when (m) {
                            "GET" -> "@GetExchange(\"$p\")"
                            "POST" -> "@PostExchange(\"$p\")"
                            "PUT" -> "@PutExchange(\"$p\")"
                            "PATCH" -> "@PatchExchange(\"$p\")"
                            "DELETE" -> "@DeleteExchange(\"$p\")"
                            else -> "@GetExchange(\"$p\")"
                        }
                    }
                t =
                    Regex(
                        "@RequestMapping\\s*\\(\\s*value\\s*=\\s*\\[?\"(.*?)\"]?\\s*,\\s*method\\s*=\\s*\\[?\\s*RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD)\\s*]?[^)]*\\)",
                    ).replace(t) { mr ->
                        val p = mr.groupValues[1]
                        val m = mr.groupValues[2]
                        when (m) {
                            "GET" -> "@GetExchange(\"$p\")"
                            "POST" -> "@PostExchange(\"$p\")"
                            "PUT" -> "@PutExchange(\"$p\")"
                            "PATCH" -> "@PatchExchange(\"$p\")"
                            "DELETE" -> "@DeleteExchange(\"$p\")"
                            else -> "@GetExchange(\"$p\")"
                        }
                    }

                // Now safe to remove remaining RequestMethod tokens (if any left in comments or strings)
                t = t.replace(Regex("""RequestMethod\.[A-Z]+"""), "")

                // Method-level replacements for @*Mapping
                t =
                    Regex(
                        "@GetMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@GetExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex(
                        "@PostMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@PostExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex(
                        "@PutMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@PutExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex(
                        "@PatchMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@PatchExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex(
                        "@DeleteMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@DeleteExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex(
                        "@HeadMapping\\s*\\(value\\s*=\\s*\\[\\\"(.*?)\\\"]\\s*\\)",
                    ).replace(t) { mr -> "@HeadExchange(\"${mr.groupValues[1]}\")" }
                // Simple forms without 'value=' and without array
                t =
                    Regex("@GetMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@GetExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex("@PostMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@PostExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex("@PutMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@PutExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex("@PatchMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@PatchExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex("@DeleteMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@DeleteExchange(\"${mr.groupValues[1]}\")" }
                t =
                    Regex("@HeadMapping\\s*\\(\\\"(.*?)\\\"\\)").replace(t) { mr -> "@GetExchange(\"${mr.groupValues[1]}\")" }

                // Remove any remaining @RequestMapping annotations and their parameter blocks
                t = t.replace(Regex("@RequestMapping\\s*\\((?:[\\s\\S]*?)\\)"), "")
                t = t.replace(Regex("""^\s*@RequestMapping.*(\r?\n)?""", setOf(RegexOption.MULTILINE)), "")

                // Ensure single @HttpExchange on interface declaration: remove existing then add one
                t = t.replace(Regex("""^\s*@HttpExchange.*(\r?\n)?""", setOf(RegexOption.MULTILINE)), "")
                t =
                    Regex("(interface\\s+[A-Za-z0-9_]+)").replace(t) { mr ->
                        "@HttpExchange(\"/rest/$product\")\n${mr.value}"
                    }

                // Make interface methods abstract: remove default bodies
                t =
                    Regex(
                        """(fun\s+[A-Za-z0-9_]+\s*\([^)]*\)\s*:[^{\n]+)\s*\{[\s\S]*?\r?\n\s*}""",
                    ).replace(t) { mr -> mr.groupValues[1] }

                f.writeText(t)
            }
        }
    }
}

// Note: Do NOT hook generation to compileKotlin. Generation runs only when tasks are called explicitly.

// Optionally download specs into resources (run manually when updating specs)
val atlassianSpecSources =
    mapOf(
        "jira" to "https://developer.atlassian.com/cloud/jira/platform/swagger.v3.json",
        "confluence" to "https://developer.atlassian.com/cloud/confluence/swagger.v3.json",
        "admin" to "https://developer.atlassian.com/cloud/admin/user-management/swagger.v3.json",
        // JSM: use stable unpkg mirror; fall back to manual vendoring if it fails
        "jsm" to "https://unpkg.com/@atlassian/jira-service-management-cloud-api-spec@latest/spec/openapi.json",
        // Others may be Swagger2 and unstable:
        "bitbucket" to "https://api.bitbucket.org/swagger.json",
        "opsgenie" to "https://api.opsgenie.com/webapi/v2/api-docs",
        "statuspage" to "https://api.statuspage.io/openapi.json",
        "trello" to "https://raw.githubusercontent.com/trello/trello-openapi/master/openapi.json",
    )

tasks.register("downloadAtlassianSpecs") {
    group = "openapi"
    description = "Download Atlassian OpenAPI specs into resources/swagger/atlassian"
    doLast {
        file(specsDirPath).mkdirs()
        atlassianSpecSources.forEach { (name, url) ->
            val targetName = atlassianSpecs[name]
            if (targetName != null) {
                val target = file("$specsDirPath/$targetName")
                try {
                    val conn = URL(url).openConnection()
                    conn.getInputStream().use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
                    println("[openapi] Downloaded $name spec from $url -> ${target.absolutePath}")
                } catch (e: Exception) {
                    println(
                        "[openapi] WARN: Failed to download $name from $url: ${'$'}{e.message}. Provide spec manually at ${'$'}{target.absolutePath}.",
                    )
                }
            }
        }
    }
}
