# Jervis Mono‑repo Structure Proposal

This repository adopts a single‑repository structure for all platforms. The goal is Kotlin‑first, coroutines‑first, and Spring Boot for the backend.

## Directory Layout

jervis/
├── core/        — Shared Kotlin code: DTOs, domain models, validation, business logic
├── server/      — Spring Boot backend (Kotlin, WebFlux)
├── desktop/     — Desktop app (Kotlin + JavaFX)
├── ios/         — iOS app (Swift), optional KMM bridge to `core`
└── android/     — Android app (Kotlin, AGP)

Supporting files at repository root (build scripts, version catalog) can be added later when migrating to Gradle entirely.

## Design Principles

- Kotlin everywhere feasible; Swift on iOS.
- Coroutines first; Reactor interop only at boundaries.
- Shared logic lives in `jervis/core` and is platform‑agnostic.
- No blocking calls in production code. Use structured concurrency.
- DTOs and entities are immutable; prefer kotlinx.serialization.

## Build Tools per Module

- core: Gradle (Kotlin DSL), Kotlin Multiplatform (common + JVM + Android; iOS target optional)
- server: Gradle (Kotlin DSL) with Spring Boot plugin
- desktop: Gradle (Kotlin DSL) with JavaFX plugin
- android: Gradle (Android Gradle Plugin)
- ios: Xcode/SPM; optional KMM framework from `core`

## CI Overview

GitHub Actions workflow `.github/workflows/monorepo-ci.yml` uses path filtering to run only affected module jobs. Jobs skip gracefully when a module is not yet fully implemented (no build files).

