# JERVIS Development Roadmap — Full Implementation Task

> This document is the complete task specification for autonomous Claude Code implementation.
> Execute all phases sequentially. Each phase has detailed design in KB (`kb_search` via MCP).
> Before starting any phase, ALWAYS `kb_search` for the detailed design first.

## Context

- **Project:** Jervis AI Assistant (Kotlin Multiplatform + Python orchestrator)
- **Repository:** This repo (already cloned)
- **K8s:** `jervis` namespace, deploy via `k8s/build_*.sh` scripts
- **KB access:** Use `mcp__jervis-mcp__kb_search` to get detailed designs
- **Guidelines:** Read `CLAUDE.md` and `docs/` before making changes
- **CRITICAL:** After every code change, update relevant `docs/`. Never commit without doc updates.
- **CRITICAL:** Never create v1/v2/phases/deprecated markers. Full final implementation only.
- **CRITICAL:** KB search FIRST before any implementation. `kb_search("phase1-email-intelligence")` etc.

## Core Design Principle

**The LLM model decides everything.** No hardcoded content-type handlers, no if/elif
dispatchers for specific document types. The qualification agent (LLM with tools) analyzes
ALL content generically — invoices, job offers, medical reports, contracts, anything —
using KB conventions, cross-source matching, and reasoning.

Business rules are stored in KB as conventions (e.g., "newsletters = auto-DONE",
"invoices from X = urgent"), not hardcoded in code.

Specialized agents (coding, financial, communication, etc.) handle specific CAPABILITIES,
not specific CONTENT TYPES. The orchestrator's decomposer routes work to the right agent
based on what needs to be done, not what type of document triggered it.

## Pre-implementation

1. Read `CLAUDE.md` for project conventions
2. Read `docs/architecture.md`, `docs/implementation.md`, `docs/structures.md`
3. `kb_search("master-implementation-plan")` — get the full roadmap with all phases
4. `kb_search("jervis-master-plan-2026-04")` — get user requirements and context
5. `kb_search("gap-analysis-2026-04-02")` — understand current gaps

## User Context

Jan Damek — CEO & Senior Software Architect, Mazlušek s.r.o.
- Domains: mazlusek.com/eu/cz (company, serves all contracts)
- Contracts: MMB (~115k CZK/month), Commerzbank via Titan (MD 6k CZK/day K4)
- Active freelancer (guru.com etc.)
- Jervis = personal AI manager treating user as CEO/Senior Architect

---

## Phase 1: Email Intelligence & Multi-Client Qualification

**KB detail:** `kb_search("phase1-email-intelligence")`
**Priority:** URGENT — blocks Phase 4, 6, 7
**Estimated effort:** 3-5 days

### What to build

#### 1.1 Client Resolution Engine
**New file:** `backend/server/src/main/kotlin/com/jervis/email/ClientResolver.kt`

- Input: EmailMessageIndexDocument
- Output: List<ResolvedClient> (clientId + confidence)
- Resolution pipeline (ordered by confidence):
  1. Explicit sender→client mapping (from ConnectionDocument.senderClientMappings)
  2. Domain→client mapping (from ConnectionDocument.domainClientMappings)
  3. Thread history (previous emails in same thread → same client)
  4. Content keywords → KB search → find client by project/contract names
  5. KB entity lookup (search sender name/email in KB)
  6. Default: connection's configured client (fallback)
- Each step returns confidence. Multiple clients possible → create task for each.

**Modify:** `backend/server/src/main/kotlin/com/jervis/connection/ConnectionDocument.kt`
- Add: `senderClientMappings: Map<String, String>` (email/pattern → clientId)
- Add: `domainClientMappings: Map<String, String>` (domain → clientId)

#### 1.2 LLM-Driven Qualification Pipeline

**Design principle:** NO content-type enum, NO hardcoded handlers. The qualification
agent (LLM with CORE tools) handles ALL content types generically.

**File:** `backend/service-orchestrator/app/unified/qualification_handler.py`

The qualification agent:
1. Receives KB analysis results (summary, entities, urgency, suggested_actions)
2. Checks KB conventions (`kb_search "conventions rules"`) — applies learned rules
3. Performs cross-source matching (search KB for related entities, invoices, threads)
4. Decides: DONE / QUEUED / URGENT_ALERT / CONSOLIDATE + priority score
5. Prepares context and suggested approach for the orchestrator

