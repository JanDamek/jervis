# Jervis – jednotné Engineering & UI Guidelines (2025‑11)

Poslední aktualizace: 2025‑11‑28

Tento dokument je jediný zdroj pravdy (SSOT) pro architekturu, programovací pravidla a UI zásady v projektu Jervis. Všechny ostatní historické „guidelines“ soubory jsou aliasy odkazující sem.

## 1) Cíle a principy
- Fail‑fast kultura: chyby nezakrývat, žádné tiché fallbacky. Výjimka je lepší než maskovat chybu. Try/catch pouze na hranicích (I/O, REST boundary) pro konverzi na doménové chyby a logování; ne uvnitř business logiky.
- Kotlin‑first, idiomaticky: coroutines + Flow jako základ asynchronní práce. Vyhýbat se „Javě v Kotlinu“. Preferuj streamování (`Flow`, `Sequence`) před budováním velkých `List`.
- IF‑LESS pattern: kde hrozí rozšiřování, nahrazuj `if/when` polymorfismem, sealed hierarchiemi, strategy mapami nebo routovací tabulkou. `if/when` je OK pro triviální případy.
- SOLID: malé, jednoúčelové funkce; vysoká soudržnost, nízké vazby; eliminace duplicit (preferuj extension functions před „utils“ třídami).
- Jazyk v kódu, komentářích a logách: výhradně angličtina. Inline komentáře „co“ nepíšeme; „proč“ patří do KDoc.

### Development mód – pravidla pro rychlý vývoj (platí do odvolání)
- Žádné deprecations ani kompatibilitní vrstvy: změny datových typů jsou povoleny (breaking changes). Cílem je udržet kód čistý a přímočarý.
- UI neschovává žádné hodnoty: hesla, tokeny, klíče a jiné „secrets“ jsou v UI vždy viditelné (žádné maskování). Tato aplikace není veřejná.
- DocumentDB (Mongo): nic nešifrujeme; vše ukládáme v „plain text“. Toto je vědomé rozhodnutí pro privátní dev instanci.

### Shared UI pro Desktop i Mobile (iPhone)
- Všechny obrazovky mají společný zdroj pravdy v `shared/ui-common` (Compose Multiplatform). Desktop i mobil (zejména iPhone) používají stejné composables.
- Layouty musí být adaptivní: žádné fixní šířky, rozumné zalamování, skrolování, touch‑targety na mobilu ≥ 44dp.

## 2) Architektura a moduly
- Server (Spring Boot WebFlux) je jediný zdroj pravdy; orchestruje procesy, RAG, plánování, integrace a modely.
- Compute‑only služby: `backend:service‑joern`, `backend:service‑tika`, `backend:service‑whisper`.
- Shared KMP: `shared:common‑dto` (DTO), `shared:common‑api` (HttpExchange kontrakty), `shared:domain` (čisté doménové typy), `shared:ui‑common` (Compose UI obrazovky).
- Aplikace: `apps:desktop` (primární), `apps:mobile` (iOS/Android, port z desktopu).

## 3) Komunikace a kontrakty
- Mezi klienty a serverem výhradně `@HttpExchange` rozhraní v `shared:common‑api` jako `I***Service`. Serverové controllery je implementují.
- `backend:common-services` obsahuje jen interní REST kontrakty pro service‑*** → server; není dostupné v UI/common modulech.

## 4) Vrstvy (sjednoceno dle aktuálního kódu)
- Controller: pracuje s DTO; mapuje `DTO ↔ Entity` (případně přes mapper); volá Service. Controller nikdy nevrací Entity do UI.
- Service: pracuje s MongoDB Entity (Documents) a business pravidly; volá Repository. Domain typy lze použít pomocně (čisté výpočty), ale Service není povinně „domain‑only“.
- Repository: pouze Entity ↔ DB.
- Zakázané vztahy: Controller → Repository, Service → DTO, Controller vrací Entity.

