# EPIC Plán — Jervis Autonomous AI Assistant

**Verze:** 1.0
**Datum:** 2026-02-25
**Status:** Living document — aktualizovat při každém milníku

---

## Vize

Jervis je **lokálně běžící autonomní AI asistent** pro správu projektů, kódu a znalostí.
Primární princip: **local-first** (Ollama + vlastní GPU), cloud jako eskalační fallback.

---

## Stav implementace — přehled EPIC bloků

| # | EPIC | Status | Poznámka |
|---|------|--------|----------|
| 1 | Guidelines & Rules Engine | ✅ Done | Produkční, resolver + cache |
| 2 | Foreground Chat (Interactive) | ✅ Done | SSE streaming, 45 nástrojů, drift detection |
| 3 | Background Task System | ✅ Done | Autonomní loop, 30 nástrojů, escalation |
| 4 | Memory Agent (Affairs) | ✅ Done | LQM 3-layer cache, affair lifecycle |
| 5 | Knowledge Base & Indexing | ✅ Done | Weaviate + ArangoDB, continuous indexers |
| 6 | Coding Agent Dispatch | ✅ Done | Aider/OpenHands/Claude/Junie selection |
| 7 | Ollama Router (GPU/CPU) | ✅ Done | 2-level priority, auto-reservation |
| 8 | Multi-Agent Delegation | 🔶 Feature-flagged | 7-node graph, 19 specialist agents, DAG exec |
| 9 | KB API Extensions | 📋 Planned | ingest-immediate, affairs endpoints |
| 10 | Dual-GPU Scaling | 📋 Planned | Druhá P40, routing, concurrent inference |
| 11 | Watch/Wearable UI | 📋 Planned | UI připraveno, deployment pending |
| 12 | Produkční stabilizace | 🔄 Ongoing | Testy, monitoring, metrics |

---

## EPIC 1 — Guidelines & Rules Engine ✅

**Cíl:** Každý projekt má konfigurovatelná pravidla a guidelines, které orchestrátor injektuje do promptů.

**Implementováno:**
- `GuidelinesService` (Kotlin) — CRUD, per-project pravidla
- `guidelines_resolver.py` (Python) — async resolver s cache
- `format_guidelines_for_prompt()` — injekce do system promptu
- Project rules: `require_review`, `require_tests`, `auto_use_anthropic/openai/gemini`
- Polling interval konfigurace per-connection

**Soubory:** `shared/common-dto/.../guidelines/`, `backend/server/.../service/guidelines/`, `backend/service-orchestrator/app/context/guidelines_resolver.py`

---

## EPIC 2 — Foreground Chat (Interactive) ✅

**Cíl:** Real-time interaktivní chat s uživatelem přes SSE streaming.

**Implementováno:**
- `handler_agentic.py` — SSE streaming loop (40 chars/chunk)
- Dynamický tier selection podle velikosti kontextu
- Drift detection (`chat/drift.py`) — prevence tool loop
- 45 nástrojů (37 base + 8 chat-specific)
- Scope switching s permission reset
- Disconnect detection (asyncio.Event)
- Focus reminders mezi iteracemi
- Max 6 iterací (3 pro dlouhé zprávy >8k chars)
- Message decomposition (`handler_decompose.py`) pro vstupy >8k chars

**Konfigurace:**
```python
chat_max_iterations = 6
chat_max_iterations_long = 3
decompose_threshold = 8000
```

**Soubory:** `app/chat/handler_agentic.py`, `app/chat/handler_streaming.py`, `app/chat/handler_tools.py`, `app/chat/handler_context.py`, `app/chat/drift.py`

---

## EPIC 3 — Background Task System ✅

**Cíl:** Autonomní zpracování úkolů na pozadí bez přímé interakce s uživatelem.

**Implementováno:**
- `handler.py` — agentic loop (max 15 iterací)
- 30-tool subset (bez ask_user, memory, list_affairs)
- Escalation tracking — auto-eskalace local → cloud
- Context overflow detection (Ollama "Operation not allowed" pattern)
- Centralizovaný `estimate_tokens()` pro odhad kontextu
- `detect_tool_loop()` — prevence opakovaných volání
- Search rate limiting (max 3× stejný search tool)
- Progress reporting přes `kotlin_client`
- MongoDB persistence výsledků

**Entry point:** `POST /orchestrate/v2`

**Konfigurace:**
```python
background_max_iterations = 15
_MAX_ESCALATION_RETRIES = 3
_BG_MAX_TIER = ModelTier.LOCAL_XLARGE
```

**Soubory:** `app/background/handler.py`, `app/background/escalation.py`, `app/background/tools.py`

---

## EPIC 4 — Memory Agent (Affairs) ✅

**Cíl:** Kontextové řízení konverzací — affair-based memory s 3-vrstvým cache.

