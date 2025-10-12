# Dependency Management Plan

This plan aligns with a Kotlin‑first mono‑repo while allowing heterogeneous tools where necessary.

## Strategy

1. Prefer Gradle (Kotlin DSL) as the primary build tool for Kotlin modules: core, server, desktop, android.
2. Keep iOS using native tooling (Xcode/SPM). Integrate `core` via Kotlin Multiplatform when beneficial.
3. Centralize dependency versions using Gradle Version Catalogs (`gradle/libs.versions.toml`).
4. Use convention plugins (in `build-logic`) to share common Gradle configuration across modules.
5. Enforce consistent Kotlin, Coroutines, and kotlinx.serialization versions across JVM/Android modules.
6. For Spring Boot, manage dependencies via the Spring dependency BOM and align with the catalog.
7. Avoid direct `implementation` of specific versions inside modules—reference the catalog aliases.

## Version Catalog (example)

A future `gradle/libs.versions.toml` can define:

[versions]
kotlin = "<pin>"
coroutines = "<pin>"
serialization = "<pin>"
springBoot = "<pin>"
javafx = "<pin>"
androidGradlePlugin = "<pin>"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
android-app = { id = "com.android.application", version.ref = "androidGradlePlugin" }

## Third‑party Dependencies Guidelines

- Domain/DTOs: kotlinx.serialization, kotlinx-datetime.
- Networking: Ktor Client for shared code; Spring WebFlux on server.
- Logging: Kotlin Logging or SLF4J with structured logging.
- Testing: kotlinx.coroutines.test, Kotest/JUnit 5; StepVerifier only for Reactor interop tests.

## Repository‑wide Policies

- Immutable DTOs/entities with `val`; prefer non‑nullable fields with sensible defaults.
- No `!!` in production code.
- No `.block()` in reactive flows.
- Use `suspend` and `Flow` as first choice for async.

## Migration Notes

- The repository currently contains Maven configuration. Migration to Gradle can be incremental:
  - Introduce Gradle wrapper at the root and add new modules using Gradle.
  - Keep Maven for legacy modules until migrated.
  - CI workflow detects build files per module and runs the appropriate tool, allowing coexistence.