## 5) Programovací pravidla (Kotlin/Spring)
- Concurrency: coroutines, žádné `.block()` v produkci; Reactor pouze pro interop.
- DI: výhradně constructor injection. Žádné field injection, žádné manuální singletony.
- Extension functions preferuj před „Utils“ třídami.
- Serializace: `kotlinx.serialization` jako standard; Jackson jen pro interop (YAML, JSON bridging). Odlišná jména polí explicitně anotuj.
- Logging: strukturovaný, např. `logger.info { "message with $var" }`; doplňuj trace/correlation ID, pokud je k dispozici.

## 6) Konfigurace (properties) – striktní pravidla
- Používej VÝHRADNĚ properties třídy: `@ConfigurationProperties` + `data class` s pouze `val` poli. ŽÁDNÉ `@Value` v kódu. ✓
- Žádné výchozí hodnoty v properties třídách, žádná `nullable` pole. Každá hodnota musí být poskytnuta v `application.yml` nebo ENV. ✓
- Chybějící hodnota = fail‑fast při startu aplikace (žádné tiché fallbacky). ✓
- Properties třídy mapuj na YAML strom logicky (prefixy). Příklad: `preload.ollama.*`, `ollama.keep-alive.default`.
- Definuj vlastní `@Qualifier` anotace pro rozlišení beanů (např. `@OllamaPrimary`, `@OllamaQualifier`) místo string klíčů.

## 7) Struktura balíčků a přístupnost
- Jeden veřejný boundary interface na okraji celku; vnitřek označ `_internal` a udržuj package‑private (nesvádět k neplánovanému použití).
- Interface nepoužívej „za každou cenu“. Pokud není potřeba více implementací, stačí `class`.
- Pokud je potřeba re‑use napříč celky, vytvoř pomocný balíček se sdílenými službami (bez vynucování interface, pokud nedává smysl).

## 8) UI design (Compose Multiplatform – viz také docs/ui-design.md)
- Používej sdílené komponenty ze `shared/ui-common`:
  - `JTopBar`, `JSection`, `JActionBar`, `JTableHeaderRow`/`JTableHeaderCell`, `JTableRowCard`
  - View states: `JCenteredLoading`, `JErrorState(message, onRetry)`, `JEmptyState(message)`
  - Akce/util: `JRunTextButton`, `ConfirmDialog`, `RefreshIconButton`/`DeleteIconButton`/`EditIconButton`, `CopyableTextCard`
- Nahrazuj přímý `TopAppBar` → `JTopBar`. Stavové UI sjednoť na výše uvedené view‑state komponenty. 
- Fail‑fast v UI: chyby zobrazuj, neukrývej. Spacing přes sdílené konstanty (JervisSpacing). Desktop je primární platforma; mobil je port.

## 9) RAG a indexace
- Postup: načti → diff proti Mongo `contentHash` → nové části → chunking (`TextChunkingService`) → Weaviate upsert.
- Weaviate kolekce: `vectorizer="none"`, distance=cosine, HNSW parametry konzistentní; dimenze musí odpovídat zvolenému embeddingu.
- Změny zakládají pending task pro kvalifikátor; po kvalifikaci běží orchestrace agentů (goals, routing, MCP tools, audit).

