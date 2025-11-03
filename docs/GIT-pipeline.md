### Kompletní průchod zpracováním GITu v Jervis (analogicky k e‑mailům)

Níže je end‑to‑end popis, jak Jervis pracuje s Git repozitáři: od pravidelného zjišťování změn, přes indexaci metadat
commitů a kódových diffů do RAG, tvorbu úloh pro analýzu, až po vykonání analýzy na GPU. Strukturovaně popisuji i
úložiště a způsob, jak se k datům dostává plánovač/model.

---

### 1) Zjištění repozitářů a periodické spouštění

- Služba `GitPollingScheduler` běží periodicky přes `@Scheduled`:
    - Interval a počáteční zpoždění jsou konfigurovatelné: `git.sync.polling-interval-ms` (default 300000 ms = 5 min),
      `git.sync.initial-delay-ms` (default 60000 ms).
- Scheduler má dvě fáze:
    1) „Mono‑repo“ fáze: projde všechny klienty a jejich `monoRepos` (více projektů pod jedním repozitářem).
    2) „Standalone“ fáze: projde všechny samostatné projekty s vlastní Git konfigurací.
- Pro každý repozitář využije `GitRepositoryService` k rychlému `fetch` (historie), následně orchestruje kompletní
  indexaci přes `GitIndexingOrchestrator`.

Důsledek: systém má jednotné, periodické „heartbeat“ pro obě varianty (mono‑repo i standalone) a spouští celý indexing
pipeline.

---

### 2) Uložení nových commitů do DB (stavová fronta NEW → INDEXED)

- Stav commitů spravuje `GitCommitStateManager` s entitou `GitCommitDocument` v kolekci `git_commits`:
    - Klíčová pole: `clientId`, `projectId?` (pro standalone), `monoRepoId?` (pro mono‑repo), `commitHash`, `state` (
      `NEW|INDEXED`), `author?`, `message?`, `commitDate?`, `branch`.
    - Indexy zajišťují unikátnost pro `(clientId, monoRepoId, commitHash)` a `(projectId, commitHash)` a rychlé dotazy
      dle stavu/času.
- `saveNewCommits(...)` / `saveNewMonoRepoCommits(...)`:
    - Vezme seznam commitů z Git (viz níže), porovná s DB a uloží jen nové s `state = NEW`.
- `findNewCommits(...)` / `findNewMonoRepoCommits(...)` vrací proud `Flow<GitCommitDocument>` pro další kroky.
- `markAsIndexed(...)` přepne stav na `INDEXED` po úspěšné tvorbě úloh (viz níže).

Důsledek: stejně jako u e‑mailů existuje „fronta“, která odděluje detekci změn od těžších indexačních/analytických
operací.

---

### 3) Indexace historie commitů (metadata → RAG, TEXT embedding)

- Hlavní práce: `GitCommitMetadataIndexer` (krok 1 v orchestrátoru).
- Pro standalone projekt:
    - `indexGitHistory(project, projectPath, branch, maxCommits)`
    - Nejdříve „sync“ commit ID do DB (`GitCommitStateManager.saveNewCommits`), poté zpracování nových commitů:
      `processNewCommits(...)` → `fetchFullCommitDetails(...)` → `indexGitCommit(...)`.
- Pro mono‑repo (bez `projectId`, s `monoRepoId`):
    - `indexMonoRepoGitHistory(clientId, monoRepoId, monoRepoPath, branch, maxCommits)` → uvnitř
      `processNewMonoRepoCommits(...)` → `indexMonoRepoGitCommit(...)`.
- Co se ukládá do RAG (vektorového úložiště) pro commit METADATA:
    - `RagDocument` s `ragSourceType = GIT_HISTORY`, embedding typ `EMBEDDING_TEXT`.
    - Důležitá metadata: `clientId`, volitelně `projectId` (standalone) nebo `monoRepoId` (sledováno přes indexační
      servis), `gitCommitHash = <hash>`, `branch`, univerzální pole: `from = author`, `subject = první řádek message`,
      `timestamp = commit date`, `parentRef = commitHash`, `contentType = "git-commit"`, `language = "git-commit"`.
    - Před uložením se kontroluje „content changed“ přes `VectorStoreIndexService` (deduplikace/inkrementální výměna
      obsahu).

