# Jervis macApp — native APNs helper for Compose Desktop

Two-app architecture on macOS:

- **macApp.app** (this folder) — menubar-only Swift helper
  (`LSUIElement`), holds the `aps-environment` entitlement, registers
  for APNs, and relays the token + incoming push payloads to the JVM
  over `/tmp/jervis-macapp-apns.sock`.
- **Jervis.app** (existing `apps/desktop` jpackage bundle) — Compose
  Desktop JVM. Dials the Unix socket on startup, feeds the APNs token
  into `registerTokenIfNeeded(platform="macos")`.

The two apps run independently. macApp is a background daemon (no Dock
icon); the Compose window is the normal user-facing UI started via
`./gradlew :apps:desktop:runPublic`. JVM embedding inside macApp.app
was attempted but abandoned: jpackage launchers hard-code their
`.cfg` path relative to the enclosing `.app`, so they can't be nested.

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
│   ├── ComposeLauncher.swift Stub — embedding is intentionally not implemented
│   ├── Info.plist            Placeholder; xcodegen expands the real keys
│   └── macApp.entitlements   Placeholder; xcodegen expands the real keys
├── project.yml               xcodegen spec (Info.plist/entitlements SSOT)
└── README.md
```

`Info.plist` and `macApp.entitlements` are **generated from
`project.yml`** on every `xcodegen generate` run. Local linters /
editors reset them to minimal stubs — that's fine, the full content
lives in the YAML spec. Do not rely on their on-disk contents.

No `.xcodeproj` is committed; `run-mac.sh` generates it fresh each
invocation.

## Build + launch

```bash
./run-mac.sh          # xcodegen → xcodebuild → open Jervis.app (macApp)
./run-mac.sh clean    # additionally wipes build/macapp and the xcodeproj

# Then, in a second terminal, start the Compose Desktop client:
./gradlew :apps:desktop:runPublic
```

Logs:

```bash
log stream --predicate 'process == "Jervis"' --style compact
```

## IntelliJ run configurations

The repo ships `.run/Mac App (runMac).run.xml` and `.run/Mac App -
clean (runMacClean).run.xml` — IntelliJ picks them up as shared Run
Configurations alongside `runLocal`/`runRemote`/`runPublic`. Under the
hood they call the Gradle tasks `:apps:desktop:runMac` /
`:apps:desktop:runMacClean`, which in turn call `run-mac.sh`.

## Enabling APNs (first-run signing setup)

`project.yml` intentionally does **not** include the `aps-environment`
entitlement: that key requires signing with a real Developer
certificate, which a vanilla `./run-mac.sh` with ad-hoc signing can't
provide. Once per workstation:

1. Register App ID `com.jervis.macApp` in the Apple Developer portal
   with **Push Notifications** enabled (the existing `.p8` APNs Auth
   Key from `apps/iosApp` works under the same Team ID).
2. Export your team ID: `export DEVELOPMENT_TEAM=ABCDE12345`.
3. Open `apps/macApp/macApp.xcodeproj` in Xcode → Signing &
   Capabilities → Team = yours → `+ Capability` → **Push
   Notifications** (adds `aps-environment=development` to the on-disk
   `macApp.entitlements`).
4. Re-run `./run-mac.sh`. `xcodegen generate` preserves the Push
   Notifications entitlement added through Xcode; subsequent runs use
   the signed build.

## IPC protocol

Unix socket at `/tmp/jervis-macapp-apns.sock`. One JSON message per
line, written by Swift, read by the JVM
(`MacAppSocketBridge.kt`):

```
{"kind":"token","hexToken":"...","deviceId":"IOPlatformUUID"}
{"kind":"payload","userInfo":{...}}
```

The JVM side uses `JERVIS_MACAPP_SOCKET` env (the macApp Swift host
never launches the JVM itself — it's set manually or by a
LaunchAgent). If not set, the socket path defaults to
`/tmp/jervis-macapp-apns.sock` inside
`PushTokenRegistrar.jvm.kt`.

## Not implemented (yet)

- Xcodegen `project.yml` switch for `aps-environment=production` when
  signing for distribution.
- LaunchAgent plist so macApp starts automatically at login.
- Forwarding the `payload` messages into the Compose notification UI
  (currently just logged).
- CI integration (`xcodebuild` in GitHub Actions).

## Related

- `docs/architecture.md` → "Device registration — two RPCs, two
  concerns" + "macOS background push — native wrapper".
- KB: `agent://claude-code/device-token-split-and-macos-push`.
- Memory: `project-macos-native-push-wrapper.md`.
- Commit `f03f2ee78` — device token RPC split (prereq).
