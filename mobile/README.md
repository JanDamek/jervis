# Jervis Mobile (skeleton)

Purpose
- Shared mobile layer mirroring the Desktop module concept, implemented in Kotlin/JVM for now.
- Reuses `:common` types and `:api-client` for server communication.
- No UI here. Platform UIs (Android/iOS) should call this module.

Notes on iOS
- The existing `:api-client` is JVM‑only (Spring HTTP interface + Reactor interop). It cannot run on iOS/Native directly.
- This module provides the shared façade and keeps API shapes aligned. An iOS-compatible transport can be introduced later without changing call sites (e.g., via a small `MobileHttpGateway` that mirrors the API‑client behavior).

Architecture
- MobileAppFacade: entry point exposing suspend functions for the UI layer.
- MobileBootstrap: immutable configuration holder.
- MobileContext: simple immutable context model (clientId, projectId).

This is a skeleton only; no business logic is implemented.