Business rules (what to do with newsletters, invoices, job offers) are stored in KB
as conventions, not in code. The model reads them via `kb_search` and applies them.

#### 1.3 Kotlin→Python /qualify Wiring

**Modify:** `KtorRpcServer.kt` `/internal/kb-done` callback
- After KB processing completes, dispatch to Python `/qualify` endpoint
- Python qualification agent analyzes and calls back to `/internal/qualification-done`

**Modify:** `PythonOrchestratorClient.kt`
- Add `qualify(QualifyRequestDto)` method

#### 1.4 Seed KB Conventions
Store via `kb_store`:
- User skill profile: Kotlin, KMP, Spring Boot, Python, TypeScript, AI/ML, Architecture
- Known sender→client mappings for existing contacts
- Qualification conventions (newsletters=DONE, VIP senders=URGENT, etc.)
- Active contracts and rates

### Testing checklist
- [ ] Multi-client email → correct client resolution
- [ ] Job offer email → qualification agent creates USER_TASK with analysis (from KB conventions)
- [ ] Invoice PDF → qualification agent flags urgency based on due date (from content analysis)
- [ ] Newsletter → auto-DONE (from KB convention, not hardcoded)
- [ ] Unknown sender → default client fallback

---

## Phase 2: KILO Coding Agent

**KB detail:** `kb_search("phase2-kilo-agent")`
**Priority:** HIGH — can be done in parallel with Phase 1
**Estimated effort:** 2-3 days

### What to build

#### 2.1 KILO Container
**New file:** `k8s/kilo-agent/Dockerfile`

```dockerfile
FROM python:3.12-slim
RUN apt-get update && apt-get install -y git nodejs npm curl openssh-client
RUN pip install aider-chat httpx
# aider uses OpenRouter API (OpenAI-compatible)
ENV OPENAI_API_BASE=https://openrouter.ai/api/v1
WORKDIR /workspace
ENTRYPOINT ["aider"]
```

**New file:** `k8s/app_kilo_agent.yaml` — K8s Job template
- Same pattern as Claude agent job
- Resources: 512Mi RAM, 500m CPU (no GPU — cloud LLM)
- Timeout: 1800s
- PVC mount: same workspace as Claude
- Env: OPENROUTER_API_KEY, KILO_MODEL from configmap

**New file:** `k8s/build_kilo.sh` — build script

#### 2.2 Agent Router Enhancement
**Modify:** `backend/service-orchestrator/app/agents/specialists/code_agent.py`

Add routing logic:
- Check client's `maxOpenRouterTier`:
  - NONE/FREE → KILO
  - PAID/PREMIUM → Claude
- Check task complexity estimate:
  - Simple (< 5k estimated tokens) → KILO
  - Complex → Claude
- User explicit override: "use KILO" / "use Claude" in task description
- Fallback: KILO fails (non-zero exit or timeout) → re-dispatch to Claude with same task context

#### 2.3 KILO Model Configuration
**Modify:** `k8s/configmap.yaml`
- Add: `KILO_MODEL: "qwen/qwen3.6-plus:free"`
- Add: `KILO_FALLBACK_MODEL: "nvidia/nemotron-3-super-120b-a12b:free"`
- Add: `KILO_TIMEOUT_S: "1800"`

### Testing checklist
- [ ] Simple task dispatched to KILO → completes successfully
- [ ] Complex task → routes to Claude
- [ ] KILO timeout → escalates to Claude
- [ ] Both can work on different branches concurrently

---

## Phase 3: Meeting Helper

**KB detail:** `kb_search("phase3-meeting-helper")`
**Priority:** HIGH — can be done in parallel
**Estimated effort:** 5-7 days

### What to build

#### 3.1 Device Registry
**New file:** `backend/server/src/main/kotlin/com/jervis/device/DeviceDocument.kt`

```kotlin
data class DeviceDocument(
    val id: ObjectId,
    val userId: String,
    val deviceId: String,  // unique device identifier
    val name: String,      // "Jan's iPhone"
    val type: DeviceType,  // IPHONE, IPAD, WATCH, ANDROID, DESKTOP
    val pushToken: String?, // APNs/FCM token
    val lastSeen: Instant,
    val capabilities: List<String>, // "helper", "voice", "notifications"
)
```

**New file:** `backend/server/src/main/kotlin/com/jervis/device/DeviceService.kt`
- Register, update, list devices
- WebSocket endpoint per device for helper streaming

