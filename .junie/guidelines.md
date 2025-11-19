### JERVIS – ARCHITEKTURA A PROGRAMOVACÍ PRAVIDLA (verze 2025‑11)

#### Účel
Jervis je vícevrstvá Kotlin/Spring Boot platforma pro podporu softwarové architektury, řízení projektů, indexaci kódu a dokumentů a asistenci vývojářům (agenti, modely, automatizace). Server je jediný zdroj pravdy; UI je jediný uživatelský vstup.

#### Základní architektura – moduly a role
- Backend (JVM):
    - `backend:server` – centrální mozek aplikace (Spring Boot WebFlux), orchestrace agentů, plánovačů, RAG, dat a integrací.
    - `backend:common-services` – interní REST kontrakty (HttpExchange) mezi serverem a službami; pouze pro service‑*** → server.
    - `backend:service-joern` – samostatná mikroslužba pro code analysis (compute‑only).
    - `backend:service-whisper` – samostatná mikroslužba pro ASR (compute‑only).
    - `backend:service-tika` – samostatná mikroslužba pro extrakci textu/dokumentů (nahradila původní OCR). Compute‑only, bez řízení logiky.
- Shared (KMP):
    - `shared:common-dto` – sdílené DTO objekty (Kotlin Multiplatform), serializace přes `kotlinx.serialization`.
    - `shared:common-api` – API kontrakty pro UI klienty (Spring `@HttpExchange` rozhraní).
    - `shared:domain` – sdílené doménové modely a business typy (KMP), bez perzistentních anotací.
    - `shared:ui-common` – sdílené UI obrazovky a navigace (Compose Multiplatform).
- Aplikace (launchery):
    - `apps:desktop` – primární platforma (plná funkcionalita, více oken, system tray, konzole, nastavení, management).
    - `apps:mobile` – sdílený mobilní launcher pro iOS/Android (single screen + navigace, port z desktopu).

Poznámky k toolchainu a knihovnám (aktuálně):
- Java/Kotlin: JDK 21 (`jvmToolchain(21)`), Kotlin s přísným null‑safety (`-Xjsr305=strict`).
- Web: Spring Boot WebFlux (reaktivní), bez `.block()` v produkci.
- Integrace: Ktor HTTP klient (CIO) pro volání externích služeb.
- Datové úložiště: MongoDB Reactive (Spring Data) jako zdroj pravdy.
- RAG: Weaviate klient (HTTP/GraphQL); chunkování textu přes LangChain4j `TextChunkingService`.
- Serializace: `kotlinx.serialization` jako standard; Jackson pouze pro interop (YAML, JSON bridging).
- Rate limiting: Bucket4j (dle potřeby na interních/vnějších endpointech).

#### Celková struktura projektu (aktuální)
```
server/
├── controller    # REST rozhraní (pouze DTO) – implementace rozhraní z common-api
├── service       # business logika, plánování, orchestrace, agenti, RAG
├── entity        # MongoDB entity (bez průniku do Domain/DTO)
├── repository    # přístup k DB (Reactive)
├── mapper        # převody Domain ↔ Entity (Service) a DTO ↔ Domain (Controller)
└── resources     # konfigurace, definice background úloh

shared/
├── common-dto    # sdílená DTO (KMP)
├── common-api    # @HttpExchange kontrakty pro UI
g└── domain       # sdílené doménové modely (KMP)
└── ui-common     # sdílené UI obrazovky (Compose Multiplatform)

backend/
├── common-services  # interní REST kontrakty pro service-*** ↔ server
├── service-joern    # compute-only služba
├── service-tika     # compute-only služba (extrakce)
└── service-whisper  # compute-only služba

apps/
├── desktop   # primární UI (vše nejdříve zde)
└── mobile    # iOS/Android launcher (port z desktopu)
```

#### Všeobecná logika
- Pro uživatele existuje pouze UI (desktop, následně mobilní porty). Vše mluví se serverem přes `common-api` rozhraní.
- Server je jediný zdroj pravdy a orchestruje procesy, data, plánování i modely. Service‑*** služby jsou pouze výpočetní.
- V UI nic neschovávej (tokeny, hesla, konfigurace se zobrazují otevřeně – fail‑fast kultura, žádné maskování hodnot).
- Klient → projekty → sdílená RAG paměť v rámci klienta. Mezi klienty absolutní izolace (Mongo i RAG filtry na `clientId`, `projectId`).

#### Komunikace mezi moduly
- Pouze přes REST `@HttpExchange` rozhraní.
    - Ve společné knihovně `shared:common-api` definuj rozhraní `I***Service`.
    - Controller na serveru tato rozhraní implementuje.
    - Klientská část (desktop/mobile) tato rozhraní používá přes `HttpServiceProxyFactory`:
      ```kotlin
      private fun createHttpServiceProxyFactory(webClient: WebClient) =
          HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))
              .build()
              .createClient(I***Service::class.java)
      ```
