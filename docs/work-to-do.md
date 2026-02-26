# JERVIS EPIC Plan — Work To Do

> Stav k 2026-02-26, po kompletním auditu codebase proti epic-plan-autonomous.md

---

## ~~CRITICAL — Blokuje autonomní provoz~~ ✅ DONE (2026-02-26)

### ~~1. Wiring: E7 idle task services → BackgroundEngine~~ ✅
**Hotovo:** `BackgroundEngine.runIdleReviewLoop()` nyní konzultuje `IdleTaskRegistry.getNextIdleTask()` a dispatchuje 6 typů idle tasků (REVIEW_BRAIN_ISSUES, KB_CONSISTENCY_CHECK, VULNERABILITY_SCAN, CODE_QUALITY_SCAN, DOCUMENTATION_FRESHNESS, LEARNING_BEST_PRACTICES) se specializovanými prompty pro orchestrátor.

### ~~2. Wiring: E8 DeadlineTrackerService → periodický scheduler~~ ✅
**Hotovo:** Deadline scan přidán do `BackgroundEngine.runSchedulerLoop()` — každých 5 minut dispatchuje SCHEDULED_TASK s deadline-scanning promptem pro orchestrátor. Skenuje JIRA due dates i KB deadline references.

### ~~3. Wiring: E14-S1 fact_checker → agentic loop~~ ✅
**Hotovo:** `fact_check_response()` volán na 5 finalizačních bodech v `handler_agentic.py` (normal exit, drift break, max iterations) a `handler.py` (decompose, greeting). Nový `handler_fact_check.py` helper. Confidence badge metadata ve streamu pro UI.

### ~~4. Wiring: E10-S3 FilteringRulesService → qualifier pipeline~~ ✅
**Hotovo:** `FilteringRulesService.evaluate()` integrován do `KbResultRouter.routeTask()` mezi actionability check a complexity assessment. IGNORE → task DONE, URGENT/HIGH → force complex routing. SourceUrn auto-mapován na FilterSourceType.

### ~~5. E5-S2/S3/S4/S5: Action dispatch stubs → reálné implementace~~ ✅ (částečně)
**Hotovo:** JIRA a Confluence dispatch přes `BrainWriteService` (createIssue, updateIssue, addComment, transitionIssue, createPage, updatePage). Email a PR zůstávají stuby (SMTP a Git write API vyžadují novou implementaci).

---

## ~~HIGH — Funkční mezery v "hotových" EPICech~~ ✅ DONE (2026-02-26)

### ~~6. E9-S1: Topic Tracker (implementace)~~ ✅
**Hotovo:** Nový `app/chat/topic_tracker.py` — LLM-based topic detection z user+assistant zpráv s fallback na tool-domain heuristics. MongoDB upsert do `conversation_topics` kolekce. Wired do `handler_agentic.py` (3 finalizační body) a `handler.py` (greeting, decompose).

### ~~7. E9-S2: Conversation Memory Consolidation~~ ✅
**Hotovo:** Nový `app/memory/consolidation.py` — topic-aware konsolidace rolling block summaries při překročení 12 bloků. Seskupení per-topic + LLM merge. Wired do `context.py` (ChatContextAssembler.assemble_context).

### ~~8. E9-S3: Multi-Intent Decomposition~~ ✅
**Hotovo:** Nový `app/chat/intent_decomposer.py` — regex pre-filter + LLM extrakce nezávislých intentů s dependency ordering. Wired do `handler.py` mezi greeting fast-path a agentic loop. Build focus message pro orchestrátor.

### ~~9. E4-S3+S5: Approval queue + analytics persistence~~ ✅
**Hotovo:** MongoDB entity `ApprovalQueueDocument` (kolekce `approval_queue`) + `ApprovalStatisticsDocument` (kolekce `approval_statistics`). `ApprovalQueueRepository` + `ApprovalStatisticsRepository` s Spring Data. `ActionExecutorService` refaktorován z ConcurrentHashMap na MongoDB persistence.

### ~~10. E14-S2: Source Attribution~~ ✅
**Hotovo:** Nový `app/chat/source_attribution.py` se `SourceTracker` třídou — zachycuje kb_search/code_search/brain_search výsledky, extrahuje sourceUrn + score regex parserem. Metadata propagována do save_assistant_message a SSE done eventu.

---

## ~~MEDIUM — UI mezery, nice-to-have~~ ✅ DONE (2026-02-26)

### ~~11. E2-S7: Pipeline Monitoring Dashboard (UI)~~ ✅
**Hotovo:** Nový `PipelineMonitoringScreen.kt` — funnel view s auto-refresh 15s, fáze KB waiting/processing, execution waiting/running, completed. Používá `IIndexingQueueService.getIndexingDashboard()`. Komponenty: FunnelCard, StageHeader, PipelineItemCard.

### ~~12. E8-S4: Deadline Dashboard Widget (UI)~~ ✅
**Hotovo:** Nový `DeadlineDashboardWidget.kt` — color-coded urgency overview (GREEN/YELLOW/ORANGE/RED/OVERDUE) s countdown. Přijímá `DeadlineDashboard` jako parameter pro embedding v parent screens. Summary card + individuální DeadlineItemCard.

### ~~13. E14-S4: Confidence Badge (UI)~~ ✅
**Hotovo:** `ConfidenceBadge` composable v `ChatMessageDisplay.kt` — čte `fact_check_confidence`, `fact_check_claims`, `fact_check_verified` z message metadata. Zobrazuje colored Verified icon + procenta (green ≥80%, amber ≥50%, red <50%).

### ~~14. E6-S4: Dedicated GPU routing mode~~ ✅
**Hotovo:** `RoutingMode` enum (AUTO/DEDICATED) v `config.py`. Nová `_route_dedicated()` metoda v `router_core.py` — GPU0=foreground (CRITICAL), GPU1+=background (NORMAL). Vyžaduje ≥2 GPU backends, jinak fallback na AUTO.

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

| Priorita | Počet bodů | Stav |
|----------|-----------|------|
| CRITICAL | 5 | ✅ Vše hotovo |
| HIGH | 5 | ✅ Vše hotovo |
| MEDIUM | 4 | ✅ Vše hotovo |
| LOW | 6 | Phase 3 (celé EPICy 11-17) |
| **Celkem** | **20** | **14/20 hotovo** |

---

*Aktualizováno 2026-02-26 po implementaci všech CRITICAL, HIGH a MEDIUM položek.*