#### 3.2 Live Meeting Helper Pipeline
**New file:** `backend/service-orchestrator/app/meeting/live_helper.py`

Pipeline (runs while recording active + helper enabled):
```
Audio chunk (from recording)
  → Whisper transcription (GPU VD)
  → Context accumulator (rolling 5-min window of transcript)
  → Parallel LLM analysis:
      a) Translation (if non-Czech meeting) → push to device
      b) Q&A predictor: anticipate questions → push to device
      c) Answer suggester: draft responses → push to device
  → WebSocket push to selected device
```

LLM: use local 30b (qwen3-coder-tool) for analysis — low latency, good quality.
For translation: same model, prompt includes source/target language.

#### 3.3 Mobile Helper UI
**New file:** `shared/ui-common/src/commonMain/kotlin/com/jervis/ui/meeting/MeetingHelperView.kt`

Compose UI:
- Large readable text, auto-scroll
- Color-coded sections: translation (blue), suggestion (green), warning (red)
- Timestamp for each suggestion
- "Copy to clipboard" for suggested phrases

**iOS specific:** `UIApplication.shared.isIdleTimerDisabled = true` during active helper

#### 3.4 Activation Flow
**Modify:** meeting recorder UI (desktop + mobile)
- Add toggle: "Meeting Helper"
- When enabled: show device picker (list registered devices)
- Start: connect WebSocket to selected device, start helper pipeline
- Stop: disconnect, re-enable idle timer on device

#### 3.5 WebSocket Protocol
Server → Device (JSON text frames):
```json
{"type": "translation", "text": "...", "from": "en", "to": "cs", "timestamp": "..."}
{"type": "suggestion", "text": "Suggest saying: ...", "context": "...", "timestamp": "..."}
{"type": "question_predict", "text": "They might ask: ...", "suggested_answer": "...", "timestamp": "..."}
{"type": "screen_context", "text": "On screen: ...", "hint": "...", "timestamp": "..."}
```

### MVP scope (implement this first)
- Text-only (no screen awareness)
- iPhone only (iOS WebSocket + no-sleep)
- Translation + answer suggestions
- Activate from desktop recording UI

### Testing checklist
- [ ] Start recording with helper → device receives suggestions
- [ ] English meeting → Czech translation appears on iPhone
- [ ] Helper suggests relevant answers based on conversation
- [ ] iPhone stays awake during session
- [ ] Stop recording → helper disconnects, phone returns to normal

---

## Phase 4: Financial Module

**KB detail:** `kb_search("phase4-finance")`
**Priority:** HIGH — needed for Phase 5
**Estimated effort:** 4-5 days

### What to build

#### 4.1 Data Model
**New package:** `backend/server/src/main/kotlin/com/jervis/finance/`

**FinancialDocument.kt:**
```kotlin
data class FinancialDocument(
    val id: ObjectId? = null,
    val clientId: String,
    val projectId: String? = null,
    val type: FinancialType, // INVOICE_IN, INVOICE_OUT, PAYMENT, EXPENSE, RECEIPT
    val amount: Double,
    val currency: String = "CZK",
    val amountCzk: Double, // converted if foreign currency
    val vatRate: Double? = null,
    val vatAmount: Double? = null,
    val invoiceNumber: String? = null,
    val variableSymbol: String? = null,
    val counterpartyName: String? = null,
    val counterpartyIco: String? = null,
    val counterpartyAccount: String? = null,
    val issueDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val paymentDate: LocalDate? = null,
    val status: FinancialStatus, // NEW, MATCHED, PAID, OVERDUE, CANCELLED
    val matchedDocumentId: ObjectId? = null,
    val sourceUrn: String, // email:xxx, manual, bank-import
    val description: String = "",
    val createdAt: Instant = Instant.now(),
)
```

**ContractDocument.kt:**
```kotlin
data class ContractDocument(
    val id: ObjectId? = null,
    val clientId: String,
    val projectId: String? = null,
    val counterparty: String,
    val type: ContractType, // EMPLOYMENT, FREELANCE, SERVICE
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val rate: Double,
    val rateUnit: RateUnit, // HOUR, DAY, MONTH
    val currency: String = "CZK",
    val terms: String = "",
    val status: ContractStatus, // ACTIVE, EXPIRED, TERMINATED
)
```

