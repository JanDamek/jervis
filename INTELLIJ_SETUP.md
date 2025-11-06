# IntelliJ IDEA Setup - Vše v jednom IDE

Kompletní setup pro vývoj Desktop, Server, Android a iOS v IntelliJ IDEA Ultimate.

## 📋 Prerequisites

### IntelliJ IDEA Ultimate

- **Verze:** 2024.3+ (doporučeno)
- **Licence:** Ultimate (obsahuje Android a iOS podporu)
- **Download:** https://www.jetbrains.com/idea/download/

### JDK

- **Java 21** - pro Desktop a Server
- **Java 17** - pro Android/iOS (automaticky z Gradle)

### Android SDK

- **Instalace přímo z IntelliJ** (viz níže)
- **Nebo** - Android Studio SDK (pokud již máte)

### iOS Development (volitelné)

- **macOS only**
- **Xcode 14+** - z App Store
- **CocoaPods** (volitelné)

---

## 🚀 Krok za krokem

### 1. Otevřít projekt v IntelliJ

#### A) Otevřít hlavní projekt

```
File → Open → vybrat: jervis/
```

IntelliJ načte všechny moduly:

- ✅ common
- ✅ server
- ✅ desktop
- ✅ api-client
- ✅ service-*

#### B) Připojit mobile projekt

```
File → New → Module from Existing Sources...
→ Vybrat: jervis/mobile-app/build.gradle.kts
→ Kliknout OK
```

**Výsledek v Project Window:**

```
jervis (root)
├── common
├── server
├── desktop
├── api-client
└── ...

mobile-app (module)
├── commonMain
│   ├── kotlin
│   └── resources
├── androidMain
│   ├── kotlin
│   ├── AndroidManifest.xml
│   └── res
└── iosMain
    └── kotlin
```

---

### 2. Nainstalovat Android SDK

#### Varianta A: Instalace z IntelliJ (Doporučeno)

**Krok 1: Otevřít SDK Manager**

```
File → Project Structure → Platform Settings → SDKs
→ Kliknout "+" → Add Android SDK
```

**Krok 2: Vybrat/Vytvořit SDK Location**

```
Default: ~/Library/Android/sdk (macOS)
         C:\Users\<name>\AppData\Local\Android\Sdk (Windows)
         ~/Android/Sdk (Linux)

Kliknout: "Download Android SDK"
```

**Krok 3: Nainstalovat komponenty**

- ✅ **Android SDK Platform 35** (compileSdk)
- ✅ **Android SDK Platform-Tools**
- ✅ **Android SDK Build-Tools 35.0.0**
- ✅ **Android Emulator**
- ✅ **Android SDK Tools**

**Krok 4: Nastavit ANDROID_HOME**

```bash
# macOS/Linux - přidat do ~/.zshrc nebo ~/.bash_profile
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/tools

# Windows - System Properties → Environment Variables
ANDROID_HOME=C:\Users\<name>\AppData\Local\Android\Sdk
```

**Krok 5: Vytvořit local.properties**

```
IntelliJ automaticky vytvoří:
mobile-app/local.properties

Obsahuje:
sdk.dir=/Users/yourname/Library/Android/sdk
```

#### Varianta B: Použít existující Android Studio SDK

Pokud již máte Android Studio:

**Najít SDK location:**

```
Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK
→ Zkopírovat "Android SDK Location"
```

**Nastavit v IntelliJ:**

```
File → Project Structure → Platform Settings → SDKs
→ "+" → Add Android SDK
→ Vybrat cestu z Android Studio
```

---

### 3. Nainstalovat pluginy

#### Povinné pluginy

**Kotlin Multiplatform Mobile:**

```
File → Settings → Plugins
→ Marketplace
→ Hledat: "Kotlin Multiplatform Mobile"
→ Install
```

**Compose Multiplatform:**

```
Marketplace → Hledat: "Compose Multiplatform IDE Support"
→ Install
```

#### Volitelné (ale doporučené)

**Flutter (pokud plánujete Flutter v budoucnu):**

```
Marketplace → "Flutter"
```

**iOS Support (macOS only):**

