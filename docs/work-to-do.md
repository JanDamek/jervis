# JERVIS EPIC Plan — Work To Do

> Stav k 2026-02-26, po kompletním auditu codebase proti epic-plan-autonomous.md

---

## CRITICAL — Blokuje autonomní provoz

### 1. Wiring: E7 idle task services → BackgroundEngine
**Problém:** VulnerabilityScannerService, KbConsistencyCheckerService, LearningEngineService, DocFreshnessService existují jako `@Service` beany, ale BackgroundEngine je **nevolá**. Idle review loop dělá jen brain JIRA review.
**Řešení:** Rozšířit `BackgroundEngine.runIdleReviewLoop()` — konzultovat `IdleTaskRegistry.getNextIdleTask()` a dispatchnout příslušný service.
**Soubory:** `BackgroundEngine.kt`, `IdleTaskRegistry.kt`

### 2. Wiring: E8 DeadlineTrackerService → periodický scheduler
**Problém:** `DeadlineTrackerService` + `ProactivePreparationService` existují ale nikdo je periodicky nevolá. Žádný scheduler neskenuje KB/JIRA na deadlines.
**Řešení:** Přidat deadline scan do BackgroundEngine scheduler loop nebo jako idle task.
**Soubory:** `BackgroundEngine.kt`, `DeadlineTrackerService.kt`

### 3. Wiring: E14-S1 fact_checker → agentic loop
**Problém:** `fact_check_response()` je kompletně naimplementovaný ale **nikdy se nevolá**. Měl by běžet jako post-processing po každé LLM odpovědi.
**Řešení:** Zavolat `fact_check_response()` v chat handler po finální odpovědi (aspoň v FOREGROUND módu). Přidat confidence badge do response.
**Soubory:** `handler_agentic.py` nebo `handler_streaming.py`, `fact_checker.py`

### 4. Wiring: E10-S3 FilteringRulesService → qualifier pipeline
**Problém:** `FilteringRulesService.evaluate()` má kompletní logiku (source type matching, condition evaluation včetně regexu) ale **qualifier ji nevolá**.
**Řešení:** Přidat volání `filteringRulesService.evaluate()` do `KbResultRouter` před/po actionability assessment.
**Soubory:** `KbResultRouter.kt`, `FilteringRulesService.kt`

### 5. E5-S2/S3/S4/S5: Action dispatch stubs → reálné implementace
**Problém:** `dispatchEmailAction()`, `dispatchJiraAction()`, `dispatchPrAction()`, `dispatchConfluenceAction()` v ActionExecutorService jsou **stuby** — approval gate funguje, ale akce se reálně neprovádí.
**Řešení:** Implementovat reálné volání:
- Email: `EmailServiceImpl.kt` hází `UnsupportedOperationException("Write operations not allowed yet")` → implementovat SMTP send
- Client JIRA: `AtlassianApiClient.kt` (1903 řádků) **už má** `createJiraIssue()`, `updateJiraIssue()`, `transitionJiraIssue()` → propojit s orchestrátorem (není wired do executor.py)
- PR/MR: GitService je jen read-only interface → přidat write operations (GitHub/GitLab API)
- Confluence: `AtlassianApiClient.kt` **už má** `createConfluencePage()`, `updateConfluencePage()` → propojit
**Soubory:** `ActionExecutorService.kt`, `executor.py`, `AtlassianApiClient.kt`, `EmailServiceImpl.kt`
**Poznámka:** Atlassian write ops existují ale nejsou propojeny s orchestrátorem. Stačí wiring, ne nová implementace.

---

## HIGH — Funkční mezery v "hotových" EPICech

### 6. E9-S1: Topic Tracker (implementace)
**Problém:** DTOs (`ConversationTopic`, `TopicSummary`) existují, ale žádný topic detection service. Konverzace nemají topic metadata.
**Řešení:** Vytvořit `TopicTracker` v Python orchestrátoru — LLM-based topic detection z uživatelských zpráv, update topic metadata per konverzace.
**Soubory:** Nový `app/chat/topic_tracker.py`

### 7. E9-S2: Conversation Memory Consolidation
**Problém:** DTOs existují ale žádný service pro topic-aware sumarizaci. Konverzace používají jen rolling block summaries.
**Řešení:** Vytvořit memory consolidation service — při dosažení context limitu konsolidovat per-topic summaries místo generic bloků.
**Soubory:** Nový `app/memory/consolidation.py`

### 8. E9-S3: Multi-Intent Decomposition
**Problém:** `DecomposedIntent` + `SingleIntent` DTOs existují ale žádný intent decomposer. Když user pošle "udělej X, Y a Z", orchestrátor to zpracuje jako jeden intent.
**Řešení:** Přidat pre-processing krok v chat handleru — detekce multi-intentů, rozklad na jednotlivé akce.
**Soubory:** Nový `app/chat/intent_decomposer.py`, úprava `handler_agentic.py`

