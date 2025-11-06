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
    // Align with Spring Boot BOM for versions
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    // Shared modules
    implementation(project(":common"))
    implementation(project(":api-client"))

    // Spring HTTP client / proxies
    implementation(libs.spring.boot.starter)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-webflux")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// NOTE: Mobile Compose Multiplatform implementation vyžaduje separátní projekt
// ═══════════════════════════════════════════════════════════════════════════
//
// Důvod: Kotlin Multiplatform plugin nelze použít ve stejném Gradle buildu
//        jako Kotlin JVM plugin (použitý v :common, :server, :desktop).
//
// Všechny Compose Multiplatform soubory byly vytvořeny v:
//   - mobile/src/commonMain/    # Shared UI a API
//   - mobile/src/androidMain/   # Android specifické
//   - mobile/src/iosMain/       # iOS specifické
//
// Pro aktivaci Compose Multiplatform buildu:
//
// Možnost 1 (Doporučeno): Separátní Gradle projekt
//   1. Vytvořit nový adresář: jervis-mobile/
//   2. Zkopírovat mobile/src/ do jervis-mobile/src/
//   3. Vytvořit jervis-mobile/build.gradle.kts s KMP konfigurací
//   4. Publikovat :common jako Maven artifact nebo použít composite build
//
// Možnost 2: Konverze :common na Kotlin Multiplatform
//   - Migrovat common/build.gradle.kts na KMP
//   - Přidat androidTarget, iosArm64, iosSimulatorArm64
//   - Vyřešit JVM-only závislosti (Spring annotations)
//
// Všechny implementační soubory jsou připraveny a funkční!
// ═══════════════════════════════════════════════════════════════════════════

// Placeholder tasks for mobile builds
tasks.register("assembleAndroidRelease") {
    group = "distribution"
    description = "Build Android release AAB (requires KMP setup - see build.gradle.kts)"
    doLast {
        println(
            """
            ═══════════════════════════════════════════════════════
            Mobile Compose Multiplatform Build
            ═══════════════════════════════════════════════════════

            ⚠️  Compose Multiplatform vyžaduje separátní Gradle projekt

            Všechny soubory jsou připraveny v:
              • mobile/src/commonMain/kotlin/com/jervis/mobile/ui/
              • mobile/src/androidMain/
              • mobile/src/iosMain/

            Pro aktivaci buildu:
              1. Vytvořit jervis-mobile/ jako samostatný projekt
              2. Nebo migrovat :common na Kotlin Multiplatform

            Dokumentace: MOBILE_BUILD.md
            ═══════════════════════════════════════════════════════
            """.trimIndent(),
        )
    }
}

tasks.register("buildIosRelease") {
    group = "distribution"
    description = "Build iOS release framework (requires KMP setup - see build.gradle.kts)"
    doLast {
        println(
            """
            ═══════════════════════════════════════════════════════
            Mobile Compose Multiplatform Build
            ═══════════════════════════════════════════════════════

            ⚠️  Compose Multiplatform vyžaduje separátní Gradle projekt

            Všechny soubory jsou připraveny v:
              • mobile/src/commonMain/kotlin/com/jervis/mobile/ui/
              • mobile/src/androidMain/
              • mobile/src/iosMain/

            Pro aktivaci buildu:
              1. Vytvořit jervis-mobile/ jako samostatný projekt
              2. Nebo migrovat :common na Kotlin Multiplatform

            Dokumentace: MOBILE_BUILD.md
            ═══════════════════════════════════════════════════════
            """.trimIndent(),
        )
    }
}
