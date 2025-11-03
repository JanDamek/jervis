### Pokračování: Kompletní průchod zpracováním e‑mailu v Jervis

Níže navazuji uceleným, krok‑za‑krokem popisem celé cesty e‑mailu: od zjištění nových zpráv, přes indexaci do RAG,
obohacení o kontext odesílatele a vlákna, předkvalifikaci (rychlý filtr), až po delegaci na silný model v GPU smyčce a
uložení všech vazeb.

---

### 1) Zjištění nových zpráv a uložení „hlaviček“

- Periodický plánovač `EmailPollingScheduler` (cron každých 60 s) načte další aktivní účet a spustí
  `EmailIndexingOrchestrator.syncMessageHeaders()`.
- `ImapClient.fetchMessageIds(...)` vrací proud `ImapMessageId` (UID, `messageId`, `subject`, `from`, `receivedAt`).
- `EmailMessageStateManager.saveNewMessageIds(...)`:
    - Dávkově porovná nový seznam `messageId` proti kolekci `email_messages` a pouze nové uloží jako
      `EmailMessageDocument` se stavem `NEW`.
    - Struktura `EmailMessageDocument`: `accountId`, `messageId`, `uid?`, `state`, `subject?`, `from?`, `receivedAt?`.
    - Indexy pro rychlé dotazy: `('accountId','messageId')` unikátní, `('accountId','state','receivedAt')`,
      `('accountId','uid')`.

Důsledek: DB rychle drží frontu „co indexovat“, ale nezačíná těžké operace hned.

---

### 2) Kontinuální indexace obsahu (tělo, přílohy, odkazy)

- Dlouhotrvající služba `EmailContinuousIndexer.startContinuousIndexing(account)` čte plynule z `email_messages` stav
  `NEW` pomocí `EmailMessageStateManager.continuousNewMessages(...)`.
- Pro každý záznam:
    1) Stáhne plný e‑mail přes `ImapClient.fetchMessage(account, uid)` do `ImapMessage` (`messageId`, `subject`, `from`,
       `to`, `receivedAt`, `content`, `attachments`).
    2) Zavolá `indexMessage(...)`, které provede tři paralelní části:
        - 2.1) Indexace těla: `EmailContentIndexer.indexEmailContent(...)`
            - Pokud je `content` HTML, extrakce čistého textu přes interní Tika službu `ITikaClient` (`service-tika`).
            - Dělení na segmenty přes `TextChunkingService` → pro každý kus embed přes `EmbeddingGateway` (
              `ModelTypeEnum.EMBEDDING_TEXT`).
            - Uložení do vektorového úložiště přes `VectorStorageRepository.store(...)` jako `RagDocument` s
              `ragSourceType = EMAIL`.
            - Klíčová metadata: `clientId`, `projectId?`, `sourceUri = "email://<accountId>/<messageId>"`, `from`,
              `subject`, `timestamp`, `parentRef = messageId`, `chunkId`, `chunkOf`, `contentType = "text/html"`.
            - Vrací `ragDocumentId` prvního chunku (pro volnější odkaz na „hlavu“ dokumentu e‑mailu).
        - 2.2) Indexace příloh: `EmailAttachmentIndexer.indexAttachments(...)`
            - Každá příloha → `ITikaClient.process(...)` (text + metadata), chunkování, embedding, uložení do vektoru s
              `ragSourceType = EMAIL_ATTACHMENT`.
            - `sourceUri = "email://<accountId>/<messageId>/attachment/<index>"`, metadata `fileName`, `contentType`,
              `indexInParent`, `totalSiblings`.
        - 2.3) Indexace odkazů v textu: `LinkIndexingService.indexLinksFromText(...)`
            - Extrahuje URL z `subject + content`, ukládá obsah na pozadí do RAG s `ragSourceType = EMAIL_LINK_CONTENT`
              a `parentRef = messageId`.
    3) Po úspěchu označí `EmailMessageStateManager.markAsIndexed(...)` (stav `INDEXED`). Chyby IMAP/komunikace se
       klasifikují a případně zpomalí další pokusy, konkrétní zpráva se však označí `FAILED`, aby fronta běžela dál.

---

### 3) Odesílatel (Sender profile): vznik, obohacení, statistiky