Důsledek: každý commit má v RAG vyhledatelnou „hlavičku/summary“ s metadaty, což umožňuje rychlé dotazy i napříč
projekty (u mono‑rep) díky `clientId + monoRepoId`.

---

### 4) Indexace kódových změn (diffy → RAG, CODE/TEXT embeddings)

- Hlavní práce: `GitDiffCodeIndexer` (krok 2 v orchestrátoru).
- Pipeline na commit:
    1) `extractCodeChangesFromCommit(projectPath, commitHash)` → rozpad na položky `CodeChange` s `filePath`,
       `changeType (ADDED/MODIFIED/DELETED)`, `addedLines/removedLines`, kontext před/po.
    2) `classifyFileType(filePath)` → `CODE | TEXT | BINARY`.
    3) Indexace dle typu:
        - `CODE`: `indexAsCodeEmbedding(...)`
            - Vytvoří code‑chunks (`createCodeChunks`) z přidaných řádků; pro `MODIFIED` využije
              `VectorStoreIndexService.prepareFileReindexing(...)` (skips, pokud obsah reálně nezměněn),
            - Pro každý chunk vygeneruje `EMBEDDING_CODE` a uloží `RagDocument` s `ragSourceType = CODE_CHANGE` a
              metadaty: `projectId`, `clientId`, `fileName = <path>`, `language` (detekovaná dle přípony),
              `gitCommitHash`, `branch`, `chunkId`, `contentType = "code-diff"`.
            - Záznam o uložení sleduje `VectorStoreIndexService.trackIndexed(...)` (držení `sourceId`, `vectorStoreId`,
              obsah, cesta, commit, atd.).
        - `TEXT`: `indexAsTextEmbedding(...)`
            - Použije `ITikaClient` k extrakci plain‑textu (PDF/DOCX/MD apod.), chunkování a `EMBEDDING_TEXT` (např. pro
              READMEs, dokumentaci, konfiguráky), metadata obdobně doplní.
        - `BINARY`: přeskočí.

Důsledek: RAG umí odpovídat na dotazy typu „co se změnilo v souboru X“ či „kde je změněna metoda Y“, a to s vazbou na
konkrétní `commitHash` a `branch`.

---

### 5) Sumarizace větví (branch‑aware kontext) a jejich indexace

- Služba `GitBranchAnalysisService`:
    - Zjistí seznam remote větví a default branch.
    - Postaví „BranchSummary“ (seznam commitů, extrakce témat, detekce změn – testy/dokumentace/ops –, jednoduchá
      narace).
    - Výsledek ukládá do RAG (opět `ragSourceType = GIT_HISTORY`) s kontrolou změn přes
      `VectorStoreIndexService.hasContentChanged`.

Důsledek: RAG získá kontext nad úrovní jednotlivých commitů – užitečné pro porozumění směru větve či release přípravě.

---

### 6) Tvorba úloh k hluboké analýze (PendingTask)

- Orchestrátor (`GitIndexingOrchestrator`) po dokončení indexace:
    1) Pro každý „nový“ commit vytvoří `COMMIT_ANALYSIS` úlohu přes `GitTaskCreator`.
        - Kontext úlohy: `commitHash`, `author`, `message`, `branch`, `additions`, `deletions`, `changedFilesCount`,
          `projectPath`.
        - Obsah (content) shrnuje commit a cíle analýzy (hledání bugů, arch. dopad, vazby na požadavky…)
    2) Vytvoří `FILE_STRUCTURE_ANALYSIS` úlohy pro změněné soubory (přes
       `fileStructureAnalyzer.analyzeCommitFiles(...)`).
    3) Podle heuristik může založit `PROJECT_DESCRIPTION_UPDATE` úlohu (přes `projectDescriptionUpdater`).
    4) Po úspěšném založení úloh přepne commit v DB na `INDEXED` (`GitCommitStateManager.markAsIndexed`).

Důsledek: stejně jako u e‑mailů existuje „rychlý indexing a enrichment“, a teprve na to navazuje tvorba úloh pro „silný“
model.

---

### 7) Rychlá předkvalifikace (CPU, „small model“) pro Git úlohy