- Předinstalováno v IntelliJ Ultimate
- Vyžaduje Xcode

**GitToolBox:**

```
Marketplace → "GitToolBox"
→ Enhanced Git integration
```

---

### 4. Konfigurace Run Configurations

#### Server (Spring Boot)

```
Run → Edit Configurations → "+" → Spring Boot

Name: Jervis Server
Main class: com.jervis.server.ServerApplicationKt
Module: jervis.server.main
JRE: 21 (Amazon Corretto 21 / Temurin 21)
```

**Test:**

```
Run → Run 'Jervis Server'
→ Server startuje na http://localhost:8080
```

#### Desktop (Application)

```
Run → Edit Configurations → "+" → Application

Name: Jervis Desktop
Main class: com.jervis.JervisApplicationKt
Module: jervis.desktop.main
JRE: 21
VM options: --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED
```

**Test:**

```
Run → Run 'Jervis Desktop'
→ Otevře se Swing okno
```

#### Android App

**IntelliJ automaticky detekuje Android configuration.**

**Pokud ne, vytvořit manuálně:**

```
Run → Edit Configurations → "+" → Android App

Name: Mobile App (Android)
Module: mobile-app.androidMain
Launch: Default Activity (MainActivity)
```

**Vytvořit Android Emulator:**

```
Tools → Device Manager → Create Device
→ Vybrat: Pixel 6
→ System Image: API 35 (Android 15)
→ Download pokud není k dispozici
→ Finish
```

**Test:**

```
Run → Run 'Mobile App (Android)'
→ Emulator se spustí a nainstaluje APK
```

#### iOS App (macOS only)

**Způsob 1: Framework Build (rychlejší vývoj)**

```
Run → Edit Configurations → "+" → Gradle

Name: iOS Framework Debug
Gradle project: mobile-app
Tasks: linkDebugFrameworkIosSimulatorArm64
```

**Způsob 2: Xcode Integration (pro release)**

- Framework se buildne v Gradle
- Otevře se v Xcode pro spuštění

---

### 5. Gradle Sync

**První sync:**

```
View → Tool Windows → Gradle
→ Click "Refresh all Gradle projects" (🔄 icon)
```

**Co se stane:**

- ✅ Download dependencies
- ✅ Generate source sets
- ✅ Configure Android SDK
- ✅ Setup iOS targets

**Troubleshooting:**

```
File → Invalidate Caches / Restart
→ Invalidate and Restart
```

---

## 🎯 Workflow v IntelliJ

### Vývoj Desktop/Server

```
1. Otevřít soubor v jervis/desktop/ nebo jervis/server/
2. Editovat kód
3. Run → Run 'Jervis Server' nebo 'Jervis Desktop'
4. Hot reload funguje pro většinu změn
```

### Vývoj Android

```
1. Otevřít soubor v mobile-app/src/androidMain/ nebo commonMain/
2. Editovat kód (Compose UI)
3. Run → Run 'Mobile App (Android)'
4. Emulator se updatene automaticky (Compose hot reload)
```

### Vývoj iOS

**Varianta A: IntelliJ + iOS Simulator**

```
1. Editovat kód v commonMain/ nebo iosMain/
2. Run → 'iOS Framework Debug'
3. Framework se přebuiluje
4. Otevřít Xcode projekt
5. Run v Xcode Simulatoru
```

**Varianta B: Fleet (budoucnost)**

```
JetBrains Fleet bude mít přímou iOS simulator podporu
```

---

## 🔧 Nastavení IDE

### Code Style

```
File → Settings → Editor → Code Style → Kotlin
→ Set from: Kotlin style guide
→ Apply
```

### File Watcher (volitelné)

Auto-format on save:

```
File → Settings → Tools → Actions on Save
→ ✅ Reformat code
→ ✅ Optimize imports
```

### Android Layout Preview

```
Otevřít: mobile-app/src/commonMain/.../MainScreen.kt

Pravá strana: Design / Split / Code
→ Preview se zobrazí automaticky
```

### Gradle JVM

```
File → Settings → Build, Execution, Deployment → Build Tools → Gradle
→ Gradle JVM: Project SDK (21)
```