#### 4.2 Financial Service
**New file:** `backend/server/src/main/kotlin/com/jervis/finance/FinancialService.kt`
- CRUD for financial documents
- Auto-match: invoice VS ↔ payment VS (or amount+counterparty)
- Overdue detection: daily check, create USER_TASK for overdue invoices
- Reports: per-client summary, monthly totals, outstanding invoices

#### 4.3 Orchestrator Tools for Financial Data
**Design:** The orchestrator creates financial records via tools, NOT via hardcoded
invoice processors. When the qualification agent or orchestrator identifies financial
content (invoice, payment, receipt), it uses financial tools to create records.

Chat tools:
- `finance_summary(client_id?, period?)` — income/expense/outstanding
- `list_invoices(client_id?, status?)` — list financial documents
- `record_payment(amount, vs, account?)` — manual payment entry
- `record_invoice(data)` — create invoice record from extracted data
- `list_contracts(client_id?)` — active contracts

#### 4.4 Seed Data
Store initial contracts:
- MMB: monthly 115k CZK net, employment type
- Commerzbank/Titan: MD 6k CZK K4, freelance, daily rate

### Testing checklist
- [ ] Orchestrator agent extracts invoice data → creates FinancialDocument via tool
- [ ] Bank statement pasted in chat → agent parses → payment created → matched to invoice
- [ ] Overdue invoice → USER_TASK alert
- [ ] "přehled financí za březen" → correct summary
- [ ] Per-client profitability report

---

## Phase 5: Time & Capacity Management

**KB detail:** `kb_search("phase5-time-capacity")`
**Priority:** MEDIUM — depends on Phase 4
**Estimated effort:** 2-3 days

### What to build

#### 5.1 Time Tracking
**New package:** `backend/server/src/main/kotlin/com/jervis/timetracking/`

**TimeEntryDocument.kt:**
```kotlin
data class TimeEntryDocument(
    val id: ObjectId? = null,
    val userId: String = "jan",
    val clientId: String,
    val projectId: String? = null,
    val date: LocalDate,
    val hours: Double,
    val description: String = "",
    val source: TimeSource, // MANUAL, MEETING, TASK, CALENDAR
    val billable: Boolean = true,
)
```

**TimeTrackingService.kt:**
- Manual entry: parse "dnes 6h MMB" from chat
- Auto-log from meetings: meeting duration → client → time entry
- Auto-log from calendar: events with client in title/description
- Weekly/monthly summaries

#### 5.2 Capacity Model
**New file:** `backend/server/src/main/kotlin/com/jervis/timetracking/CapacityService.kt`

```kotlin
data class CapacitySnapshot(
    val totalHoursPerWeek: Double = 40.0,
    val committed: Map<String, Double>, // clientId → hours/week from contracts
    val actualThisWeek: Map<String, Double>, // clientId → hours logged this week
    val availableHours: Double, // total - sum(committed)
)
```

Query: "Can I take 20h/week contract?" → check available ≥ 20, any deadline conflicts

#### 5.3 Chat Tools
Add tools:
- `log_time(client, hours, description?, date?)` — manual time entry
- `check_capacity(hours_needed?)` — capacity snapshot
- `time_summary(period?, client?)` — time report

### Testing checklist
- [ ] "dnes 6h MMB" → time entry created
- [ ] Meeting with MMB client → auto-logged
- [ ] "kolik mám volných hodin?" → capacity snapshot
- [ ] "můžu vzít 20h týdně zakázku?" → capacity check with answer

---

## Phase 6: Proactive Communication

**KB detail:** `kb_search("phase6-proactive")`
**Priority:** MEDIUM — depends on Phase 1, 4, 5
**Estimated effort:** 2-3 days

### What to build

#### 6.1 Proactive Scheduler
**File:** `backend/service-orchestrator/app/proactive/scheduler.py` (asyncio background task)

Scheduled triggers (Europe/Prague timezone):
- **Morning briefing** (daily 7:00 Mon-Fri): calendar, pending tasks, overdue invoices, unread VIP emails
- **Invoice check** (daily 9:00 Mon-Fri): scan for overdue invoices → USER_TASK
- **Weekly summary** (Monday 8:00): time, finance, task completion

#### 6.2 Notification Types
- Morning briefing → BACKGROUND chat message
- Urgent alerts → push notification (APNs) + BACKGROUND chat message
- Follow-up reminders → BACKGROUND chat message