### 9.1) Per‑client OGDB + RAG (SSOT)
- Knowledge Graph: ArangoDB, per‑client kolekce (documents, entities, files, classes, methods, tickets, commits, emails, slack, requirements) + edge kolekce (mentions, defines, implements, modified_by, changes_ticket, affects, owned_by, concerns, describes). Vytváří se idempotentně na startu serveru (bootstrap).
- Weaviate RAG: per‑client třídy `{clientSlug}_rag_text` a `{clientSlug}_rag_code`. Properties: `content`, `projectId`, `sourcePath`, `chunkIndex`, `totalChunks`, `entityTypes[]`, `contentHash`, `graphRefs[]`. Vectorizer="none"; distance=cosine; dimenze = 1024 (`bge-m3`).
- Cross‑links: Weaviate chunk nese `graphRefs[]`; Arango uzly nesou `ragChunks[]`. `RagMetadataUpdater` udržuje obousměrné odkazy (append; idempotentní klíče).
- Bootstrap: při startu serveru se pro každého klienta vytvoří per‑client OGDB+RAG zdroje a uloží se `ClientReadyStatus` do Mongo. Fail‑fast: pokud je klient aktivní a provisioning selže, start se ukončí s chybou.
- Health endpointy nevystavujeme; stav a provisioning řešíme idempotentními operacemi a logy.

## 9.2) Typová pravidla a identita (Kotlin‑first)
- Slug se nikde nepoužívá pro doménovou logiku. Všechny vazby a názvy odvozuj z ID (např. `clientId: ObjectId`).
- Zaveď `@JvmInline value class` pro hodnotové ID typy a používej je na hranicích API i v doméně:
  - `ClientId(val value: ObjectId)`, `ProjectId(val value: ObjectId)`, apod.
  - Maximalizuj typovou bezpečnost (žádná generická `String`/`ObjectId` tam, kde lze mít sémantický typ).
- Architektura: veřejné rozhraní vždy za `interface`; implementace uvnitř balíčku `*_internal` jako `internal`/package‑private. Doménové objekty:
  - pokud veřejné → na úrovni `interface` (API/kontrakty),
  - jinak do `*_internal` a nepřístupné vně balíčku.
- UI řeš vždy samostatně až po stabilizaci serveru (server‑first).

## 9.3) Auto‑provision a provoz
- OGDB (Arango): před jakoukoliv operací použij `ensureDatabase()`; `ensureSchema(clientId)` musí být idempotentní. První zápis smí implicitně vytvořit chybějící kolekce.
- RAG (Weaviate): třídy zakládej idempotentně dle `clientId` → jméno třídy odvoď deterministicky (např. `WeaviateClassNameUtil` z `ObjectId`). Při prvním `store/search` je povolen runtime guard.
- Embedding refresher: drž `keep_alive` přes `EmbeddingModelRefresher` (interval = `keep_alive * safetyFactor`), pro kompatibilitu posílej i `options.keep_alive` a `prompt|input`.

## 10) Modely (LLM/Embeddings) – provoz
- Ollama preloader běží asynchronně – start serveru na něj nečeká.
- Před prvním použitím chybějícího modelu klient provede blokující `pull` a počká (fail‑fast s jasnými logy). Pokud je model stažený ale „ne‑warmed“, první volání ho nahraje do paměti; používáme `keep_alive` (default 1h).
- Routing: `OLLAMA` → GPU (primary), `OLLAMA_QUALIFIER` → CPU (qualifier). Embeddingy běží na CPU Ollamě.
- Embeddingy: TEXT=`bge-m3` (1024), CODE=`bge-m3` (1024). U smíšených dotazů doporučen „dual retrieval + rank fusion" (sloučení top‑K z obou kolekcí).

---

### Backlog pro dorovnání kódu s guidelines
- Konfigurace: nahradit `@Value` → `@ConfigurationProperties`; přidat vlastní `@Qualifier` anotace pro WebClienty.
- Weaviate: pokud byla dříve Code kolekce v jiné dimenzi, provést reindex.
- UI: sjednotit na `JTopBar` + standardní view‑state komponenty, kde ještě nejsou.

### Specifika „Connections“ (vlastnictví, správa v UI)
- Connection entity smí patřit právě jednomu vlastníkovi: buď `Client`, nebo `Project` (ne oběma současně). Sdílení přes vícenásobné připojení se řeší duplikací.
- Správa připojení je součástí editace Klienta/Projektu (žádné samostatné „Connections“ okno). V UI zobrazovat kompletní hodnoty včetně secretů.