- Probíhá v `TaskQualificationService` dle typu úlohy:
    - Pro `COMMIT_ANALYSIS` využívá prompt z `background-task-goals.yaml` (sekce `COMMIT_ANALYSIS.qualifier*`).
    - Rozhoduje `DISCARD` vs. `DELEGATE` (formátování, comments, typos, čisté verze závislostí → mazat; logické změny,
      API, security, výkonnost, arch refaktoring → delegovat).
    - `DELEGATE` nastaví `needsQualification = false` → úloha míří do GPU smyčky.

Důsledek: drobné rutinní commity se odfiltrují dřív, než zatíží GPU.

---

### 8) Vykonávací smyčka na GPU (silný model + MCP nástroje)

- Stejný mechanismus jako u e‑mailů: `BackgroundEngine` má 2 smyčky (CPU kvalifikace, GPU exekuce). Jakmile je GPU
  volné, vyzvedne kvalifikovanou `PendingTask` a volá `AgentOrchestratorService.handleBackgroundTask(task)`.
- Pro `COMMIT_ANALYSIS` je v `background-task-goals.yaml` definován detailní „goal“ s doporučeným workflow a nástroji:
    - MCP nástroje pro Git:
        - `git_commit_files_list` – rychlý výpis změněných souborů.
        - `git_commit_diff` – plný diff.
    - RAG dotazy:
        - `knowledge_search` se zaměřením na `sourceType = CODE_CHANGE` (nalezení indexovaných změn/chunků) a na
          `FILE_DESCRIPTION` (popisy souborů, které tvoří AI v jiné úloze).
    - Doplňkové nástroje:
        - `git_file_current_content` (pokud chybí popis nebo je potřeba realita z FS),
        - `requirement_query_user` pro napojení na požadavky uživatele,
        - `knowledge_store` pro perzistenci zjištění,
        - `task_create_user` pro vytvoření uživatelských akčních úkolů.
- Orchestrátor iterativně plánuje kroky, vykonává a končí prázdným plánem `[]`, pokud je commit rutinní bez rizik.

Důsledek: hluboká analýza probíhá nástrojově a datově úsporně – nejprve file list, popisy, až poté diff a plné čtení
kódu, jen pokud je nutné.

---

### 9) Kde jsou data a jak se odkazují (Git)

- MongoDB:
    - `git_commits` → `GitCommitDocument` (`NEW/INDEXED`, `commitHash`, `author`, `message`, `commitDate`, `branch`,
      `clientId`, `projectId?` nebo `monoRepoId?`).
    - Pending úlohy → `pending_tasks` (stejná kolekce jako u jiných typů), typy: `COMMIT_ANALYSIS`,
      `FILE_STRUCTURE_ANALYSIS`, `PROJECT_DESCRIPTION_UPDATE`.
- Vektorové úložiště (RAG):
    - `RagDocument` s `ragSourceType = GIT_HISTORY` pro commit metadata a větve.
    - `RagDocument` s `ragSourceType = CODE_CHANGE` pro kódové a textové diff chunk‑y.
    - Klíčová metadata pro trasování: `gitCommitHash`, `branch`, `fileName` (u kódů), `from = author`,
      `subject = first line`, `timestamp = commit date`, `clientId`, volitelně `projectId`.
    - `VectorStoreIndexService` udržuje „index sledování“ s `sourceId` a `vectorStoreId` pro inkrementální reindex a
      mazání.

Poznámka: entita `MessageLink` podporuje kanál `GIT_COMMIT`, nicméně v aktuálním Git pipeline není vytváření linků na
„thread/sender“ použito (na rozdíl od e‑mailů).

---

### 10) Jak se model dostane k commitům, difům a souborům

- Přes MCP nástroje z plánovacího goalu `COMMIT_ANALYSIS`:
    - `git_commit_files_list` → nejrychlejší pohled na rozsah změn.
    - `git_commit_diff` → plný diff konkrétního commitu.
    - `knowledge_search` → cílené dotazy do RAG:
        - podle `commitHash` a `sourceType = CODE_CHANGE` (najde indexované změny),
        - podle `sourceType = FILE_DESCRIPTION` (pokud existuje popis souboru),
        - obecné dotazy přes indexed dokumenty (kód, dokumentace, historie).
    - `git_file_current_content` → v případě potřeby načtení skutečného obsahu ze souborového systému.