#### 6.3 VIP Sender Detection
- KB convention: list of VIP senders (per client)
- When email from VIP → immediate push notification
- "Email from [Commerzbank PM] about [project deadline]"

### Testing checklist
- [ ] Morning briefing appears in chat at 7:00
- [ ] Overdue invoice → push notification
- [ ] Email from VIP sender → immediate alert

---

## Phase 7: Active Opportunity Search

**KB detail:** `kb_search("phase7-opportunity-search")`
**Priority:** MEDIUM — depends on Phase 1, 5
**Estimated effort:** 2-3 days

### What to build

**Design principle:** No hardcoded scoring. The qualification agent uses KB data
(skill profile, rates, capacity) to evaluate opportunities via LLM reasoning.

#### 7.1 User Skill Profile (KB Convention)
Seed KB entry with:
- Skills: Kotlin, KMP, Compose Multiplatform, Spring Boot, Java, Python, TypeScript, AI/ML
- Domains: Architecture, System Design, DevOps, K8s, Mobile (iOS/Android)
- Roles: CEO, Senior Software Architect, Full-stack Developer
- Languages: Czech (native), English (fluent), German (basic)
- Min rates: configurable per platform

#### 7.2 Qualification Agent Convention
Store KB convention: "When content looks like a work opportunity (job offer, freelance
inquiry, project proposal), analyze against user skill profile, check capacity via
check_capacity tool, evaluate rate, and create USER_TASK with recommendation."

The qualification agent reads this convention from KB and applies it — no hardcoded
opportunity scoring module needed. The LLM reasoning handles skill matching,
rate evaluation, and capacity checking natively.

#### 7.3 Integration
Any content identified as an opportunity by the qualification agent:
- Agent searches KB for skill profile and rates
- Agent checks capacity via `check_capacity` tool
- Agent creates USER_TASK with:
  - Title describing the opportunity
  - Analysis: skill match, rate evaluation, capacity status
  - Recommendation: accept/decline/negotiate

### Testing checklist
- [ ] guru.com job email → qualification agent creates scored USER_TASK
- [ ] High-match opportunity → high priority task
- [ ] Low capacity → task includes capacity warning

---

## Phase 8: Voice Cloning TTS

**KB detail:** `kb_search("phase8-voice-cloning")`
**Priority:** FUTURE — implement last
**Estimated effort:** 1-2 days

### What to build

#### 8.1 Voice Sample Pipeline
**New script:** `scripts/extract_voice_samples.py`
- Input: meeting recordings from KB
- Whisper diarization → identify user's voice segments
- Extract clean segments (no overlap, no noise)
- Output: directory of wav files + transcripts

#### 8.2 XTTS Fine-tuning
**New script:** `scripts/finetune_xtts.py`
- Input: voice samples directory
- XTTS-v2 fine-tuning (using Coqui trainer)
- Output: custom speaker model
- Deploy: copy to VD GPU, update XTTS config

#### 8.3 TTS Integration
**Modify:** TTS service config on VD
- Add speaker parameter: `default` | `jan-damek`
- API: POST /tts now accepts `speaker` field

### Testing checklist
- [ ] Voice samples extracted from meetings
- [ ] XTTS fine-tuned with user voice
- [ ] TTS produces recognizable user voice in Czech
- [ ] TTS produces recognizable user voice in English
- [ ] Watch/iPhone plays cloned voice

---

## Deployment

After each phase:
1. Update relevant `docs/` files
2. `kb_store` any new conventions or findings
3. Deploy via `k8s/build_*.sh` scripts (NEVER gradle/docker/kubectl directly)
4. Verify in logs: `kubectl -n jervis logs deploy/jervis-<service>`
5. Test via chat or MCP tools

## Important files to read first

- `CLAUDE.md` — project conventions
- `docs/architecture.md` — system architecture
- `docs/structures.md` — data processing pipeline
- `docs/knowledge-base.md` — KB schema
- `docs/orchestrator-detailed.md` — orchestrator reference
- `backend/service-orchestrator/app/unified/qualification_handler.py` — current qualification
- `backend/server/src/main/kotlin/com/jervis/email/EmailContinuousIndexer.kt` — email pipeline
- `backend/server/src/main/kotlin/com/jervis/task/BackgroundEngine.kt` — task processing
- `backend/service-orchestrator/app/agents/specialists/code_agent.py` — coding agent dispatcher