- `TaskQualificationService.qualifyEmailEnriched(...)` se volá bezprostředně po indexaci obsahu, aby připravil obohacený
  kontext pro rozhodování:
    1) `SenderProfileService.findOrCreateProfile(...)`
        - Klíčem je identifikátor `email.from`. Pokud profil neexistuje, zakládá se s aliasem typu
          `AliasType.EMAIL_WORK` a inferencí `relationship` podle heuristik (např. `noreply` →
          `RelationshipType.SYSTEM`).
        - Pole profilu: `primaryIdentifier`, `displayName?`, `aliases[]`, `relationship`, `organization?`, `role?`,
          `conversationSummary?`, `topics[]`, metriky a časová pole (first/last seen, interactions).
        - K dispozici je filtrování témat (`findByTopic`) – „skupiny“ odesílatelů jsou tak dnes řešitelné přes `topics`
          a `relationship`, není zde separátní objekt „skupina“.
    2) `SenderProfileService.incrementMessagesReceived(profileId)` zvýší počítadla a timestamps.

---

### 4) Vlákno konverzace (Conversation thread): vznik, slučování, stav

- `ConversationThreadService.findOrCreateThread(emailHeaders, senderProfileId, clientId, projectId)`
    - `threadId` se prozatím bere jako `email.messageId` (protože IMAP hlavičky `References/In-Reply-To` nejsou v
      modelu). Z důvodu absence explicitní konverzační vazby je doplněna heuristika slučování:
        - Hledá se „nedávné“ vlákno stejného normalizovaného předmětu (odstranění `Re:/Fwd:/Fw:`) v rámci stejného
          `clientId` a `projectId` v 14denním okně.
        - Pokud existuje, nové ID zprávy se do něj přidá (`addMessageToThread`), jinak se založí nový dokument.
    - `ConversationThreadDocument` uchovává: `threadId`, `subject`, `senderProfileIds[]`, `messageIds[]`, čítače,
      `firstMessageAt/lastMessageAt`, `lastMessageFrom`, `requiresResponse` (heuristika: `?` v obsahu/předmětu,
      `failed/error` v předmětu), `status`, `priority`, `category` (např. `SYSTEM_NOTIFICATION`, `SUPPORT_REQUEST`),
      `ragDocumentIds[]`, `clientId`, `projectId`.
    - Při obohaceném kvalifikačním běhu se také volá
      `conversationThreadService.addRagDocumentId(thread.id, ragDocumentId)`.

---

### 5) Propojení zprávy s vláknem a RAG

- `MessageLinkService.createLinkFromEmail(...)` ukládá záznam `MessageLink`:
    - Vazby: `messageId` → `threadId`, `senderProfileId`, odkaz na `ragDocumentId`, kanál `EMAIL`.
    - Dále `subject`, textový `snippet`, `timestamp`, příznak `hasAttachments`.
- Nad linky existují dotazy `findByThreadId(...)`, `findBySenderProfileId(...)` pro rychlé dohledání souvisejících
  zpráv.

---

### 6) Rychlá předkvalifikace (CPU, „small model“)

- Stavební krok: „Discard nebo Delegate“. Probíhá přímo v `TaskQualificationService.qualifyBasicEmail(...)`:
    - Z `background-task-goals.yaml` se načte konfigurace pro `PendingTaskTypeEnum.EMAIL_PROCESSING`:
        - `qualifierSystemPrompt` a `qualifierUserPrompt` se předají do `QualifierLlmGateway`.
    - Model zodpoví `DISCARD` (šum, typické newsletttery, nerelevantní promo) nebo `DELEGATE` (potřeba hlubší práce).
- Rezoluční háček před tvorbou nové úlohy: služba se pokusí uzavřít již existující „uživatelské úkoly“ související s
  daným vláknem, pokud příchozí e‑mail poskytuje rozřešení. Pak může nastavovat `requiresResponse=false` a případně
  `ThreadStatus.RESOLVED`.

Výsledek:

- `DISCARD` → nic dalšího se netvoří.
- `DELEGATE` → vzniká `PendingTask` typu `EMAIL_PROCESSING` s minimalistickým, ale silně propojovacím kontextem:
    - `context`: `senderProfileId`, `threadId`, `sourceUri = "email://..."`, `from`, `subject`, `date`, volitelně
      `previousTasksClosed`.
    - `content`: serializovaná hlavička + obsah e‑mailu (včetně seznamu příloh).

---

### 7) Vykonávací smyčka na GPU (silný model, plánování a nástroje)

- Služba `BackgroundEngine` drží dvě nezávislé smyčky:
    - CPU „qualification loop“ (viz výše) běží pořád.
    - GPU „execution loop“ běží jen při volné kapacitě (`LlmLoadMonitor.isIdleFor(...)`).
- Jakmile je GPU volné, `BackgroundEngine` vyzvedne jeden `PendingTask(needsQualification=false)` a předá jej do
  `AgentOrchestratorService.handleBackgroundTask(task)`.
- `AgentOrchestratorService` vytvoří prompt:
    - Připojí obsah `PendingTask`, kvalifikační poznámky, dynamické cíle a načte úlohové `goal` z
      `background-task-goals.yaml` pro `EMAIL_PROCESSING`.
    - Cíl pro e‑mailovou úlohu explicitně říká, že je k dispozici obohacený kontext: `enrichedContext.sender`,
      `enrichedContext.thread`, `enrichedContext.ragContext` a že běžná historie/sender info se už nemá zbytečně hledat
      v RAG.
- Orchestrátor provádí iterativní plánování a vykonávání kroků (viz `prompts-tools.yaml`). K dispozici jsou MCP
  nástroje, např.:
    - `sender_query_profile` – pro detailní dotaz na profil odesílatele.
    - `conversation_search_history` – pro dohledání konkrétních historických zpráv ve vlákně.
    - `knowledge_search` – pro hlubší RAG, pokud obohacený kontext nestačí.
    - Dále nástroje pro vytváření úkolů, plánování termínů, ukládání znalostí (`knowledge_store`), atp.
- Po dokončení vykonávání se `PendingTask` odstraní z fronty. Pokud dojde k chybě komunikace s LLM, je zde backoff a
  úloha se stejně smaže (neprobíhá nekonečný retry loop).

---

### 8) Kde jsou data a jak se odkazují

- MongoDB kolekce:
    - `email_messages` → `EmailMessageDocument` (stavová fronta: `NEW/INDEXED/FAILED`).
    - `conversation_threads` → `ConversationThreadDocument` (vlákna napříč kanály, metadata, `ragDocumentIds`).
    - `sender_profiles` → `SenderProfileDocument` (identifikátor, aliasy, relationship, topics, souhrny, metriky).
    - `message_links` → `MessageLinkDocument` (kanál, vazba zpráva↔vlákno↔odesílatel↔RAG).
    - `pending_tasks` → `PendingTaskDocument` (úlohy k řešení, včetně typu `EMAIL_PROCESSING`).
- Vektorové úložiště:
    - `RagDocument` pro každý chunk těla (`ragSourceType = EMAIL`), pro každý chunk přílohy (`EMAIL_ATTACHMENT`), a pro
      extrahovaný obsah odkazů (`EMAIL_LINK_CONTENT`).
    - Důležitá pole pro trasování: `sourceUri = "email://<accountId>/<messageId>"` (+ `/attachment/<index>`),
      `parentRef = messageId`, `from`, `subject`, `timestamp`, `clientId`, `projectId`.
- Vazby:
    - Vlákno (`conversation_threads`) má `senderProfileIds[]` a drží `ragDocumentIds[]` na první dokumenty e‑mailů ve
      vlákně.
    - `MessageLink` propojuje `messageId` ↔ `threadId` ↔ `senderProfileId` a volitelně ↔ `ragDocumentId`.
    - `PendingTask.context` nese jen ID a `sourceUri` – vlastní „enriched context“ se skládá dynamicky při zpracování
      dle cílů a nástrojů.

---

### 9) Jak se model dostane k vláknu, odesilateli a obsahu

- V zadání `EMAIL_PROCESSING.goal` je klíčové: plánovač má již „enriched context“ a RAG dílčí výřezy – nemá znovu
  prohledávat RAG pro základní věci.