### 9. E4-S3+S5: Approval queue + analytics persistence
**Problém:** Approval queue i statistiky jsou jen v `ConcurrentHashMap` (in-memory). Po restartu serveru se ztratí pending approvals i historické statistiky. Žádné UI pro trust suggestions.
**Řešení:**
- Vytvořit MongoDB collection `approval_queue` pro pending approvals (ApprovalQueueItem)
- Vytvořit MongoDB collection `approval_analytics` pro rozhodnutí (approve/deny ratios)
- Přidat `ApprovalQueueRepository` s Spring Data
- Přidat endpoint pro UI (trust suggestions dialog)
**Soubory:** `ActionExecutorService.kt`, nový `ApprovalQueueRepository.kt`, nová MongoDB collection

### 10. E14-S2: Source Attribution
**Problém:** `SourceAttribution` DTO existuje ale žádná implementace nepřipojuje zdroje k jednotlivým segmentům odpovědi.
**Řešení:** Při KB search výsledcích propagovat source URN do response metadata → UI zobrazí "[zdroj: wiki/architecture.md]" u relevantních částí.
**Soubory:** `executor.py` (KB search), chat handler, response DTOs

---

## MEDIUM — UI mezery, nice-to-have

### 11. E2-S7: Pipeline Monitoring Dashboard (UI)
**Problém:** Backend loguje pipeline metriky ale žádná Compose obrazovka je nezobrazuje. Chybí funnel view (Polled → Qualified → Created → Executed).
**Řešení:** Nová Compose screen `PipelineMonitoringScreen.kt` s `JAdaptiveSidebarLayout`.
**Soubory:** Nový screen v `shared/ui-common/.../screens/`

### 12. E8-S4: Deadline Dashboard Widget (UI)
**Problém:** `DeadlineDashboard` DTO + `buildDashboard()` existují, ale žádný Compose widget.
**Řešení:** Nová Compose komponenta `DeadlineDashboardWidget.kt` — color-coded urgency, countdown.
**Soubory:** Nový widget v `shared/ui-common/.../screens/`

### 13. E14-S4: Confidence Badge (UI)
**Problém:** `ResponseConfidence` se počítá v `fact_checker.py` ale nikde se nezobrazuje.
**Řešení:** Přidat confidence badge do chat message bubble (e.g. "95% verified").
**Soubory:** Chat UI components

### 14. E6-S4: Dedicated GPU routing mode
**Problém:** Ollama Router podporuje multi-backend ale nemá DEDICATED mód (GPU0=foreground, GPU1=background).
**Řešení:** Přidat routing strategy "DEDICATED" vedle "AUTO" do `router_core.py`.
**Soubory:** `router_core.py`, config

---

## LOW — Phase 3 Foundation (plánováno, DTOs hotové)

### 15. EPIC 11: Slack / Teams / Discord
- S2: Slack microservice (OAuth2, WebSocket, polling handler)
- S3: Teams microservice (MS Graph API)
- S4: Chat Source Indexer (`ChatContinuousIndexer`)
- **Stav:** DTOs + communication agent existují

### 16. EPIC 12: Google Calendar
- S1: Google Calendar OAuth2 connection
- S2: Calendar polling & indexing
- S3: Availability service
- S5: Scheduler integration (deadline + calendar)
- **Stav:** DTOs + calendar agent existují

### 17. EPIC 13: System Prompt Self-Evolution
- S1: PromptSection MongoDB service
- S2: Behavior Learning Loop (analyzovat user denials)
- S3: User Correction chat tools
- S4: Prompt version history
- **Stav:** Jen DTOs

### 18. EPIC 15: Apple Watch App
- S1-S5: watchOS companion app, quick notes, approval, record, complications
- **Stav:** Nulová implementace (vyžaduje nativní Swift/watchOS)

### 19. EPIC 16: Brain Workflow Structure
- S1: JIRA issue type/workflow enforcement
- S2: Confluence space auto-creation
- S3: Daily report generator
- **Stav:** Jen DTOs

### 20. EPIC 17: Environment Agent Enhancement
- S1: On-demand env agent K8s job dispatch
- S2: Deployment validation service
- S3: Debug assistance (kubectl logs/describe)
- **Stav:** Jen DTOs

---

## Přehled bodů

| Priorita | Počet bodů | Popis |
|----------|-----------|-------|
| CRITICAL | 5 | Wiring existujících services, action dispatch stuby |
| HIGH | 5 | Chybějící services (topic tracker, memory, attribution) |
| MEDIUM | 4 | UI komponenty, GPU routing |
| LOW | 6 | Phase 3 (celé EPICy 11-17) |
| **Celkem** | **20** | |

---

*Vygenerováno 2026-02-26 na základě hloubkového auditu codebase.*