- `backend:common-services` obsahuje pouze interní kontrakty pro service‑*** → server. Nesmí být importováno do `common`, `common-api` ani desktop/mobile.

#### Separation of layers (striktně)
- Controller: pouze DTO; volá Service; mapuje `DTO ↔ Domain`.
- Service: pracuje pouze s Domain; volá Repository; mapuje `Domain ↔ Entity`.
- Repository: pracuje pouze s Entity a DB.
- Zakázané vztahy: Controller → Repository, Service → DTO, Controller → Entity, Domain s persist. anotacemi.

#### Indexace obsahu a RAG
- Vždy: načíst obsah → porovnat s Mongo snapshotem (`contentHash`) → určit novinky.
- Chunkování textu/kódu přes `TextChunkingService` (LangChain4j) a ukládání do Weaviate.
- Kód > 4096 tokenů chunkovat stejnou službou a ukládat do RAG.
- Každá zjištěná změna zakládá „Pending task“ pro kvalifikaci; kvalifikátor rozhodne o hloubce analýzy (CPU/GPU).
- Po kvalifikaci běh agentů (plánovač) s jasnými „goals“; agent může vytvářet vlastní pending tasks (follow‑ups) nebo úkoly pro uživatele.

#### Programovací pravidla (Kotlin/Spring)
- Kotlin‑first, idiomaticky; žádný „Java styl“.
- Coroutines pro asynchronní logiku; Reactor pouze pro interop; bez `.block()` v produkci.
- Preferuj `val`; žádné magické hodnoty (používej `const val`).
- Null‑safety: žádné `!!`; používej `?:`, `?.`, `requireNotNull`, `checkNotNull`.
- DI pouze přes constructor injection. Žádné field injection ani manuální singletony.
- Serializace: `kotlinx.serialization` jako standard; Jackson jen pro interop. Explicitně anotuj odlišná jména polí.
- Logging: strukturovaný, `logger.info { "message with $var" }`. Přidávej correlation/trace ID, pokud je k dispozici.
- Funkce krátké, jednoúčelové; sdílený kód přes extension functions; eliminuj duplicity.
- Jazyk kódu, proměnných a komentářů: výhradně angličtina.
- Inline komentáře `//` nepoužívat k popisu „co“ kód dělá; „proč“ patří do KDoc.

#### Model objektů a mapování
- Domain: immutable (pouze `val`), aktualizace přes `.copy()`.
- Entity: pouze v Repository vrstvě, mutable jen pokud vyžaduje DB.
- DTO: immutable, jen v Controller vrstvě; `@Serializable`.
- Mapování: `DTO ↔ Domain` (Controller), `Domain ↔ Entity` (Service).

#### Závislosti a verze (aktuální praxe)
- JDK 21; Kotlin kompilátor s přísnou null‑safety.
- `kotlinx-serialization` BOM je sjednocena (Spring Boot BOM má starší verzi; řeší se explicitním BOMem a resolution strategiemi).
- Knihovny: Ktor klient, Weaviate, LangChain4j, Jakarta Mail (Angus impl.), Jsoup, JavaParser, Diff utils, SLF4J API.

#### User Tasks – UI (Desktop first)
- Okno „User Tasks“:
    - tabulka čekajících položek (pending),
    - horní lišta „Quick Actions“ (placeholder),
    - tlačítko `Refresh` obnovuje data.
- Badge v ikoně aplikace (macOS Dock) zobrazuje počet aktivních user‑tasků v aktuálním kontextu klienta (součet napříč projekty v rámci klienta, pokud tak definováno endpointem).
- Implementační zásady pro UI:
    - Layout: BorderLayout + GridBagLayout.
    - Okraje: vnější 10 px; vnitřní sekce 12 px; mezery 8 px.
    - Nadpisy: SansSerif BOLD 16 (hlavní), BOLD 13 (sekce).
    - Action bar: `FlowLayout(ALIGN_RIGHT)` s mezerou 8 px.
    - Form panel: GridBagLayout, dvousloupcové řádky (label vlevo, pole vpravo), labely zarovnat vlevo.
    - Chyby zobrazuj přes `JOptionPane.ERROR_MESSAGE`, bez potlačení.
    - Žádné maskování hodnot – vše zobraz otevřeně (tokeny, hesla) dle pravidel.
    - Používej helper `com.jervis.ui.style.UiDesign`:
        - `headerLabel(text)`, `subHeaderLabel(text)`
        - `sectionPanel(title, content)`
        - `actionBar(buttons…)`
        - `formPanel { row(label, field); fullWidth(component) }`

