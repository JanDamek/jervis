# JERVIS Desktop Application

Compose Multiplatform desktop aplikace pro JERVIS AI Assistant.

## Konfigurace serveru

Aplikace podporuje různé profily pro snadné přepínání mezi prostředími.

### Dostupné profily

- **local** (výchozí) - Lokální server na `http://localhost:5500`
- **remote** - Vzdálený server v lokální síti na `http://192.168.100.117:5500`
- **public** - Veřejný server na `https://jervis.your-domain.com`

### Spuštění aplikace

#### 1. Pomocí Gradle tasku (nejjednodušší)

```bash
# Local server (výchozí)
./gradlew :apps:desktop:runLocal

# Remote server
./gradlew :apps:desktop:runRemote

# Public server
./gradlew :apps:desktop:runPublic
```

#### 2. Pomocí Compose Desktop run tasku s profilem

```bash
# Local (výchozí)
./gradlew :apps:desktop:run

# Remote
./gradlew :apps:desktop:run -Pjervis.profile=remote

# Public
./gradlew :apps:desktop:run -Pjervis.profile=public
```

#### 3. Pomocí vlastní URL

```bash
./gradlew :apps:desktop:run -Pjervis.server.url=http://custom-server:8080
```

#### 4. Spuštění z IntelliJ IDEA / Android Studio

1. Otevřete projekt v IDE
2. Najděte Gradle tasky v sekci `apps:desktop > application`
3. Spusťte jeden z tasků:
   - `runLocal`
   - `runRemote`
   - `runPublic`

Nebo přidejte VM options do Run Configuration:
```
-Djervis.server.url=http://localhost:5500
```

### Úprava URL v profilech

Pro změnu URL editujte soubor `build.gradle.kts` v sekci `serverUrls`:

```kotlin
val serverUrls = mapOf(
    "local" to "http://localhost:5500",
    "remote" to "http://192.168.100.117:5500",
    "public" to "https://jervis.your-domain.com"  // <-- změňte zde
)
```

## Build a distribuce

### Vytvoření spustitelné aplikace

```bash
# Vytvoření native distribuční balíčku pro váš OS
./gradlew :apps:desktop:packageDistributionForCurrentOS

# Nebo specifické formáty:
./gradlew :apps:desktop:packageDmg      # macOS
./gradlew :apps:desktop:packageMsi      # Windows
./gradlew :apps:desktop:packageDeb      # Linux
```

Výsledné soubory najdete v `apps/desktop/build/compose/binaries/main/`.

### Development build

```bash
# Kompilace bez spuštění
./gradlew :apps:desktop:assemble

# Spuštění s hot reload (automatické načtení změn)
./gradlew :apps:desktop:run --continuous
```

## Funkce

- ✅ Material Design 3 UI
- ✅ Multi-window architektura
- ✅ Správa projektů a klientů
- ✅ Task management
- ✅ Asynchronní API komunikace s Ktor
- ✅ Repository pattern pro datovou vrstvu
- ✅ MVVM architektura s StateFlow

## Struktura projektu

```
desktop/
├── src/main/kotlin/com/jervis/desktop/
│   ├── Main.kt                 # Entry point aplikace
│   └── ui/                     # UI komponenty
│       ├── MainContent.kt      # Hlavní obrazovka
│       ├── ClientsWindow.kt    # Správa klientů
│       ├── ProjectsWindow.kt   # Správa projektů
│       └── ...
└── build.gradle.kts           # Build konfigurace
```

## Řešení problémů

### Build selhává s "out of memory"

Zvyšte heap size v `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m
```

### Aplikace se nemůže připojit k serveru

1. Zkontrolujte, že server běží
2. Ověřte URL v konfiguraci
3. Zkontrolujte firewall/network nastavení
4. Pro debugging spusťte s logováním:
   ```bash
   ./gradlew :apps:desktop:run --debug
   ```

### Hot reload nefunguje

Použijte:
```bash
./gradlew :apps:desktop:run --continuous
```