Důsledek: model má přímý přístup k metadatům commitů, kódovým změnám i popisům souborů a může dělat navazující
inferenci (např. napojování na požadavky uživatele).

---

### 11) Tenancy a sdílení paměti uvnitř klienta

- Všechna data nesou `clientId`; pro standalone také `projectId`.
- U mono‑repo indexace se commit METADATA ukládá pod `clientId + monoRepoId` (bez `projectId`), aby šel kód hledat
  napříč projekty daného klienta.
- Izolace mezi klienty je striktní – žádná data se nesmí sdílet napříč `clientId`.

---

### 12) Spolehlivost a výkon

- Scheduler běží bezpečně v cyklu; chyby loguje a pokračuje v dalších repozitářích.
- Indexátory používají `withContext(Dispatchers.IO)`, proudy `Flow`, `buffer`, a batch zpracování (`toList()` po
  bufferu) pro vyvážení latence a propustnosti.
- `VectorStoreIndexService.hasContentChanged*` zabraňuje zbytečné re‑indexaci.
- Kódové embeddingy validují, že vektor není prázdný/zero.
- GPU smyčka (`BackgroundEngine`) respektuje `LlmLoadMonitor` a při komunikačních chybách aplikuje backoff; úlohy po
  selhání nemačká do nekonečného retry.

---

### 13) Omezení a rozšiřitelnost

- Autor/„sender“: existuje `AliasType.GIT_AUTHOR` a vztahová heuristika v `SenderProfileService` (COLLEAGUE pro GIT
  autora), ale aktuální Git pipeline neprovádí tvorbu/inkrementaci profilů odesilatelů commitů (na rozdíl od e‑mailů).
  Lze doplnit dle potřeby.
- `MessageLink` s kanálem `GIT_COMMIT` se aktuálně v Git flow nevytváří; vazby a trasování jdou přes `gitCommitHash`,
  `branch`, `fileName` a index tracking ve `VectorStoreIndexService`.
- `sourceUri` se pro Git `RagDocument` explicitně nevyplňuje; návrat ke zdroji se děje přes `commitHash/branch/path` a
  lokální Git FS.

---

### 14) Shrnutí end‑to‑end toku (Git)

1) Scheduler (`GitPollingScheduler`) spustí synchronizaci všech mono‑repo i standalone repozitářů.
2) `GitCommitStateManager` uloží nové commity jako `NEW` do `git_commits`.
3) `GitCommitMetadataIndexer` uloží do RAG metadata commitů (`GIT_HISTORY`, TEXT embedding) s univerzálními poli (
   `from/subject/timestamp/gitCommitHash/branch`).
4) `GitDiffCodeIndexer` uloží do RAG kódové/textové diff‑chunky (`CODE_CHANGE`, CODE/TEXT embedding) s `gitCommitHash`,
   `fileName` a `branch`.
5) `GitBranchAnalysisService` vytvoří/aktualizuje sumarizace větví a uloží je do RAG (`GIT_HISTORY`).
6) `GitIndexingOrchestrator` z nových commitů založí `COMMIT_ANALYSIS` úlohy; pro změněné soubory založí
   `FILE_STRUCTURE_ANALYSIS`; případně vytvoří `PROJECT_DESCRIPTION_UPDATE`. Poté commity označí `INDEXED`.
7) `TaskQualificationService` rychle přefiltruje úlohy (DISCARD vs. DELEGATE) dle `background-task-goals.yaml` →
   delegované jdou na GPU.
8) `BackgroundEngine` při volné GPU kapacitě provede analýzu přes `AgentOrchestratorService` s MCP nástroji (
   `git_commit_files_list`, `git_commit_diff`, `knowledge_search`, …). Výsledek: buď prázdný plán `[]`, nebo vytvořené
   uživatelské úkoly/poznatky.

Tímto je analogicky pokryt kompletní cyklus pro Git: detekce → RAG indexace → kvalifikace → hluboká analýza se zpětnou
trasovatelností přes `commitHash/branch/fileName` a vektorový index.