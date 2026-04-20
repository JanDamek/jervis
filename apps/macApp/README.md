# Jervis macApp — native APNs wrapper for Compose Desktop

Swift/SwiftUI host that registers for APNs, receives push payloads, and
forwards both to the Compose Desktop JVM child over a Unix socket.
Required because Compose Desktop runs as a plain JVM process and can't
hold the `aps-environment` entitlement on its own.

Windows and Linux Desktop builds keep using the bare JVM Compose
distribution with no background push — see
`project-win-linux-desktop-push-deferred.md`.

## Source layout

```
apps/macApp/
├── macApp/
│   ├── macAppApp.swift       @main — hosts AppDelegate, no visible UI
│   ├── AppDelegate.swift     NSApplicationDelegate — APNs + notification delegate
│   ├── SocketBridge.swift    Unix socket server (Swift → JVM, JSON-per-line)
│   ├── ComposeLauncher.swift Spawns the bundled Compose Desktop JVM
│   ├── Info.plist            Bundle id com.jervis.macApp, usage strings
│   └── macApp.entitlements   aps-environment + network + JVM JIT
└── README.md
```

No `.xcodeproj` is committed (binary pbxproj would constantly churn in
git). Create the project in Xcode once per clone:

1. **Xcode → File → New → Project…** → macOS → App → Next.
2. Product Name: `macApp`, Team: your Apple Developer team, Bundle
   Identifier: `com.jervis.macApp`, Interface: SwiftUI, Language: Swift.
3. Save the project at `apps/macApp/` (so `macApp.xcodeproj` sits next
   to the existing `macApp/` folder).
4. Xcode will create its own `macApp/` subfolder — **delete the
   auto-generated files inside it** and drag in the committed
   `macAppApp.swift`, `AppDelegate.swift`, `SocketBridge.swift`,
   `ComposeLauncher.swift`, `Info.plist`, `macApp.entitlements`
   via the Project Navigator (Add Files to "macApp"…, uncheck "Copy
   items if needed").
5. **Build Settings → Signing & Capabilities**:
   - Team: your team
   - Bundle Identifier: `com.jervis.macApp`
   - Code-sign with Developer ID Application (distribution) or
     Developer ID (local dev)
   - Capabilities → `+ Capability`: Push Notifications (adds
     `aps-environment`), Hardened Runtime (allow-jit +
     allow-unsigned-executable-memory +
     disable-library-validation — mirrored from
     `macApp.entitlements`).
6. **Build Settings → Info** → set the Info.plist path to the committed
   `macApp/Info.plist`.
7. **Build Settings → Code Signing Entitlements** → set to the committed
   `macApp/macApp.entitlements`.

## APNs key (reuse iOS one)

The `.p8` APNs Auth Key issued for `apps/iosApp` works for any bundle
id in the same Apple Developer team, so the backend
`ApnsPushService` configuration doesn't need to change. Just make sure
`com.jervis.macApp` is a registered App ID in your developer account
and has the "Push Notifications" capability enabled.

## Embedding Compose Desktop JVM

The wrapper expects the JRE + app jar at
`macApp.app/Contents/Resources/JervisDesktop/`. Produce that tree with:

```bash
./gradlew :apps:desktop:packageDistributionForCurrentOS
# → apps/desktop/build/compose/binaries/main/app/Jervis.app
```

Then in Xcode: **Build Phases → + → New Copy Files Phase**, Destination
`Resources`, Subpath `JervisDesktop`, and drag the *contents* of the
generated `Jervis.app` (the `Contents` directory). `ComposeLauncher`
runs `Contents/runtime/Contents/Home/bin/java -cp …/app/*
com.jervis.desktop.MainKt` against that tree at startup.

## IPC protocol

Unix socket at `/tmp/jervis-macapp-apns.sock`. One JSON message per
line, written by Swift:

```
{"kind":"token","hexToken":"...","deviceId":"IOPlatformUUID"}
{"kind":"payload","userInfo":{...}}
```

The JVM child (see
`shared/ui-common/src/jvmMain/.../PushTokenRegistrar.jvm.kt`,
macOS branch) reads the socket from `JERVIS_MACAPP_SOCKET` env, feeds
the token into the standard `registerTokenIfNeeded(platform="macos")`
flow, and dispatches payloads into the in-app notification UI.

## Build + run (ad-hoc dev)

```bash
# 1. Build the Compose Desktop runtime once
./gradlew :apps:desktop:packageDistributionForCurrentOS

# 2. In Xcode: Product → Run (⌘R)
#    — Xcode signs the bundle with your dev cert and installs it into
#      ~/Library/Developer/Xcode/DerivedData/…/Build/Products/…
#    — first launch triggers the macOS notification permission prompt
```

For distribution (`.dmg` with notarization): use `xcodebuild archive`
→ Organizer → Distribute App → Developer ID. The notarization step is
mandatory because Hardened Runtime is enabled.

## Not implemented (yet)

- Xcode project file (`.xcodeproj`) — must be created manually on first
  clone as described above. If the team decides to commit one, switch
  to `xcodegen project.yml` instead to keep diffs reviewable.
- Gradle task `:apps:macApp:build` that wraps `xcodebuild` — optional;
  would make CI/CD symmetrical with the other platforms.
- Retry / reconnect logic on the JVM child side of the socket (for
  long-running sessions where the socket gets reset).

## Related

- `docs/architecture.md` → "Device registration — two RPCs, two
  concerns" + "macOS background push — native wrapper (planned)".
- KB: `agent://claude-code/device-token-split-and-macos-push`.
- Memory: `project-macos-native-push-wrapper.md`.
- Commit `f03f2ee78` — device token RPC split (prereq).
