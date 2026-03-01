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

### 14. E6-S4: Dedicated GPU routing mode — ⏸️ On hold
**Pozastaveno:** Druhá P40 měla HW problém (overload na sběrnici). Router zůstává v AUTO režimu s jednou P40. DEDICATED mode odstraněn z codebase.

---

## ~~LOW — Phase 3 Foundation~~ ✅ DONE (2026-02-26, except EPIC 15)

### ~~15. EPIC 11: Slack / Teams / Discord~~ ✅
**Hotovo:** SLACK/MICROSOFT_TEAMS/DISCORD providery + CHAT_READ/CHAT_SEND capabilities v ConnectionDtos. `ChatContinuousIndexer` s polling stuby pro Slack Web API, MS Graph, Discord Bot API. Priority boosting (mentions, DMs) a sentiment analýza (reactions). `ChatReplyService` wired do `ActionExecutorService` pro CHAT_REPLY approval dispatch.

### ~~16. EPIC 12: Google Calendar~~ ✅
**Hotovo:** GOOGLE_CALENDAR provider + CALENDAR_READ/CALENDAR_WRITE capabilities. `CalendarService` (Kotlin) s event fetching, availability kalkulací (free/busy sloty), event creation, a today context pro system prompt. `calendar_integration.py` (Python) pro orchestrator scheduling context a autonomous work mode detection.

### ~~17. EPIC 13: System Prompt Self-Evolution~~ ✅
**Hotovo:** MongoDB entity `PromptSectionDocument` (4 kolekce: system_prompt_sections, learned_behaviors, user_corrections, prompt_version_history) + repositories. `PromptEvolutionService` (Kotlin) s CRUD, version history, rollback. `behavior_learning.py` (Python) — LLM denial analýza + approval pattern detection. `user_corrections.py` — correction detection heuristics + LLM extrakce.

### 18. EPIC 15: Apple Watch App
- S1-S5: watchOS companion app, quick notes, approval, record, complications
- **Stav:** Vyžaduje nativní Swift/watchOS — nelze implementovat v tomto repo

### ~~19. EPIC 16: Brain Workflow Structure~~ ✅
**Hotovo:** `BrainWorkflowService` (Kotlin) — BrainIssueType→JIRA mapping s label enforcement (FINDING/REVIEW/LEARNING → Task + semantic labels). Confluence space auto-creation pro všechny `ConfluenceSpaceSection` entries. DAILY_REPORT idle task wired do `IdleTaskRegistry` a `BackgroundEngine` s orchestrator promptem.

### ~~20. EPIC 17: Environment Agent Enhancement~~ ✅
**Hotovo:** `EnvironmentAgentService` (Kotlin) — dispatch routing dle job type. `validateDeployment()` — pod health check + error log collection + warning events. `fetchPodLogs()` a `describePod()` — debug assistance přes Fabric8 K8s client. Namespace overview.

---

## Přehled bodů

| Priorita | Počet bodů | Stav |
|----------|-----------|------|
| CRITICAL | 5 | ✅ Vše hotovo |
| HIGH | 5 | ✅ Vše hotovo |
| MEDIUM | 4 | ✅ Vše hotovo |
| LOW | 6 | ✅ 5/6 hotovo (EPIC 15 Apple Watch vyžaduje nativní Swift) |
| **Celkem** | **20** | **19/20 hotovo** |

---

*Aktualizováno 2026-02-26 po implementaci všech položek (kromě EPIC 15 Apple Watch).*

---

## Architecture Upgrade (2026-03-01)

### 21. Hierarchická dekompozice tasků (TaskDocument hierarchy)
**Stav:** ✅ Hotovo
- TaskStateEnum: nové stavy BLOCKED, PLANNING
- TaskDocument: nová pole parentTaskId, blockedByTaskIds, phase, orderInPhase
- WorkPlanExecutor loop v BackgroundEngine (15s interval) — automatický unblock, completion
- DTOs: childCount, completedChildCount, phase

### 22. Unified Chat Stream (BACKGROUND + ALERT zprávy)
**Stav:** ✅ Hotovo
- MessageRole: BACKGROUND, ALERT
- ChatRpcImpl: pushBackgroundResult(), pushUrgentAlert(), isUserOnline()
- OrchestratorStatusHandler: push do chatu při done/error
- KbResultRouter: urgent push do chatu
- ChatContextAssembler: mapování nových rolí pro LLM

### 23. Intent Router + Cloud-first chat
**Stav:** ✅ Hotovo (feature-flagged, `use_intent_router=False`)
- Dvou-fázová klasifikace: regex fast-path (0ms) + LLM fallback (P40, ~2-3s)
- 6 kategorií: DIRECT, RESEARCH, BRAIN, TASK_MGMT, COMPLEX, MEMORY
- Per-category fokusované prompty (prompts/ adresář) a tool sety (3-13 tools místo 26)
- CHAT_CLOUD queue pro cloud-first routing (claude-sonnet-4 → gpt-4o → p40)
- Posílená izolace klientů/projektů v promptech

### 24. Planning Agent (work plan dekompozice)
**Stav:** ✅ Hotovo
- create_work_plan chat tool: fáze + závislosti → hierarchie child tasků
- Kotlin endpoint POST /internal/tasks/create-work-plan
- Integrace s approval flow a write consent
