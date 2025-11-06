# Jervis - Multi-Platform Build System

> Desktop, Server a Mobile aplikace v jednom repository

## 📁 Struktura projektu

```
jervis/                          # Git repository
├── .git/
├── gradle/libs.versions.toml    # Shared dependencies
│
├── # Desktop + Server (Kotlin JVM)
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── common/                      # Shared DTO
├── server/                      # Spring Boot API
├── desktop/                     # Swing UI + JPackage
├── api-client/
└── service-*/

└── # Mobile (Kotlin Multiplatform)
    └── mobile-app/
        ├── settings.gradle.kts
        ├── build.gradle.kts
        ├── gradlew
        └── src/
            ├── commonMain/      # Compose UI (Android + iOS)
            ├── androidMain/     # Android specific
            └── iosMain/         # iOS specific
```

---

## 🚀 Quick Start

### Desktop + Server

```bash
# Start server
./gradlew :server:bootRun

# Run desktop
./gradlew :desktop:run

# Build desktop installers
./gradlew :desktop:packageDesktopMacOS     # macOS .dmg
./gradlew :desktop:packageDesktopWindows   # Windows .msi
./gradlew :desktop:packageDesktopLinux     # Linux .deb

# Build all platforms
./gradlew full-ui-build
```

### Mobile

```bash
cd mobile-app/

# Android
./gradlew assembleDebug              # Debug APK
./gradlew assembleAndroidRelease     # Release AAB

# iOS (macOS only)
./gradlew buildIosRelease            # iOS framework
```

---

## 💻 IntelliJ IDEA Setup

### Instalace

1. **IntelliJ IDEA Ultimate** (obsahuje Android + iOS podporu)
2. **Otevřít projekt:** `File` → `Open` → `jervis/`
3. **Připojit mobile:** `File` → `New` → `Module from Existing Sources` → `mobile-app/build.gradle.kts`

### Pluginy

```
File → Settings → Plugins → Marketplace
→ Install: "Kotlin Multiplatform Mobile"
→ Install: "Compose Multiplatform IDE Support"
```

### Android SDK

```
File → Project Structure → SDKs
→ "+" → Add Android SDK
→ Download Android SDK
→ Install: SDK Platform 35, Build Tools, Emulator
```

**Detailní guide:** [INTELLIJ_SETUP.md](INTELLIJ_SETUP.md)

---

## 🎯 Build Outputs

### Desktop (s embedded JRE 21)

| Platforma   | Soubor                                        | Velikost | Příkaz                                     |
|-------------|-----------------------------------------------|----------|--------------------------------------------|
| **macOS**   | `desktop/build/jpackage/Jervis-1.0.dmg`       | ~63 MB   | `./gradlew :desktop:packageDesktopMacOS`   |
| **Windows** | `desktop/build/jpackage/Jervis-1.0.msi`       | ~70 MB   | `./gradlew :desktop:packageDesktopWindows` |
| **Linux**   | `desktop/build/jpackage/jervis_1.0_amd64.deb` | ~65 MB   | `./gradlew :desktop:packageDesktopLinux`   |

### Mobile

| Platforma   | Soubor                                            | Velikost | Příkaz                                              |
|-------------|---------------------------------------------------|----------|-----------------------------------------------------|
| **Android** | `mobile-app/build/outputs/bundle/release/*.aab`   | ~8 MB    | `cd mobile-app && ./gradlew assembleAndroidRelease` |
| **iOS**     | `mobile-app/build/bin/iosArm64/releaseFramework/` | ~12 MB   | `cd mobile-app && ./gradlew buildIosRelease`        |

---

## 🛠️ Development Workflow

### Vývoj v IntelliJ

```
1. Otevřít IntelliJ s jervis/
2. Vybrat Run Configuration:
   - "Jervis Server" → Start backend
   - "Jervis Desktop" → Start desktop UI
   - "Mobile App (Android)" → Run v emulátoru
   - "iOS Framework Debug" → Build pro Xcode
3. Edit, Run, Test
```

### Hot Reload

- **Server:** Spring DevTools auto-restart
- **Desktop:** Recompile & Restart
- **Android:** Compose hot reload
- **iOS:** Framework rebuild + Xcode restart

---

## 📦 Dependencies Management

### Shared Version Catalog

`gradle/libs.versions.toml` - používán oběma projekty:

```toml
[versions]
kotlin = "2.2.0"
compose = "1.7.3"
spring-boot = "3.5.6"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
```

### DTO Sharing

**Desktop/Server:**

- `common/src/main/kotlin/com/jervis/dto/` - originální DTO (JVM)

**Mobile:**

- `mobile-app/src/commonMain/kotlin/com/jervis/dto/` - kopie (KMP)

**Sync DTO:**

```bash
cp -r common/src/main/kotlin/com/jervis/dto mobile-app/src/commonMain/kotlin/com/jervis/
```

