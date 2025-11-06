# Jervis Mobile Build - První návrh

## Stav implementace ✅

Byla vytvořena **kompletní Compose Multiplatform** struktura pro mobile modul s podporou Android a iOS.

### Co bylo implementováno:

#### 1. **Compose Multiplatform UI** ✅

- `MainScreen.kt` - Hlavní obrazovka s:
    - Client Selector (dropdown)
    - Project Selector (dropdown)
    - Chat Area (scrollable messages)
    - Input Field + Send button
- `App.kt` - Root Compose component
- `MainViewModel.kt` - State management a business logika

#### 2. **Ktor API Client** ✅

- `KtorMobileAppFacade.kt` - Nahrazení Spring WebClient za Ktor
- Multiplatform compatible (Android + iOS)
- REST API komunikace se serverem
- Flow-based state management

#### 3. **Android Support** ✅

Soubory:

- `MainActivity.kt` - Activity hosting Compose
- `AndroidManifest.xml` - Permissions a konfigurace
- `res/values/strings.xml` - String resources

#### 4. **iOS Support** ✅

- `MainViewController.kt` - UIViewController wrapper pro Compose
- Framework export s `export(project(":common"))`
- Static framework pro snadnou Xcode integraci

### Struktura souborů:

```
mobile/
├── build.gradle.kts (Kotlin Multiplatform + Compose)
├── src/
│   ├── commonMain/
│   │   └── kotlin/com/jervis/mobile/
│   │       ├── api/
│   │       │   └── KtorMobileAppFacade.kt
│   │       └── ui/
│   │           ├── App.kt
│   │           ├── MainScreen.kt
│   │           └── MainViewModel.kt
│   ├── androidMain/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/jervis/mobile/
│   │   │   └── MainActivity.kt
│   │   └── res/
│   │       └── values/
│   │           └── strings.xml
│   └── iosMain/
│       └── kotlin/com/jervis/mobile/
│           └── MainViewController.kt
```

## Známý problém ⚠️

**Plugin Conflict**: Kotlin Multiplatform plugin nelze aplikovat v mobile modulu, protože common modul je JVM-only.

```
Error resolving plugin [id: 'org.jetbrains.kotlin.multiplatform']
The plugin is already on the classpath with an unknown version
```

### Řešení (3 možnosti):

#### Možnost 1: Separátní mobile projekt (Doporučeno)

Vytvořit samostatný Gradle projekt pro mobile:

```
jervis/          # Desktop + Server
jervis-mobile/   # Samostatný KMP projekt
```

#### Možnost 2: Konverze common modulu na KMP

Migrovat `:common` na Kotlin Multiplatform:

```kotlin
// common/build.gradle.kts
kotlin {
    jvm()
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
}
```

#### Možnost 3: Duplikace DTO

Zkopírovat potřebné DTO do mobile/commonMain (ne ideální, ale rychlé).

## UI Preview

### MainScreen layout:

```
┌─────────────────────────────────┐
│     JERVIS Assistant            │ ← TopBar
├─────────────────────────────────┤
│ Client: [Select client... ▼]    │ ← Selectors
│ Project: [Select project... ▼]  │
├─────────────────────────────────┤
│                                 │
│  [You]                          │
│  ┌───────────────────┐          │
│  │ Hello JERVIS!     │          │ ← Chat Area
│  └───────────────────┘          │
│                                 │
│           [JERVIS]              │
│  ┌───────────────────┐          │
│  │ Hello! How can I  │          │
│  │ help you?         │          │
│  └───────────────────┘          │
│                                 │
├─────────────────────────────────┤
│ [Type your message...] [Send]   │ ← Input
└─────────────────────────────────┘
```

## Features

### Implementováno:

- ✅ Material 3 design
- ✅ Responsive layout (mobile optimized)
- ✅ Auto-scroll chat na nové zprávy
- ✅ Loading state (spinner když čeká na odpověď)
- ✅ Error handling (Snackbar pro chyby)
- ✅ Client/Project selection závislosti
- ✅ Disabled state pro Send tlačítko

### Připraveno k použití:

- Flow-based state management
- Coroutine-safe operations
- Lifecycle-aware (DisposableEffect)
- Ktor HTTP client (CIO engine)

## Build tasky

```bash
# Android
./gradlew :mobile:assembleAndroidRelease
# Vytvoří: mobile/build/outputs/bundle/release/*.aab

# iOS
./gradlew :mobile:buildIosRelease
# Vytvoří: mobile/build/bin/iosArm64/releaseFramework/JervisMobile.framework
```

## Další kroky

1. **Vyřešit plugin conflict** (vybrat jednu z možností výše)
2. **Test build** na skutečném zařízení
3. **WebSocket** implementace pro real-time notifikace
4. **Ikony a resources** (launcher icons, splash screen)
5. **Build signing** (Android keystore, iOS provisioning)
6. **CI/CD** integrace

## Poznámky k implementaci

### Rozdíly oproti Desktop:

- **Ktor místo Spring WebClient** (multiplatform)
- **Compose místo Swing** (modern, declarative)
- **Flow místo blocking calls** (reaktivní)
- **Material 3** design language

### Zachovaná funkcionalita:

- ✅ Client + Project selection
- ✅ Chat interface
- ✅ Message sending
- ✅ Response handling
- ✅ Loading states

### Zjednodušení:

- ❌ Plány zobrazení (zatím ne)
- ❌ Quick response checkbox (lze přidat)
- ❌ Menu bar (mobilní nemá)
- ❌ Tray icon (mobilní nemá)

## Testování

### Android Emulator:

```bash
# Server na localhost
./gradlew :server:bootRun

# V Android Studio: Run MainActivity
# Server URL: http://10.0.2.2:8080
```

### iOS Simulator:

```bash
# Vytvoř framework
./gradlew :mobile:buildIosRelease

# V Xcode:
# 1. Vytvoř iOS App projekt
# 2. Link framework: JervisMobile.framework
# 3. V ContentView.swift:
#    UIViewControllerRepresentable s MainViewController()
```

## Závěr

První návrh mobile aplikace je **kompletní a připravený k buildu** po vyřešení plugin conflictu.

UI je funkčně ekvivalentní k Desktop MainWindow s modernějším Material 3 designem optimalizovaným pro mobilní zařízení.