**Implementováno:**
- **Affair model:** id, title, summary, status, topics, key_facts, pending_actions, messages
- **Životní cyklus:** create → park (LLM summarizace) → resume → resolve
- **LQM (Local Quick Memory):**
  - Layer 1 (Hot): `dict[str, Affair]` — aktivní/parkované affairs (max 100)
  - Layer 2 (Buffer): `asyncio.Queue` — pending KB writes s prioritou
  - Layer 3 (Warm): `LRUCache` — nedávné KB výsledky (TTL 300s, max 1000)
- **Context switching:** LLM-based detection (CONTINUE/SWITCH/AD_HOC/NEW_AFFAIR), confidence threshold 0.7
- **Context composition:** Token-budgeted (40% aktivní affair, 10% parkované, 15% user kontext)
- **Nástroje:** `memory_store`, `memory_recall`, `list_affairs`

**Konfigurace:**
```python
affair_max_hot = 100
lqm_max_warm_entries = 1000
lqm_warm_ttl_seconds = 300.0
lqm_write_buffer_max = 500
context_switch_confidence_threshold = 0.7
```

**Soubory:** `app/memory/models.py`, `app/memory/lqm.py`, `app/memory/affairs.py`, `app/memory/composer.py`, `app/memory/context_switch.py`, `app/memory/agent.py`

---

## EPIC 5 — Knowledge Base & Indexing ✅

**Cíl:** Duální úložiště znalostí (vektor + graf) s continuous indexing.

**Implementováno:**
- **Weaviate:** RAG, vektorové embeddingy, sémantické vyhledávání, chunking
- **ArangoDB:** graf nody, hrany, traversal
  - `c{clientId}_nodes` — heterogenní kolekce (Users, Jira, files, commits, Confluence…)
  - `c{clientId}_edges` — vztahy
  - `c{clientId}_graph` — pojmenovaný graf pro traversal
- **Continuous indexers:**
  - Polling pipeline (Jira, GitHub, GitLab, Slack, email)
  - Git indexing (separátní workspace — nikdy se nedotýká orchestrátor workspace)
  - Vision processing (2-stage analýza dokumentů)
  - Transcript correction (Whisper + Ollama korekce)
- **Task outcome ingestion:** LLM summarizace → summary, key_facts, pending_actions, urgency

**Soubory:** `docs/knowledge-base.md` (SSOT), `backend/server/.../service/kb/`, indexing pipeline

---

## EPIC 6 — Coding Agent Dispatch ✅

**Cíl:** Inteligentní výběr a dispatch coding agentů přes K8s Jobs.

**Implementováno:**

| Agent | Provider | Model | Timeout | Use Case |
|-------|----------|-------|---------|----------|
| Aider | Ollama | qwen3-coder:30b | 10min | Quick fixes |
| OpenHands | Ollama | qwen3-coder:30b | 30min | Medium/complex |
| Claude | Anthropic | claude-3-5-sonnet | 30min | CRITICAL úkoly |
| Junie | JetBrains | claude-3-5-sonnet | 20min | Premium (vyžaduje approval) |

- **Selection logic:** Complexity-based (SIMPLE→Aider, MEDIUM/COMPLEX→OpenHands, CRITICAL→Claude)
- **K8s Job runner:** dispatch, monitoring, TTL cleanup (300s)
- **Max concurrent orchestrations:** 5

**Soubory:** `app/tools/executor.py`, `app/tools/kotlin_client.py`

---

## EPIC 7 — Ollama Router (GPU/CPU Priority) ✅

**Cíl:** Inteligentní routing LLM požadavků mezi GPU a CPU backend.

**Implementováno:**
- **2-level priority:**
  - CRITICAL (0): Foreground orchestrátor — reserved GPU, auto-reserve/release
  - NORMAL (1): Background, KB ingest, correction — GPU if free, else CPU
- **Auto-reservation:** CRITICAL request → reserve GPU (60s idle timeout, 10min max)
- **Model tier selection:** Dynamicky podle velikosti kontextu
  - 0–4k → qwen3-coder-tool-4k:30b
  - 4k–16k → qwen3-coder-tool-16k:30b
  - 16k–64k → qwen3-coder-tool-64k:30b
  - 64k+ → qwen3-coder-tool-256k:30b
- **Cloud escalation:** Volitelná, řízená project rules

**Infrastruktura:**
```
Port 11430 — Router
Port 11434 — GPU backend (P40 24GB)
Port 11435 — CPU backend (200GB RAM)
```

**Soubory:** `app/llm/provider.py`, `app/config.py`

---

## EPIC 8 — Multi-Agent Delegation 🔶

**Cíl:** Paralelní delegace úkolů na specializované agenty.

**Status:** Implementováno, feature-flagged (vypnuto).

**Implementováno:**
- 7-node delegation graph (alternativa k legacy 14-node)
- 19+ specialist agents v registru (coding, PM, communication, DevOps, security…)
- DAG-based parallel execution via `DAGExecutor`
- Token budgeting per depth (48k → 16k → 8k → 4k), max depth 4
- Nodes: `plan_delegations` → `execute_delegation` → `synthesize`

