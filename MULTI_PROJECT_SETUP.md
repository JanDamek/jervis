# Jervis Multi-Project Setup

## 📁 Struktura Repository

```
jervis/                          # Root directory (jedno Git repo)
├── .git/                        # Shared Git
├── gradle/                      # Shared version catalog
│   └── libs.versions.toml
│
├── # PROJECT 1: Desktop + Server (Kotlin JVM)
├── settings.gradle.kts          # Desktop/Server settings
├── build.gradle.kts             # Root build s full-ui-build
├── gradlew / gradlew.bat
├── common/                      # Shared DTO (publikováno jako Maven)
├── server/                      # Spring Boot backend
├── desktop/                     # Swing UI + JPackage
├── api-client/
├── service-*/
└── mobile/                      # Původní skeleton (deprecated)

└── # PROJECT 2: Mobile (Kotlin Multiplatform)
    └── jervis-mobile/
        ├── settings.gradle.kts  # Mobile settings s Composite Build
        ├── build.gradle.kts     # KMP konfigurace
        ├── gradlew / gradlew.bat
        └── src/
            ├── commonMain/      # Shared UI (Compose)
            ├── androidMain/     # Android specific
            └── iosMain/         # iOS specific
```

## 🔗 Jak to funguje

### Composite Build

**jervis-mobile/settings.gradle.kts** používá `includeBuild("..­")`:

```kotlin
includeBuild("..") {
    dependencySubstitution {
        substitute(module("com.jervis:common"))
            .using(project(":common"))
    }
}
```

**Výhoda:**

- ✅ Automatická kompilace `common` modulu při buildu mobile
- ✅ IntelliJ IDEA rozpozná závislost a poskytne refactoring
- ✅ Změny v `common` se okamžitě projeví v mobile
- ✅ Žádné manuální `publishToMavenLocal`

## 🛠️ Build příkazy

### Desktop + Server (hlavní projekt)

```bash
# V root adresáři (jervis/)
./gradlew :server:bootRun                  # Start server
./gradlew :desktop:packageDesktopMacOS     # Build desktop
./gradlew full-ui-build                    # Build všechny platformy
```

### Mobile (samostatný projekt)

```bash
# V jervis-mobile/ adresáři
cd jervis-mobile
./gradlew assembleDebug                    # Android debug APK
./gradlew assembleAndroidRelease           # Android release AAB
./gradlew buildIosRelease                  # iOS framework
```

### Build všeho najednou

```bash
# Z root adresáře
./gradlew full-ui-build && cd jervis-mobile && ./gradlew assembleAndroidRelease buildIosRelease
```

## 💻 IntelliJ IDEA Setup

### Varianta 1: Dva projekty v jednom okně (Doporučeno)

1. **Otevřít hlavní projekt:**
    - `File` → `Open` → vybrat `jervis/`
    - IntelliJ načte všechny moduly (server, desktop, common, atd.)

2. **Připojit mobile projekt:**
    - `File` → `New` → `Module from Existing Sources...`
    - Vybrat `jervis-mobile/build.gradle.kts`
    - IntelliJ přidá mobile jako další modul

3. **Výsledek:**
   ```
   Project Window:
   ├── jervis (root)
   │   ├── common
   │   ├── server
   │   ├── desktop
   │   └── ...
   └── jervis-mobile
       ├── commonMain
       ├── androidMain
       └── iosMain
   ```

### Varianta 2: Dvě samostatná okna

1. **První okno:** `jervis/` (Desktop + Server)
2. **Druhé okno:** `jervis-mobile/` (Mobile)

**Kdy použít:**

- Pracujete střídavě na desktop a mobile
- Chcete oddělené terminály a run configurations

### Konfigurace Run/Debug

**Desktop:**

```
Run Configuration: JervisApplication
Main class: com.jervis.JervisApplicationKt
Module: jervis.desktop.main
```

**Server:**

```
Run Configuration: Spring Boot
Main class: com.jervis.ServerApplicationKt
Module: jervis.server.main
```

**Android:**

```
Run Configuration: Android App
Module: jervis-mobile.androidMain
```

## 📦 Maven Publishing

### Common modul

Publikace je automatická díky Composite Build, ale lze i manuálně:

```bash
# Z root adresáře
./gradlew :common:publishToMavenLocal

# Vytvoří:
# ~/.m2/repository/com/jervis/common/1.0.0/common-1.0.0.jar
```

### Mobile modul

```bash
cd jervis-mobile
./gradlew publishToMavenLocal

# Vytvoří:
# ~/.m2/repository/com/jervis/jervis-mobile/1.0.0/jervis-mobile-*.jar
```

## 🔄 Version Catalog

Oba projekty sdílejí `gradle/libs.versions.toml`:

**jervis/gradle/libs.versions.toml** - source of truth