---

## 🐛 Troubleshooting

### "Cannot resolve symbol" v Android kódu

**Řešení:**

```
1. File → Invalidate Caches / Restart
2. Gradle sync (🔄)
3. Build → Rebuild Project
```

### Android Emulator nenačte

**Řešení:**

```
1. Tools → Device Manager
2. Zkontrolovat emulator status
3. Cold Boot emulator
4. Nebo vytvořit nový
```

### iOS build fails

**Řešení:**

```
1. Zkontrolovat Xcode je nainstalován: xcode-select -p
2. Install Command Line Tools: xcode-select --install
3. Open Xcode alespoň jednou (licence agreement)
```

### Gradle Daemon out of memory

**Zvýšit heap:**

```
Editovat: gradle.properties

org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m
```

### ANDROID_HOME not found

**Nastavit v IntelliJ:**

```
Run → Edit Configurations → vybrat Android config
→ Environment variables
→ Přidat: ANDROID_HOME=/path/to/sdk
```

---

## 📱 Android SDK Komponenty (detailní seznam)

### Minimální požadavky:

```
SDK Platform 35 (Android 15.0)
├── Android SDK Platform 35
├── Sources for Android 35

SDK Build Tools
├── 35.0.0

SDK Platform-Tools
└── Latest

SDK Tools
├── Android Emulator
├── Android SDK Tools
└── Intel x86 Emulator Accelerator (HAXM) - Intel CPU
    nebo Android Emulator Hypervisor Driver - Apple Silicon
```

### Doporučené dodatečné:

```
SDK Platform 34 (Android 14) - pro širší kompatibilitu
SDK Platform 33 (Android 13)
SDK Platform 31 (Android 12)
```

### Instalace z command line (alternativa):

```bash
# List available packages
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list

# Install
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "emulator"
```

---

## 🎨 Tips & Tricks

### 1. Compose Preview

```kotlin
// V MainScreen.kt přidat:
@Preview
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            clients = listOf(ClientDto("1", "Test Client")),
            // ... mock data
        )
    }
}
```

→ Preview se zobrazí v IDE

### 2. MultiModule Search

```
Double Shift → Search Everywhere
→ Hledat napříč všemi moduly
```

### 3. Android Logcat

```
View → Tool Windows → Logcat
→ Filtrovat: "com.jervis.mobile"
```

### 4. iOS Console

```
Po buildu frameworku:
Xcode → Window → Devices and Simulators → Open Console
```

### 5. Gradle Build Scan

```
./gradlew build --scan
→ Poskytne URL s detailním build reportem
```

---

## 📊 Struktura v IntelliJ

```
IntelliJ Project Window:

jervis/
├── 📦 common (JVM)
├── 🚀 server (Spring Boot)
├── 🖥️ desktop (Swing + JPackage)
├── 🔧 api-client
├── ⚙️ service-* (microservices)
└── 📱 mobile-app (KMP)
    ├── commonMain (Compose UI)
    ├── androidMain (Android)
    └── iosMain (iOS)
```

---

## ✅ Checklist - Setup Complete

Po dokončení tohoto guidu máte:

- [ ] IntelliJ IDEA Ultimate nainstalováno
- [ ] Projekt jervis/ otevřen
- [ ] mobile-app připojen jako modul
- [ ] Android SDK nainstalován
- [ ] Kotlin Multiplatform Mobile plugin
- [ ] Run configurations vytvořeny
- [ ] Gradle sync proběhl úspěšně
- [ ] Server lze spustit
- [ ] Desktop lze spustit
- [ ] Android emulator funguje
- [ ] iOS framework lze buildit (macOS)

---

## 🎉 Hotovo!

Teď můžete vyvíjet **všechny platformy v jednom IDE**:

```
Ctrl+R / Cmd+R → Run konfigurace
→ Vybrat: Server / Desktop / Android / iOS Framework
→ Build & Run
```

**Jediný IDE, pět platforem:** ✅ Desktop, ✅ Server, ✅ Android, ✅ iOS, ✅ Web (budoucnost)