---

## 🎨 UI Technologies

### Desktop

- **Framework:** Swing
- **Look & Feel:** FlatLaf
- **Concurrency:** Kotlin Coroutines + Swing Dispatcher

### Mobile

- **Framework:** Compose Multiplatform
- **Design:** Material 3
- **State:** StateFlow / SharedFlow
- **HTTP:** Ktor Client

---

## 📚 Documentation

| Dokument                                             | Popis                                 |
|------------------------------------------------------|---------------------------------------|
| **[INTELLIJ_SETUP.md](INTELLIJ_SETUP.md)**           | Detailní setup IntelliJ IDEA Ultimate |
| **[BUILD.md](BUILD.md)**                             | Desktop build guide (JPackage)        |
| **[MOBILE_BUILD.md](MOBILE_BUILD.md)**               | Mobile implementation details         |
| **[MULTI_PROJECT_SETUP.md](MULTI_PROJECT_SETUP.md)** | Multi-project architecture            |
| **[mobile-app/README.md](mobile-app/README.md)**     | Mobile projekt dokumentace            |

---

## 🔧 CI/CD

### GitHub Actions příklad

```yaml
name: Build All

on: [push]

jobs:
  build-desktop:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: ./gradlew :desktop:jpackage

  build-mobile:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Setup Android SDK
        uses: android-actions/setup-android@v2
      - run: cd mobile-app && ./gradlew assembleRelease
      - run: cd mobile-app && ./gradlew buildIosRelease
```

---

## 🐛 Troubleshooting

### Desktop build fails

```bash
# Check Java version
java -version  # Should be 21

# Clean build
./gradlew clean :desktop:jpackage
```

### Mobile build fails

```bash
# Set Android SDK
export ANDROID_HOME=$HOME/Library/Android/sdk

# Or create local.properties:
echo "sdk.dir=$ANDROID_HOME" > mobile-app/local.properties

# Clean
cd mobile-app && ./gradlew clean build
```

### IntelliJ doesn't see modules

```
File → Invalidate Caches / Restart
View → Tool Windows → Gradle → Refresh (🔄)
```

---

## ✅ Features

### Desktop ✅

- ✅ Native installers (Windows, Linux, macOS)
- ✅ Embedded JRE (no dependencies)
- ✅ Auto-update ready
- ✅ macOS notarization ready

### Server ✅

- ✅ Spring Boot 3.5
- ✅ Reactive (WebFlux)
- ✅ MongoDB
- ✅ Weaviate vector DB
- ✅ WebSocket notifications

### Mobile ✅

- ✅ Compose Multiplatform UI
- ✅ Material 3 design
- ✅ Android 7.0+ (API 24)
- ✅ iOS 14+
- ✅ Shared business logic

---

## 🎯 Deployment

### Desktop

**macOS:**

```bash
./gradlew :desktop:packageDesktopMacOS
# → Notarize: xcrun notarytool submit Jervis-1.0.dmg
# → Distribute: Web nebo Mac App Store
```

**Windows:**

```bash
./gradlew :desktop:packageDesktopWindows
# → Sign: signtool sign /f cert.pfx /p password Jervis-1.0.msi
# → Distribute: Web nebo Microsoft Store
```

**Linux:**

```bash
./gradlew :desktop:packageDesktopLinux
# → Distribute: Web, apt repo, flatpak, snap
```

### Mobile

**Android:**

```bash
cd mobile-app
./gradlew assembleAndroidRelease
# → Sign with keystore
# → Upload to Google Play Console
```

**iOS:**

```bash
cd mobile-app
./gradlew buildIosRelease
# → Open in Xcode
# → Archive & Upload to App Store Connect
```

---

## 🔑 Key Takeaways

### Struktura

- ✅ **Jedno Git repository** - unified history
- ✅ **Dva Gradle projekty** - plugin isolation (JVM vs KMP)
- ✅ **Shared version catalog** - consistent dependencies
- ✅ **IntelliJ friendly** - vše v jednom IDE

### Build

```bash
# Desktop & Server
./gradlew full-ui-build

# Mobile
cd mobile-app && ./gradlew assembleAndroidRelease buildIosRelease
```

### IDE

- **IntelliJ IDEA Ultimate** - jediný IDE pro všechno
- **Android SDK** - instalace přímo z IntelliJ
- **iOS** - framework build + Xcode (macOS)
- **Pluginy** - KMM + Compose Multiplatform

---

## 📞 Support

Pro více informací viz:

- `INTELLIJ_SETUP.md` - detailní IDE setup
- `MULTI_PROJECT_SETUP.md` - architektura projektu
- `BUILD.md` - build příkazy a troubleshooting

---

**Vytvořeno:** 2025-01-06
**Verze:** 1.0.0
**Platformy:** Windows, Linux, macOS, Android, iOS