**Feature flags:**
```python
use_delegation_graph = False    # Master switch
use_specialist_agents = False   # Agent registry
use_dag_execution = False       # Parallel DAG
use_procedural_memory = False   # Procedural memory layer
```

**Aktivace:** Vyžaduje stabilizaci background handleru a testování s reálnými úkoly.

---

## EPIC 9 — KB API Extensions 📋

**Cíl:** Přímé API endpointy pro immediate ingest a affair persistence.

**Plánováno:**
- `POST /api/v1/ingest-immediate` — synchronní ingest do KB (bypass polling pipeline)
- `POST /api/v1/affairs` — CRUD endpointy pro affairs (persistence mimo LQM)
- Affair synchronizace mezi sessions (multi-device)

**Závislosti:** EPIC 4 (Memory Agent), EPIC 5 (Knowledge Base)

---

## EPIC 10 — Dual-GPU Scaling 📋

**Cíl:** Využití druhé P40 GPU pro paralelní inference.

**Plánováno:**
- Druhý Ollama backend (port 11436) na druhé P40
- Router update: round-robin nebo priority-based distribuce
- Foreground na GPU-1, background na GPU-2 (eliminace VRAM contention)
- Nebo: oba GPU pro foreground s load balancing

**Varianty:**

### Varianta A — Dedikované GPU per use-case
```
GPU-1 (P40 #1): Foreground (CRITICAL priority) — rychlá odezva
GPU-2 (P40 #2): Background + indexing (NORMAL priority) — throughput
CPU: Fallback pro overflow
```

### Varianta B — Load-balanced pool
```
GPU pool [P40 #1, P40 #2]: Obě GPU sdílí workload
Router: Least-loaded routing
CPU: Overflow fallback
```

**Doporučení:** Varianta A — jednodušší, předvídatelná latence pro uživatele.

**Závislosti:** Hardware (druhá P40 — montuje se), router update

---

## EPIC 11 — Watch/Wearable UI 📋

**Cíl:** Zjednodušené UI pro chytré hodinky.

**Status:** UI komponenty připravené v Compose Multiplatform, deployment pending.

---

## EPIC 12 — Produkční stabilizace 🔄

**Cíl:** Monitoring, testy, metriky, resilience.

**V řešení:**
- [ ] Comprehensive memory agent testy (Phase 5)
- [ ] Session memory TTL optimalizace (již konfigurovatelné přes env, default 7d)

**Dokončeno:**
- [x] Git branch validace — evidence_pack validuje `target_branch` proti KB (exact/case/suffix match)
- [x] K8s metrics-server instalace (C1)
- [x] Legacy dead code removal (~440 řádků: /orchestrate, /approve, _run_and_push, _resume_in_background, duplicate build_delegation_graph)
- [x] Tool loop detection (centralizovaný `detect_tool_loop`)
- [x] Search rate limiting (max 3× per tool per task)
- [x] Dynamic context estimation (`estimate_tokens`)
- [x] Config centralizace (magic numbers → Settings)
- [x] Executor timeout konfigurace
- [x] Stale connection warning dedup
- [x] KB purge failure logging

---

## Architektonická rozhodnutí

### 1. Local-first LLM
Ollama + vlastní GPU jako default. Cloud (Anthropic/OpenAI/Gemini) pouze při eskalaci nebo explicitním povolení v project rules. Minimalizace nákladů, maximalizace privacy.

### 2. Dual Workspace
- `git/{resourceId}/` — agent workspace (stabilní HEAD)
- `git-indexing/{resourceId}/` — indexing workspace (volně naviguje historii)
Prevence race conditions mezi indexingem a orchestrací.

### 3. Push-first komunikace
Python → Kotlin: progress callbacks (rychlé, real-time).
Kotlin → Python: polling safety-net každých 60s (detekce stuck tasků po 15 min).

### 4. Feature flags pro stabilitu
Nové subsystémy (delegation, DAG, procedural memory) za feature flags.
Aktivace až po testování na reálných úkolech.

### 5. Memory layering
Hot (RAM) → Warm (LRU) → Cold (KB). Affair lifecycle s LLM summarizací při parkování.

---

## Roadmap (orientační)

```
2026-02 ████████████████░░░░ EPIC 1-7 done, EPIC 8 flagged, EPIC 12 ongoing
2026-03 ░░░░░░░░░░░░░░░░░░░░ EPIC 10 (dual GPU), EPIC 12 (stabilizace)
2026-Q2 ░░░░░░░░░░░░░░░░░░░░ EPIC 8 (unflag delegation), EPIC 9 (KB API)
2026-Q3 ░░░░░░░░░░░░░░░░░░░░ EPIC 11 (watch), advanced features
```

---

*Tento dokument je living document. Aktualizovat při každém dokončeném milníku.*
