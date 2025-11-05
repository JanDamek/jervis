# Jervis Mobile

Purpose
- Shared mobile runtime for Android/iOS UIs. Mirrors Desktop wiring but keeps UI in platform apps.
- Reuses `:common` types and `:api-client` for server communication.
- Provides online notifications while the app is running.

What’s implemented now
- MobileAppFacade: suspend APIs for clients, projects, chat sending, and user‑task counts.
- MobileNotifications: wraps WebSocket notifications and exposes Flows (Agent responses, errors, etc.).
- Simple chat state models (ChatMessage, MobileSelection).

Notes on iOS
- The existing `:api-client` is JVM‑only (Spring HTTP interface + Reactor interop) and cannot run on iOS/Native.
- You can still build an iOS UI that consumes the same facade API by adding a tiny transport shim (e.g., Ktor client on iOS) with the same interface. The facade API was kept stable for this.

Architecture
- No UI inside this module (follows JERVIS separation). Platform UIs (Android/iOS) render:
  - Bottom tabs: Chat, User‑Tasks, Settings (Settings may be placeholder now).
  - Chat screen: client selector, project selector, messages list, input box + Send.
  - User‑Tasks screen: list of active user‑tasks and count badge.

How to integrate in Android (emulator)
1. Create a small Android app module (not included here) and depend on `:mobile`.
2. Initialize the facade:
   ```kotlin
   val facade = MobileAppFacade(MobileBootstrap(
       serverBaseUrl = "http://10.0.2.2:8080", // Android emulator loopback to host
       clientId = "<your-client-id>",
       defaultProjectId = null,
   ))
   facade.startNotifications()
   ```
3. In your Activity/Compose UI:
   - Render a bottom bar with tabs [Chat, User‑Tasks, Settings].
   - On Chat tab:
     ```kotlin
     val selection by facade.selection.collectAsState()
     val chat = remember { mutableStateListOf<ChatMessage>() }
     LaunchedEffect(Unit) { facade.chat.collect { chat += it } }
     // Load clients and projects
     val clients = remember { mutableStateListOf<com.jervis.dto.ClientDto>() }
     LaunchedEffect(Unit) { clients += facade.listClients() }
     // After user picks client, load projects and call facade.select(clientId, projectId)
     // TextField + Send button -> facade.sendChat(text)
     ```
   - On User‑Tasks tab, show `facade.activeTasks` count and a list from `IUserTaskService.listActive(clientId)` if needed.
4. Run on emulator:
   - Start server: `./gradlew :server:bootRun`
   - In Android Studio, create an emulator (Pixel API 34) and run your app. Ensure the base URL uses `10.0.2.2`.

How to integrate in iOS (Simulator)
1. Create a tiny Swift/Kotlin Multiplatform host (not included here). Because `:api-client` is JVM‑only, use one of:
   - Replace HTTP proxies with a Ktor iOS client that implements the same interfaces, or
   - Bridge to the server via a thin backend (not recommended for production).
2. Initialize the same `MobileBootstrap` values and expose functions to SwiftUI.
3. Render the same tabs in SwiftUI; call facade methods via expect/actual or through a shared Kotlin layer compiled for iOS.

Online notifications
- Only while the app runs: `MobileNotifications` connects to `/ws/notifications` and streams events into flows.
- For badges: consume `activeTasks` and display a badge on the User‑Tasks tab. Offline delivery is not implemented here by design.

Limitations
- iOS needs a non‑JVM transport; this README shows how to prepare the UI while keeping APIs stable.
- This module intentionally does not include UI to keep layers clean.
