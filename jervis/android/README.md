# jervis/android

Purpose: Android application written in Kotlin, consuming shared code from `jervis/core`.

Tooling:
- Build: Gradle (Android Gradle Plugin)
- Language: Kotlin (Android)
- UI: Jetpack Compose preferred; Views allowed only for legacy interop

Notes:
- Target minSdk per product requirements. Use coroutines and Flows. Avoid blocking calls.
