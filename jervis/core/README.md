# jervis/core

Purpose: Shared Kotlin code — DTOs, domain models, validation, and business logic shared across all platforms.

Tooling:
- Build: Gradle (Kotlin DSL)
- Language: Kotlin Multiplatform (common, jvm, android; optional ios target later)
- Serialization: kotlinx.serialization

Notes:
- Keep this module framework-agnostic.
- No platform-specific I/O logic here; use expect/actual or interfaces for abstractions.
