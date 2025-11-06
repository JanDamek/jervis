# Jervis Build Guide

## Přehled

Jervis podporuje multiplatformní build pro desktop a mobile aplikace s následujícími výstupy:

### Desktop (s embedded JRE)

- **Windows**: `.msi` installer
- **Linux**: `.deb` balíček
- **macOS**: `.dmg` installer

### Mobile (TODO: vyžaduje kompletní KMP strukturu)

- **Android**: `.aab` pro Google Play
- **iOS**: Framework pro App Store

## Desktop Build

Desktop modul používá **Badass Runtime Plugin** (org.beryx.runtime) pro vytvoření nativních installerů s kompletním JRE

21.

### Jednotlivé platformy

```bash
# macOS (na macOS systému)
./gradlew :desktop:packageDesktopMacOS

# Windows (na Windows systému)
./gradlew :desktop:packageDesktopWindows

# Linux (na Linux systému)
./gradlew :desktop:packageDesktopLinux
```

### Výstupy

Všechny výstupy jsou v `desktop/build/jpackage/`:

- **macOS**: `Jervis-1.0.dmg` (~63 MB)
- **Windows**: `Jervis-1.0.msi`
- **Linux**: `jervis_1.0_amd64.deb`

### Co obsahují balíčky?

Každý installer obsahuje:

- ✅ Kompletní JRE 21 (optimalizováno jlink)
- ✅ Všechny aplikační JAR soubory
- ✅ Nativní launcher
- ✅ Desktop ikonu (pokud existuje v `src/main/resources/icons/`)
- ✅ Automatická instalace a shortcut vytvoření

### Spuštění aplikace

Po instalaci:

- **macOS**: `Applications/Jervis.app`
- **Windows**: Start Menu → Jervis
- **Linux**: Applications menu → Jervis

Žádné další závislosti nejsou potřeba - JRE je součástí balíčku!

## Mobile Build (Stav: TODO)

Mobile modul je aktuálně JVM-only skeleton. Pro plnou funkcionalitu je potřeba:

1. Vytvořit samostatný `composeApp` modul s Compose Multiplatform
2. Migrovat `MobileAppFacade` na Ktor (místo Spring WebClient)
3. Implementovat sdílené UI komponenty
4. Nakonfigurovat Android SDK a iOS podpůrné soubory

### Placeholder tasky

```bash
# Momentálně pouze vypisují TODO zprávy
./gradlew :mobile:assembleAndroidRelease
./gradlew :mobile:buildIosRelease
```

## Full UI Build

Sestaví **všechny platformy najednou**:

```bash
./gradlew full-ui-build
```

Tento task spustí:

1. `:desktop:packageDesktopWindows`
2. `:desktop:packageDesktopLinux`
3. `:desktop:packageDesktopMacOS`
4. `:mobile:assembleAndroidRelease` (TODO)
5. `:mobile:buildIosRelease` (TODO)

⚠️ **Poznámka**: Cross-platform build (např. Windows build na macOS) není možný s jpackage. Každá platforma musí být
buildována na svém native systému.

## Ikony

Pro správné zobrazení ikon vytvořte tyto soubory:

```
desktop/src/main/resources/icons/
├── jervis.icns    # macOS ikona (512x512+)
├── jervis.ico     # Windows ikona (256x256+)
└── jervis.png     # Linux ikona (512x512+)
```

Pokud ikony chybí, installer se vytvoří s default ikonou.

## Technické detaily

### Desktop Build Pipeline

1. **Kompilace**: Kotlin → JVM bytecode
2. **JAR Creation**: Všechny dependencies → fat JAR
3. **jlink**: Vytvoření custom JRE (pouze potřebné moduly)
4. **jpackage**:
    - Image: Vytvoření `.app` bundle
    - Installer: Komprese do `.dmg`/`.msi`/`.deb`

### JRE Moduly (optimalizované)

```kotlin
modules = [
    "java.base",
    "java.desktop",      // Pro Swing UI
    "java.logging",
    "java.management",
    "java.naming",
    "java.prefs",
    "java.sql",
    "jdk.unsupported",
    "jdk.crypto.ec"
]
```

### Velikosti

- **Plný JDK 21**: ~400 MB
- **Optimalizované JRE**: ~50 MB
- **Finální DMG**: ~63 MB (komprese + všechny závislosti)

## Deployment

### Desktop

1. **Build** na každé cílové platformě
2. **Distribuce**:
    - macOS: Notarize přes Apple Developer účet, distribuovat přes web/Mac App Store
    - Windows: Sign pomocí Code Signing Certificate, distribuovat přes web/Microsoft Store
    - Linux: Upload na web nebo repozitáře (apt, flatpak, snap)

### Mobile (budoucí)

1. **Android**:
    - Sign `.aab` pomocí keystore
    - Upload na Google Play Console

2. **iOS**:
    - Build přes Xcode s provisioning profile
    - Upload na App Store Connect
    - TestFlight pro beta testing

## Troubleshooting

### "jpackage: command not found"

Ujistěte se, že máte JDK 21+ (ne pouze JRE):

```bash
java -version  # Mělo by zobrazit "21" nebo vyšší
jpackage --version  # Mělo by fungovat
```

### Build selhává s "module not found"

Přidejte chybějící modul do `runtime.modules` v `desktop/build.gradle.kts`.

### DMG/MSI je příliš velký

jlink automaticky optimalizuje JRE. Pokud je stále velký, zkontrolujte:

- Nepoužívané dependencies v `dependencies {}`
- Nadbytečné JRE moduly v `runtime.modules`

## Další kroky

- [ ] Implementovat kompletní Compose Multiplatform strukturu pro mobile
- [ ] Přidat CI/CD pipeline pro automatické buildy
- [ ] Implementovat auto-update mechanismus
- [ ] Přidat code signing pro všechny platformy