#### Platform priorities a portování UI
- Desktop (PRIMARY – vše nejdřív zde): více oken, system tray, WebSocket/Debug konzole, všechny panely nastavení, management obrazovky.
- iOS (SECONDARY – Priority 1): TestFlight ready; auto‑sync s Desktopem. Adaptace: více oken → jedna obrazovka s navigací; tray/okna → vynechat s `// TODO` a odkazem na desktop implementaci.
- Android (TERTIARY – Priority 2): Záložní aplikace; baseline velký displej (např. Samsung Fold). Sdílí strukturu s iOS. Ikona stejná jako Desktop.
- Sdílené obrazovky v `shared/ui-common`:
    - `MainScreen.kt`, `SettingsScreen.kt`, `UserTasksScreen.kt`, `ErrorLogsScreen.kt`, `RagSearchScreen.kt`, `SchedulerScreen.kt`, `ClientsScreen.kt`, `ProjectsScreen.kt`, `navigation/AppNavigator.kt`.
- Platform‑specific entry points:
    - Desktop: `apps/desktop/src/main/kotlin/com/jervis/desktop/Main.kt`
    - iOS: `apps/mobile/src/iosMain/kotlin/com/jervis/mobile/Main.kt`
    - Android: `apps/mobile/src/androidMain/kotlin/com/jervis/mobile/MainActivity.kt`

#### Orchestrace agentů, kvalifikace a pravidla (aktuální koncepce)
- Server obsahuje `AgentOrchestratorService` (Service vrstva) s kroky: kvalifikace (CPU/GPU), vyhodnocení pravidel („rules“), sestavení `GOALS`, stavba kontextu pro model, routing modelu, provedení kroků (MCP tools), feedback a audit.
- „Rules/Playbooks“ jsou per klient/projekt, verzované v Mongo, indexované do RAG (Weaviate), a vybírány deterministicky podle kontextu operace. Automatické kroky používají jen `VALIDATED` pravidla (fail‑fast, chybějící → DRAFT + user‑task na validaci).
- CPU/GPU routing: rychlé přepínání přes přednačtené pooly (malý GPU model ~2 GB), žádné reloady mezi požadavky; override pouze přes „user‑task“ s auditem.

---

### Zásady architektonické disciplíny (shrnutí)
1) Vrstvy jsou striktně oddělené (Controller ↔ Service ↔ Repository; Mapper dle schématu výše).
2) Domain model je jediný zdroj pravdy v business vrstvě; Entity pouze pro persistenci; DTO pouze pro komunikaci.
3) Server je zodpovědný za orchestraci, řízení procesů, modely a agentní běhy; Service‑*** jsou compute‑only.
4) Komunikace mezi moduly výhradně přes `@HttpExchange` REST API.
5) Klienti mezi sebou nesdílejí data; projekty v rámci klienta sdílejí RAG; filtruj vždy `clientId`/`projectId`.
6) Fail‑fast: neočekávaný stav je výjimka; žádné skryté fallbacky.

---

### Co se oproti dřívějšímu „guidelines“ mění (důležité aktualizace)
- `service-ocr` je nahrazeno `backend:service-tika` (extrakce textu/dokumentů).
- Přibyl modul `shared:domain` (KMP) pro sdílené doménové typy bez perzistentních vazeb.
- Sdílené UI je centralizováno v `shared:ui-common` (Compose Multiplatform) a app launchery jsou `apps:desktop` a `apps:mobile` (iOS+Android).
- Standardně používáme `kotlinx.serialization` BOM a verze sjednocujeme (Spring BOM může být starší).
- RAG je Weaviate s chunkováním přes LangChain4j. Indexace má být vždy řízená snapshoty (`contentHash`) a diff‑chunkingem.

---

### Krátký checklist pro tým (abychom byli v souladu)
- [ ] Controller nikdy nepracuje s Entity/Repository; pouze `DTO ↔ Domain` a volá Service.
- [ ] Service nikdy nepřijímá/nevydává DTO; mapuje `Domain ↔ Entity` a volá Repository.
- [ ] Všechny nové API kontrakty pro UI patří do `shared:common-api` jako `I***Service` a používají `@HttpExchange`.
- [ ] Interní kontrakty pro service‑*** patří do `backend:common-services` a nejsou dostupné v UI/common modulech.
- [ ] Serializace přes `kotlinx.serialization`; Jackson jen interop (např. YAML).
- [ ] Žádné `.block()` v produkci; pouze `suspend` a `Flow`.
- [ ] Každá indexace: snapshot diff → chunking → RAG upsert; při změně vzniká „Pending task“ pro kvalifikaci.
- [ ] Desktop je vždy první platforma implementace; mobil port až po dokončení na desktopu.
- [ ] User‑Tasks okno respektuje design system (layout, okraje, action bar, refresh) a badge v Docku zobrazuje počet aktivních úkolů.

---

### Další kroky
- Pokud máte konkrétní části starého dokumentu, které je potřeba přepsat 1:1, pošlete prosím odkazy/sekce – promítnu je do této aktuální verze.
- Potřebujete‑li do guidelines doplnit konkrétní „rules/playbooks“ (policy pro JIRA/GitHub/MCP), mohu připojit kapitolu „Policy a Rules“ s přesnými Domain/DTO strukturami a pracovními postupy.