- K hlubšímu dohledání má k dispozici MCP nástroje z `prompts-tools.yaml`:
    - `sender_query_profile` pro profil odesílatele (organizace, role, topics, agregované statistiky, shrnutí).
    - `conversation_search_history` pro cílené vyhledání v historii vlákna.
    - `knowledge_search` pro obecné dotazy do RAG (kód, dokumentace, předchozí e‑maily, meetingy atd.).
- Přímý návrat ke zdroji z RAG je přes `RagDocument.sourceUri` (`email://...`) – backend tak ví, jaké e‑mailové
  message/attachment rehydrate‑ovat.

---

### 10) Co dělá „GPU analýza“, když e‑mail prošel do analýzy

- Silný model (na GPU) v orchestrátoru plánuje a vykonává kroky podle `goal` pro `EMAIL_PROCESSING`:
    - Kontrola stáří e‑mailu vs. relevance.
    - Porovnání s aktivními požadavky/želáními uživatele (pomocí příslušného nástroje).
    - Extrakce termínů a plánování (`system_schedule_task`).
    - Identifikace akčních položek a vytvoření uživatelských úkolů (`task_create_user`), případně potvrzení pozvánek.
    - Ukládání znalostí (`knowledge_store`) pro důležitá rozhodnutí/fakta.
    - Newslettery a promo: buď vynechat, nebo jen při shodě se zájmy založit úkol / připomínku.
- Výsledek bývá prázdný plán `[]` (nic k řešení), nebo vytvoření konkrétních úkolů/poznámek/událostí.

---

### 11) Spolehlivost, výkonnost, izolace dat

- Odolnost a řízení chyb:
    - IMAP komunikační chyby pozastaví indexaci na 30 s a zprávu označí `FAILED` (fronta se nezablokuje).
    - BackgroundEngine při chybách LLM rozlišuje komunikaci vs. logické chyby a aplikuje exponenciální zpoždění jen na
      ty komunikační.
- Výkonnost:
    - Proudové zpracování (`Flow`), `buffer`, dávkování 100 ID, souběžnosti `flatMapMerge(concurrency=5)` u indexeru,
      `buffer(128)` mezi DB a indexováním.
    - Indexy v Mongo pro nejčastější dotazy (viz výše).
- Izolace tenantů:
    - Každý artefakt nese `clientId` a optional `projectId`; RAG dokumenty jsou prohledávány v mezích klienta/projektu
      dle volání.
    - „Sdílená RAG paměť“ platí jen uvnitř klienta a jeho projektů, nikdy napříč klienty.

---

### 12) Omezení a rozšiřitelnost

- Vláknování je dnes pragmatické: bez `References/In-Reply-To` se používá `messageId` + heuristika podle `subject` v
  14denním okně. Přidání pole hlaviček pro odpovědi by přesnost dále zvýšilo.
- „Skupiny odesílatelů“ nejsou první‑třídní entita; praxe je přes `SenderProfile.topics` a `relationship`. Lze rozšířit
  o explicitní „Group“ model, pokud bude třeba.
- `enrichedContext` používá ID z kvalifikačního kroku; detailní data si plánovač dotáhne přes MCP nástroje jen když jsou
  potřeba, čímž šetří výpočty.

---

### 13) Shrnutí end‑to‑end toku

1) Scheduler uloží nové `messageId` do `email_messages` jako `NEW`.
2) Continuous indexer stáhne celé zprávy, uloží tělo/přílohy/odkazy do RAG, vytvoří vazby na odesílatele a vlákno (
   včetně `MessageLink`) a označí `INDEXED`.
3) Kvalifikační služba obohatí kontext (profil + vlákno + RAG výřezy), provede rychlý filtr; při `DELEGATE` vytvoří
   `PendingTask` s minimem, ale s odkazovými ID.
4) Při volné GPU kapacitě orchestrátor podle cíle `EMAIL_PROCESSING` vyřeší potřebné kroky pomocí MCP nástrojů a
   výsledek uloží (úkoly, kalendář, znalosti). Úloha se poté odstraní.

Tím je kompletně uzavřen cyklus od načtení e‑mailu přes jeho indexaci do RAG, kvalifikaci a případnou následnou analýzu
silným modelem s plnou trasovatelností mezi entitami a dokumenty.