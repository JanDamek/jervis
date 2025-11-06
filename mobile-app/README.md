# Jervis Mobile - Compose Multiplatform

Mobilní aplikace pro Android a iOS s Material 3 UI.

## 🚀 Quick Start

### Prerequisites

**Android:**

- Android SDK (API 24+)
- Set `ANDROID_HOME` environment variable
- Or create `local.properties`:
  ```properties
  sdk.dir=/path/to/Android/sdk
  ```

**iOS:**

- macOS with Xcode 14+
- CocoaPods (optional)

### Build Commands

```bash
# Android Debug APK
./gradlew assembleDebug

# Android Release AAB (pro Google Play)
./gradlew assembleAndroidRelease

# iOS Framework (pro Xcode)
./gradlew buildIosRelease

# All targets
./gradlew build
```

## 📁 Project Structure

```
jervis-mobile/
├── src/
│   ├── commonMain/          # Shared code
│   │   ├── kotlin/com/jervis/
│   │   │   ├── dto/         # Data classes (copied from parent)
│   │   │   ├── domain/      # Domain enums
│   │   │   ├── mobile/
│   │   │   │   ├── ui/      # Compose UI
│   │   │   │   │   ├── MainScreen.kt
│   │   │   │   │   ├── MainViewModel.kt
│   │   │   │   │   └── App.kt
│   │   │   │   └── api/     # HTTP Client
│   │   │   │       └── KtorMobileAppFacade.kt
│   ├── androidMain/         # Android platform
│   │   ├── kotlin/.../MainActivity.kt
│   │   ├── AndroidManifest.xml
│   │   └── res/
│   └── iosMain/             # iOS platform
│       └── kotlin/.../MainViewController.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 🔧 Configuration

### Android SDK

**Option 1:** Environment variable

```bash
export ANDROID_HOME=/Users/yourname/Library/Android/sdk
```

**Option 2:** local.properties

```properties
sdk.dir=/Users/yourname/Library/Android/sdk
```

### Server URL

Edit in platform files:

**Android:** `src/androidMain/kotlin/.../MainActivity.kt`

```kotlin
val bootstrap = MobileBootstrap(
    serverBaseUrl = "http://10.0.2.2:8080",  // Emulator loopback
    clientId = "",
    defaultProjectId = null
)
```

**iOS:** Create iOS app in Xcode and configure URL

## 🎨 Features

### UI Components

- ✅ Client Selector (dropdown)
- ✅ Project Selector (dropdown)
- ✅ Chat Area (auto-scroll)
- ✅ Message Cards (You/JERVIS)
- ✅ Input Field (multi-line)
- ✅ Send Button
- ✅ Loading States
- ✅ Error Snackbar

### Technical Stack

- **UI:** Compose Multiplatform + Material 3
- **HTTP:** Ktor Client
- **Serialization:** kotlinx.serialization
- **State:** StateFlow / SharedFlow
- **Coroutines:** kotlinx.coroutines

## 📱 Platform Specifics

### Android

**Build variants:**

- `debug` - Debuggable, not minified
- `release` - Minified, requires signing

**Signing (for release):**
Create `keystore.properties`:

```properties
storePassword=yourStorePassword
keyPassword=yourKeyPassword
keyAlias=yourKeyAlias
storeFile=/path/to/keystore.jks
```

### iOS

**Framework output:**

```
build/bin/iosArm64/releaseFramework/JervisMobile.framework
```

**Xcode integration:**

1. Create iOS App project
2. Add framework to project
3. Embed & Sign
4. Use in SwiftUI:

```swift
import JervisMobile

let bootstrap = MobileBootstrap(
    serverBaseUrl: "http://localhost:8080",
    clientId: "",
    defaultProjectId: nil
)

let vc = MainViewControllerKt.MainViewController(bootstrap: bootstrap)
```

## 🧪 Testing

### Android Emulator

```bash
# Start server (from parent jervis/)
cd ../
./gradlew :server:bootRun

# Run Android app
cd jervis-mobile/
./gradlew installDebug

# Or from Android Studio:
# File → Open → jervis-mobile/
# Run → Run 'app'
```

### iOS Simulator

```bash
# Build framework
./gradlew linkDebugFrameworkIosX64

# Open in Xcode and run
```

## 📦 Distribution

### Google Play (Android)

1. Build signed AAB:
   ```bash
   ./gradlew bundleRelease
   ```

2. Upload to Google Play Console
    - Location: `build/outputs/bundle/release/*.aab`

### App Store (iOS)

1. Build framework:
   ```bash
   ./gradlew buildIosRelease
   ```

2. In Xcode:
    - Archive project
    - Distribute → App Store Connect
    - TestFlight → Production

## 🔗 Parent Project Integration

Tento projekt je součástí mono-repo:

```
jervis/                    # Parent (Desktop + Server)
├── common/                # Shared DTO (JVM-only)
└── jervis-mobile/         # This project (KMP)
    └── src/commonMain/kotlin/com/jervis/dto/  # DTO copy
```

**Note:** DTO jsou zkopírované z `../common/`, protože parent je JVM-only a nelze sdílet přes Composite Build s KMP.

## 🛠️ Development

### IntelliJ IDEA

**Otevřít projekt:**

1. `File` → `Open` → vybrat `jervis-mobile/`
2. Gradle sync proběhne automaticky

**Run Configurations:**

- Android: Auto-detekováno
- iOS: Build framework ručně, pak Xcode

### Android Studio

```bash
# Z command line:
studio jervis-mobile/

# Nebo: File → Open → jervis-mobile/
```

## 📊 Build Output Sizes

| Platform | Build Type  | Size   | Note             |
|----------|-------------|--------|------------------|
| Android  | Debug APK   | ~15 MB | With debug info  |
| Android  | Release AAB | ~8 MB  | Minified         |
| iOS      | Framework   | ~12 MB | Static framework |

## 🐛 Troubleshooting

### "SDK location not found"

**Řešení:**

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

### "Cannot resolve com.jervis:common"

**Příčina:** Composite build nefunguje pro KMP vs JVM

**Řešení:** DTO jsou již zkopírované v `src/commonMain/kotlin/com/jervis/dto/`

### Gradle sync fails

```bash
./gradlew --stop
./gradlew clean
./gradlew tasks
```

## 📚 Documentation

- **Parent Project:** `../MULTI_PROJECT_SETUP.md`
- **Mobile Implementation:** `../MOBILE_BUILD.md`
- **Build Guide:** `../BUILD.md`

## 🎯 Next Steps

1. **Setup Android SDK** (pokud chybí)
2. **Test na emulátoru:** `./gradlew installDebug`
3. **Build release:** `./gradlew assembleAndroidRelease`
4. **iOS:** Vytvořit Xcode projekt a integrovat framework
5. **Deploy:** Google Play + App Store

## 📝 Version

- **Version:** 1.0.0
- **Min SDK:** Android 24 (7.0), iOS 14
- **Compose:** 1.7.3
- **Kotlin:** 2.2.0
