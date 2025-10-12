# jervis/server

Purpose: Spring Boot backend written in Kotlin.

Tooling:
- Build: Gradle (Kotlin DSL) with Spring Boot plugin
- Language: Kotlin (JVM)
- Reactive: Coroutines first (Spring WebFlux with kotlinx-coroutines)

Notes:
- Expose HTTP APIs using suspend controller methods.
- Never call `.block()` in production code. Prefer Reactor interop only at boundaries.