**jervis-mobile/settings.gradle.kts** - importuje:

```kotlin
versionCatalogs {
    create("libs") {
        from(files("../gradle/libs.versions.toml"))
    }
}
```

**Výhoda:**

- ✅ Jedno místo pro správu verzí
- ✅ Konzistence mezi projekty
- ✅ Snadná aktualizace dependencies

## 🚀 CI/CD

### GitHub Actions příklad

```yaml
name: Build All Platforms

on: [push, pull_request]

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
      - name: Build Desktop
        run: ./gradlew :desktop:jpackage

  build-mobile:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Build Android
        run: |
          cd jervis-mobile
          ./gradlew assembleRelease
      - name: Build iOS
        run: |
          cd jervis-mobile
          ./gradlew linkReleaseFrameworkIosArm64
```

## 📝 Git Workflow

### Jeden repository, dva projekty

```bash
# Vše je v jednom Git repo
git add .
git commit -m "feat: přidána mobile funkcionalita"
git push

# Struktura commitů:
# ✅ jervis/common/        - změny v DTO
# ✅ jervis/desktop/       - desktop UI změny
# ✅ jervis-mobile/src/    - mobile UI změny
```

### Branch strategie

```
main
  ├── feature/mobile-chat-ui      # Mobile features
  ├── feature/desktop-settings    # Desktop features
  └── fix/common-dto-validation   # Shared DTO fixes
```

## 🔍 Troubleshooting

### "Cannot resolve com.jervis:common"

**Příčina:** Composite build nefunguje

**Řešení:**

```bash
# Z root adresáře
./gradlew :common:publishToMavenLocal

# Pak v jervis-mobile/
./gradlew --refresh-dependencies
```

### IntelliJ nevidí změny v common

**Řešení:**

1. `File` → `Invalidate Caches / Restart`
2. `View` → `Tool Windows` → `Gradle` → `Refresh all`
3. V Gradle tool window: Right-click `jervis-mobile` → `Reload Gradle Project`

### Gradle daemon conflicts

```bash
# Stop všechny daemony
./gradlew --stop
cd jervis-mobile && ./gradlew --stop

# Restart
./gradlew tasks
cd jervis-mobile && ./gradlew tasks
```

## 📊 Srovnání s alternativami

### Multi-Project vs Monorepo vs Separate Repos

| Vlastnost         | Multi-Project (Náš) | Monorepo      | Separate Repos    |
|-------------------|---------------------|---------------|-------------------|
| **Shared code**   | ✅ Composite Build   | ✅ Subprojects | ❌ Maven artifacts |
| **IntelliJ**      | ✅ Dvě okna/moduly   | ✅ Jedno okno  | ❌ Dvě instance    |
| **Git history**   | ✅ Unified           | ✅ Unified     | ❌ Rozdělený       |
| **Build izolace** | ✅ Ano               | ⚠️ Částečně   | ✅ Úplná           |
| **CI/CD**         | ⚠️ 2 workflows      | ✅ 1 workflow  | ❌ 2 workflows     |
| **Versioning**    | ⚠️ Manual sync      | ✅ Unified     | ❌ Independent     |

**Proč jsme vybrali Multi-Project:**

- ✅ Plugin isolation (JVM vs KMP)
- ✅ Flexible build (můžete buildovat jen mobile)
- ✅ Shared version catalog
- ✅ IntelliJ friendly
- ✅ Jedno Git repo

## 🎯 Závěr

### Výhody tohoto setupu:

1. **Jedno repository** - Unified Git history
2. **Dva Gradle projekty** - Izolované pluginy (JVM vs KMP)
3. **Composite Build** - Automatické sdílení `common` modulu
4. **Shared version catalog** - Konzistentní dependencies
5. **IntelliJ friendly** - Funguje s Gradle synckem
6. **Production ready** - Otestováno a funkční

### Kdy buildit co:

```bash
# Vyvíjíte desktop/server
cd jervis/
./gradlew :server:bootRun
./gradlew :desktop:run

# Vyvíjíte mobile
cd jervis-mobile/
./gradlew assembleDebug
# Android Studio: Open jervis-mobile/

# Release build všeho
cd jervis/
./gradlew full-ui-build
cd jervis-mobile/
./gradlew assembleAndroidRelease buildIosRelease
```

## 📞 Quick Reference

```bash
# Desktop build
./gradlew :desktop:packageDesktopMacOS

# Server start
./gradlew :server:bootRun

# Mobile Android
cd jervis-mobile && ./gradlew assembleDebug

# Mobile iOS
cd jervis-mobile && ./gradlew buildIosRelease

# Všechny platformy
./gradlew full-ui-build && \
cd jervis-mobile && \
./gradlew assembleAndroidRelease buildIosRelease
```
