# JERVIS — Master EPIC Plan: Cesta k autonomnímu asistentovi

**Datum:** 2026-02-25
**Autor:** Claude (na základě analýzy kódové základny + požadavků Jana Damka)
**Scope:** Kompletní roadmap od aktuálního stavu k plně autonomnímu JERVIS systému
**Status:** Living document — aktualizovat při každém milníku

---

## Východisko — Co už funguje

| Oblast | Stav | Poznámka |
|--------|------|----------|
| Kotlin server + kRPC + UI (Desktop/iOS/Android) | ✅ Produkční | KMP, Compose Multiplatform |
| Python orchestrátor (chat + background handler) | ✅ Produkční | Agentic loop, 26 chat tools, background tools |
| KB service (Weaviate RAG + ArangoDB graf) | ✅ Produkční | 3 repliky, async ingest |
| Polling pipeline (GitHub, GitLab, Atlassian, Email, Git) | ✅ Produkční | 3-stage: Polling → Indexing → Qualifier → KB |
| Coding agenti (Aider, OpenHands, Junie, Claude CLI) | ✅ Produkční | K8s Jobs, auto-select strategy |
| Brain (interní JIRA + Confluence) | ✅ Produkční | 8 brain tools, idle review loop |
| Notifikace (kRPC, FCM, APNs, Desktop OS) | ✅ Produkční | USER_TASK approval flow |
| Environment (K8s namespace, MCP tools) | ✅ Produkční | Per client/group/project |
| Ollama Router (1× P40 + CPU) | ✅ Produkční | 2-level priority, auto-reservation |
| Whisper + Transcript Correction | ✅ Produkční | K8s Job + REST mode, interactive Q&A |
| MCP server (KB + Env + Mongo + Orchestrator) | ✅ Produkční | Streamable HTTP, Bearer auth |
| Chat (foreground agentic loop) | ✅ Produkční | 26 tools, context switching, permissions |

---

## Co CHYBÍ pro autonomní provoz

**Klíčový problém:** JERVIS umí reagovat (na chat, na manuálně vytvořené tasky), ale **neumí proaktivně jednat** — sám najít co dělat, sám naplánovat, sám provést, sám zkontrolovat a sám doručit výsledek. Chybí mu také širší integrace (kalendář, Slack/Teams), sebeuvědomění (self-evolution) a infrastrukturní rozšíření (2. GPU).

---

## Fáze implementace

### FÁZE 1: JERVIS začne programovat (kritická cesta)
**Cíl:** Od momentu kdy qualifier najde actionable content, JERVIS sám naplánuje práci, zakóduje řešení, zkontroluje ho a předloží ke schválení.

### FÁZE 2: Širší autonomie
**Cíl:** JERVIS proaktivně monitoruje termíny, komunikuje s okolím, učí se z chyb a optimalizuje svou práci.

### FÁZE 3: Rozšíření ekosystému
**Cíl:** Nové zdroje dat (Slack/Teams, kalendář), hodinky, multi-GPU, self-evolution.

---

## Stav implementace — přehled EPIC bloků

| # | EPIC | Fáze | Priorita | Odhad | Status | Poznámka |
|---|------|------|----------|-------|--------|----------|
| 1 | Guidelines & Rules Engine | F1 | 🔴 CRITICAL | L | ✅ Done | Plně implementováno (DTOs, DB, UI, chat tools, resolver) |
| 2 | Autonomous Task Pipeline | F1 | 🔴 CRITICAL | XL | ✅ Done | S1-S5: qualifier, auto-creation, priority, planner, dispatch |
| 3 | Code Review Pipeline | F1 | 🔴 CRITICAL | L | ✅ Done | review_engine.py + re-dispatch loop + wired into background handler |
| 4 | Approval Framework (Rozšířený) | F1 | 🔴 CRITICAL | L | ✅ Done | approval_gate.py + batch approval + analytics + wired into tool executor |
| 5 | Action Execution Engine | F1 | 🔴 CRITICAL | L | ✅ Done | ActionExecutorService + result tracking + DTOs + ApprovalRequired event |
| 6 | 2nd GPU Integration | F2 | 🟡 HIGH | M | 📋 Planned | HW dependent — dual P40, multi-backend |
| 7 | Proaktivní KB Údržba & Učení | F2 | 🟡 HIGH | L | ✅ Done | DTOs + IdleTaskRegistry + VulnerabilityScanner + KbConsistencyChecker + LearningEngine + DocFreshness |
| 8 | Deadline Monitoring | F2 | 🟡 HIGH | M | ✅ Done | DTOs + DeadlineTrackerService + ProactivePreparationService |
| 9 | Chat Intelligence & Memory | F2 | 🟡 HIGH | M | ✅ Done | DTOs + action_log KB category + query_action_log chat tool |
| 10 | Dynamic Filtering Rules | F2 | 🟢 MEDIUM | S | ✅ Done | DTOs + FilteringRulesService + chat tools (set/list/remove) + intent + internal API |
| 11 | Slack / Teams / Discord | F3 | 🟢 MEDIUM | XL | 🔄 Foundation | DTOs (chat platform, messages) |
| 12 | Google Calendar Integration | F3 | 🟢 MEDIUM | M | 🔄 Foundation | DTOs (calendar events, availability) |
| 13 | System Prompt Self-Evolution | F3 | 🟢 MEDIUM | M | 🔄 Foundation | DTOs (prompt sections, learned behaviors) |
| 14 | Anti-Hallucination Guard | F2 | 🟡 HIGH | M | ✅ Done | fact_checker.py + contradiction_detector.py + DTOs |
| 15 | Apple Watch App | F3 | 🟢 MEDIUM | M | 📋 Planned | watchOS companion (needs native Swift) |
| 16 | Brain Workflow Structure | F3 | 🟢 MEDIUM | S | 🔄 Foundation | DTOs (issue types, daily reports) |
| 17 | Environment Agent Enhancement | F3 | 🟢 MEDIUM | S | 🔄 Foundation | DTOs (env agent jobs, validation) |

---

## FÁZE 1 — Epicy

---

### EPIC 1: Guidelines & Rules Engine (Hierarchický systém pravidel)

**Priorita:** 🔴 CRITICAL (blokuje EPIC 2–5)
**Odhad:** L (velký)
**Závislosti:** Žádné

#### Problém

ProjectRules v orchestrátoru dnes obsahuje jen Git pravidla (branch naming, commit prefix, approval flags). Chybí:
- Coding standards (linting rules, forbidden patterns, required patterns)
- Commit message konvence (conventional commits, JIRA prefix)
- Review checklist (co musí projít code review)
- Communication rules (jak odpovídat na emaily, komentáře)
- Action auto-approval pravidla (co smí JERVIS udělat bez ptaní)

#### Řešení

Trojúrovňový systém pravidel: **Global → Client → Project** kde nižší úroveň overriduje/doplňuje vyšší.

#### Stories

**E1-S1: MongoDB datový model pro Guidelines**
- Nová kolekce `guidelines` s dokumenty typu:
  - `scope`: `global` | `client:{id}` | `project:{id}`
  - `category`: `coding` | `git` | `review` | `communication` | `approval` | `general`
  - `rules`: JSON objekt s pravidly pro danou kategorii
- Rozlišovací logika: project overriduje client, client overriduje global (deep merge)
- Singleton `GuidelinesService` s cache (5 min TTL)

**E1-S2: Coding Guidelines**
- Pravidla: `forbidden_patterns` (regex), `required_patterns` (regex), `max_file_size`, `max_function_length`, `naming_conventions`, `language_specific` (per-language sub-rules)
- Default globální set: žádné hardcoded credentials, max 500 řádků/soubor, camelCase pro Kotlin, snake_case pro Python

**E1-S3: Git & Commit Guidelines**
- Rozšíření stávajících ProjectRules o:
  - `commit_message_template`: `"feat|fix|refactor|docs|test|chore(scope): description"`
  - `commit_message_validators`: regex patterns pro validaci
  - `branch_name_template`: `"task/{taskId}-{short-description}"`
  - `require_jira_reference`: bool
  - `squash_on_merge`: bool
  - `protected_branches`: list
- Migrace stávajících pravidel z ProjectRules → guidelines

**E1-S4: Review Guidelines**
- Checklist items: `must_have_tests`, `must_pass_lint`, `max_changed_files`, `max_changed_lines`, `forbidden_file_changes` (.env, secrets/, production configs)
- Review focus areas: security, performance, error handling, logging
- Per-language review rules

**E1-S5: Approval Guidelines (Auto-Approval Engine)**
- Nová kategorie pravidel `approval`:
  - `auto_approve_commit`: bool (default: false)
  - `auto_approve_push`: bool (default: false)
  - `auto_approve_email`: bool (default: false)
  - `auto_approve_jira_comment`: bool (default: false)
  - `auto_approve_jira_create`: bool (default: false)
  - `auto_approve_pr_comment`: bool (default: false)
  - `auto_approve_chat_reply`: bool (default: false)
  - `auto_approve_confluence_update`: bool (default: false)
- Každý s podmínkami: `when_risk_level_below`: LOW/MEDIUM, `when_confidence_above`: 0.8
- `ApprovalDecisionService`: vyhodnotí zda akce vyžaduje approval na základě pravidel + risk assessment

**E1-S6: Guidelines UI (Settings)**
- Nová sekce v Settings: "Pravidla a Směrnice"
- Tři záložky: Globální / Klient / Projekt
- Formuláře pro každou kategorii pravidel
- Vizuální indikátor "zdědil z vyšší úrovně" vs "přepsáno lokálně"

**E1-S7: Guidelines Chat Interface**
- Nové chat tools: `get_guidelines`, `update_guideline` (s approval)
- Uživatel může říct: "pro projekt X chci conventional commits s JIRA prefixem"
- JERVIS to přeloží do pravidel a uloží (po approval)

**E1-S8: Guidelines → Orchestrátor Integration**
- `GuidelinesResolver` v Python orchestrátoru: načítá merged pravidla pro daný client+project
- Pravidla injektována do system promptu coding agenta
- Pravidla použita v review phase

---

### EPIC 2: Autonomous Task Pipeline (Od zjištění po řešení)

**Priorita:** 🔴 CRITICAL
**Odhad:** XL (velmi velký)
**Závislosti:** EPIC 1 (Guidelines)

#### Problém

Dnes: Qualifier najde `hasActionableContent=true` → zapíše do brain JIRA → konec. Nikdo nepokračuje. Background handler umí zpracovat task, ale nikdo mu automaticky tasky nevytváří z qualifier findings.

#### Řešení

Rozšířit pipeline: Qualifier findings → automatická triáž → vytvoření interních tasků → auto-dispatch.

#### Stories

**E2-S1: Qualifier Enhanced Output**
- Qualifier rozšířen o pole:
  - `actionType`: `CODE_FIX` | `CODE_REVIEW` | `RESPOND_EMAIL` | `UPDATE_DOCS` | `CREATE_TICKET` | `INVESTIGATE` | `NOTIFY_ONLY`
  - `estimatedComplexity`: `TRIVIAL` | `SIMPLE` | `MEDIUM` | `COMPLEX`
  - `suggestedAgent`: `coding` | `orchestrator` | `none`
  - `affectedFiles`: list (pokud jde o kód)
  - `relatedKbNodes`: list (propojení s existujícím kontextem)
- Tato pole už částečně existují v `IngestResult` — rozšířit a standardizovat

**E2-S2: Task Auto-Creation Service**
- Nový `AutoTaskCreationService` v Kotlin serveru
- Volán po úspěšné kvalifikaci (v `KbResultRouter`)
- Logika:
  - `actionType == CODE_FIX && estimatedComplexity <= SIMPLE` → vytvořit BACKGROUND task automaticky
  - `actionType == CODE_FIX && estimatedComplexity >= MEDIUM` → vytvořit USER_TASK s plánem ke schválení
  - `actionType == RESPOND_EMAIL` → vytvořit draft odpovědi → USER_TASK ke schválení
  - `actionType == NOTIFY_ONLY` → jen notifikace (stávající chování)
  - `actionType == INVESTIGATE` → vytvořit BACKGROUND task pro analýzu
- Deduplikace: kontrola zda stejný finding už nemá existující task (`correlationId`)

**E2-S3: Task Priority & Scheduling Intelligence**
- `TaskPriorityCalculator`:
  - Vstup: urgency (z qualifier), deadline (z JIRA/KB), complexity, user preference
  - Výstup: priority score (0–100)
  - Pravidla: security vulnerabilities = 90+, approaching deadline = 80+, user-created = 70+, auto-discovered = 50
- Priority score ukládán na `TaskDocument`
- `BackgroundEngine.getNextBackgroundTask()` řadí podle priority score (dnes FIFO)

**E2-S4: Background Handler Enhancement — Planner Node**
- Rozšíření `handle_background()` o fázi plánování:
  - Před agentic loop: LLM analyzuje task + guidelines + KB kontext
  - Výstup plánu: steps, affected files, risk level, estimated time
  - Pro `estimatedComplexity >= MEDIUM`: plán uložit jako USER_TASK ke schválení
  - Pro `estimatedComplexity <= SIMPLE`: pokračovat rovnou
- Plán se loguje a ukládá do KB pro audit trail

**E2-S5: Coding Agent Dispatch Enhancement**
- Rozšíření `dispatch_coding_agent` tool:
  - Předat guidelines (coding + git) do workspace instruction
  - Předat plán (z E2-S4) jako strukturovanou instrukci
  - Předat review checklist (z guidelines)
  - Předat related KB context (relevantní kód, předchozí rozhodnutí)
  - `CLAUDE.md` v workspace obsahuje sekci "Project Rules" z guidelines

**E2-S6: Result Processing & Artifact Collection**
- Po dokončení coding agenta:
  - Parsovat výstup (diff, changed files, test results)
  - Uložit artifacts do KB (co se změnilo, proč)
  - Vyhodnotit zda výsledek splňuje task requirements
  - Pokud ne → re-dispatch s upraveným promptem (max 2 retry)

**E2-S7: End-to-End Pipeline Monitoring**
- Dashboard v UI: pipeline funnel view
  - Polled → Qualified → Task Created → Planned → Coding → Review → Approved → Executed
- Metriky: průměrný čas v každé fázi, success rate, retry rate
- Logování do KB pro analýzu bottlenecků

---

### EPIC 3: Code Review Pipeline

**Priorita:** 🔴 CRITICAL
**Odhad:** L (velký)
**Závislosti:** EPIC 1 (Guidelines), EPIC 2 (Task Pipeline)

#### Problém

Coding agent vyprodukuje kód, ale nikdo ho nekontroluje. Chybí automated review s enforced guidelines.

#### Řešení

Druhý průchod: nezávislý review agent (stejný orchestrátor, jiný kontext) kontroluje výstup prvního agenta.

#### Stories

**E3-S1: Review Agent System Prompt**
- Dedikovaný review prompt builder:
  - Role: "Jsi senior code reviewer. Kontroluješ kód proti pravidlům."
  - Vstup: diff, plán, guidelines (coding + review + git), original task description
  - Výstup: strukturovaný verdikt: `APPROVE` | `REQUEST_CHANGES` | `REJECT`
  - Pro `REQUEST_CHANGES`: konkrétní issues s file:line referencemi
- Review NESMÍ vidět myšlenkový proces coding agenta (nezávislost)

**E3-S2: Review Execution Engine**
- Nový node v background pipeline (za coding, před finalize):
  - Spouští review agent jako separátní LLM volání
  - Review agent dostane: diff, original task, guidelines, KB kontext
  - Review agent NEMÁ přístup k coding agent instructions (prevence bias)
- Logika po review:
  - `APPROVE` → pokračuje na finalize/approval
  - `REQUEST_CHANGES` → re-dispatch coding agent s review feedback (max 2 rounds)
  - `REJECT` → escalate na USER_TASK s detaily

**E3-S3: Static Analysis Integration**
- Před LLM review: spustit automatické kontroly:
  - File size limits (z guidelines)
  - Forbidden patterns check (regex z guidelines)
  - Import/dependency check (zakázané dependencies)
  - Credentials scan (hardcoded secrets detection)
- Výsledky předány LLM review agentovi jako kontext

**E3-S4: Review Report Generation**
- Strukturovaný review report (uložen v KB):
  - Summary: pass/fail, score 0–100
  - Issues: list s severity (blocker/major/minor/info)
  - Per-file comments
  - Suggestions
- Report přiložen k USER_TASK pokud jde na approval

**E3-S5: Review Guidelines Enforcement Check**
- Speciální verifikace: review agent MUSÍ zkontrolovat KAŽDOU položku z review checklist (z guidelines)
- Výstup review obsahuje checklist s ✅/❌ pro každou položku
- Pokud jakýkoli BLOCKER → automaticky REJECT (ne LLM rozhodnutí, ale hard rule)

---

### EPIC 4: Approval Framework (Rozšířený)

**Priorita:** 🔴 CRITICAL
**Odhad:** L (velký)
**Závislosti:** EPIC 1 (Guidelines), EPIC 2 (Task Pipeline), EPIC 3 (Review)

#### Problém

Dnes approval existuje jen pro git commit/push (orchestrátor interrupt). Chybí approval pro ostatní akce: email, JIRA, komentáře atd. A chybí auto-approval engine.

#### Řešení

Univerzální approval framework: každá "write" akce prochází approval gate, který rozhodne approve/deny/ask na základě guidelines.

#### Stories

**E4-S1: Universal Approval Gate**
- `ApprovalGate` service:
  - Vstup: `action_type`, `action_payload`, `risk_level`, `confidence`, `client_id`, `project_id`
  - Výstup: `AUTO_APPROVED` | `NEEDS_APPROVAL` | `DENIED`
  - Rozhodování: guidelines (E1-S5) + risk level + confidence score
- Registrace v orchestrátoru: KAŽDÝ write tool prochází ApprovalGate před execution

**E4-S2: Approval Actions Registry**
- Enum `ApprovalAction`:
  - `GIT_COMMIT`, `GIT_PUSH`, `GIT_CREATE_BRANCH`
  - `EMAIL_SEND`, `EMAIL_REPLY`
  - `JIRA_CREATE_ISSUE`, `JIRA_UPDATE_ISSUE`, `JIRA_COMMENT`, `JIRA_TRANSITION`
  - `CONFLUENCE_CREATE_PAGE`, `CONFLUENCE_UPDATE_PAGE`
  - `PR_CREATE`, `PR_COMMENT`, `PR_MERGE`
  - `CHAT_REPLY` (odpověď v externím chat vlákně)
  - `KB_DELETE`, `KB_STORE` (write do KB)
  - `DEPLOY` (deployment do environment)
  - `CODING_DISPATCH` (spuštění coding agenta — pro drahé operace)

**E4-S3: Approval Queue Enhancement**
- Rozšíření USER_TASK pro approval:
  - `approvalAction`: typ akce
  - `approvalPayload`: JSON s detaily (diff, email body, JIRA fields...)
  - `approvalContext`: proč to JERVIS chce udělat (reasoning)
  - `approvalPreview`: human-readable preview výsledku
- UI: approval dialog s preview, ne jen text

**E4-S4: Batch Approval**
- Pro EPIC tasky (mnoho drobných akcí):
  - Seskupení related approvals: "5 JIRA komentářů k wave 1"
  - Approve all / Deny all / Review individually
- Snížení approval fatigue

**E4-S5: Approval Analytics & Trust Building**
- Tracking: kolik akcí daného typu bylo schváleno vs zamítnuto
- Suggestion: "V posledních 30 dnech jste schválili 47/47 JIRA komentářů. Chcete zapnout auto-approve?"
- Eskalace v UI Settings (Guidelines → Approval → per-action toggle)

---

### EPIC 5: Action Execution Engine

**Priorita:** 🔴 CRITICAL
**Odhad:** L (velký)
**Závislosti:** EPIC 4 (Approval)

#### Problém

Po approval je třeba akci skutečně provést. Dnes orchestrátor umí jen coding dispatch a brain tools. Chybí: odeslání emailu, vytvoření PR, komentář v klientském JIRA (ne brain), odpověď v chat vlákně.

#### Řešení

Unified Action Executor: po approval zavolá příslušný backend service.

#### Stories

**E5-S1: Action Executor Service (Kotlin)**
- `ActionExecutorService`:
  - Vstup: `ApprovalAction` + `payload` + `clientId` + `projectId`
  - Routing na konkrétní service (email, bugtracker, wiki, git...)
  - Error handling: retry s backoff, fallback na USER_TASK
  - Audit log: každá provedená akce uložena do `action_log` kolekce

**E5-S2: Email Send Action**
- Integrace s email connections (SMTP)
- `EmailSendService.sendEmail(connectionId, to, subject, body, inReplyTo?)`
- Template support: JERVIS napíše draft, po approval odešle
- Threading: In-Reply-To header pro odpovědi

**E5-S3: Client JIRA Actions**
- Rozšíření stávajících bugtracker tools o write operace do KLIENTSKÉHO JIRA (ne brain):
  - `client_create_issue(connectionId, projectKey, ...)`
  - `client_update_issue(connectionId, issueKey, ...)`
  - `client_add_comment(connectionId, issueKey, comment)`
  - `client_transition_issue(connectionId, issueKey, transitionId)`
- Connection resolution: z project resources najít správný `connectionId`

**E5-S4: PR/MR Actions**
- `client_create_pr(connectionId, repo, sourceBranch, targetBranch, title, description)`
- `client_comment_pr(connectionId, repo, prId, comment)`
- Provider abstrakce: GitHub PR vs GitLab MR vs Bitbucket PR

**E5-S5: Confluence/Wiki Actions**
- Rozšíření wiki tools pro klientské Confluence spaces
- `client_create_page`, `client_update_page`
- Formátování: Markdown → Confluence storage format konverze

**E5-S6: Action Result Tracking**
- Po provedení akce:
  - Uložit výsledek do KB (graph node propojený s taskem)
  - Aktualizovat brain JIRA issue (pokud existuje)
  - Notifikace uživateli: "Akce provedena: [detail]"
  - Pokud akce selhala: retry nebo escalate

---

## FÁZE 2 — Epicy

---

### EPIC 6: 2nd GPU Integration (Ollama Router Multi-GPU)

**Priorita:** 🟡 HIGH
**Odhad:** M (střední)
**Závislosti:** Žádné (paralelizovatelný)

#### Problém

Jedna P40 = bottleneck. Foreground blokuje background. Dvě P40 v separátním serveru umožní paralelní provoz.

#### Stories

**E6-S1: 2nd Ollama Instance Setup**
- Nový server s 2× P40
- Docker compose / systemd pro 2 Ollama instance (jedna na GPU0, druhá na GPU1)
- `CUDA_VISIBLE_DEVICES=0` / `CUDA_VISIBLE_DEVICES=1`
- Porty: 11434 (GPU0), 11436 (GPU1)

**E6-S2: Ollama Router Multi-Backend**
- Rozšíření router configu:
  - `gpu_backends`: list of `{host, port, gpu_id, vram_mb}`
  - Routing strategy: round-robin pro NORMAL, dedicated GPU pro CRITICAL
- Nový routing mode: DEDICATED
  - GPU0: vyhrazena pro CRITICAL (foreground chat, orchestrátor)
  - GPU1: NORMAL (background, KB ingest, correction, embeddings)
  - Pokud GPU0 idle → přebírá NORMAL requesty (žádné plýtvání)

**E6-S3: Health Monitoring & Failover**
- Router health check pro oba backends (5s interval)
- Pokud jeden GPU backend spadne → vše na druhý (degraded mode)
- Metriky: GPU utilization, queue depth, latency per backend

**E6-S4: Model Distribution Strategy**
- GPU0 (foreground): `qwen3-coder-tool:30b` + `nomic-embed-text`
- GPU1 (background): `qwen3-coder-tool:30b` + `nomic-embed-text`
- Obě karty mají stejné modely → žádný model swap potřeba
- Whisper zůstává na CPU (dostatečný throughput)

---

### EPIC 7: Proaktivní KB Údržba & Učení

**Priorita:** 🟡 HIGH
**Odhad:** L (velký)
**Závislosti:** EPIC 2 (Task Pipeline)

#### Problém

Idle review loop (bod 10 zadání) dnes jen kontroluje brain JIRA. Chybí: vulnerability scanning, learning from internet, knowledge enrichment.

#### Stories

**E7-S1: Idle Tasks Registry**
- Konfigurovatelný seznam idle aktivit s prioritami:
  1. Review open brain issues (existuje)
  2. KB consistency check (duplicáty, protichůdné informace)
  3. Dependency vulnerability scan (pro všechny projekty s REPOSITORY resource)
  4. Code quality scan (základní statická analýza z KB dat)
  5. Documentation freshness check (stará docs vs nový kód)
  6. Learning: hledání best practices pro technologie v projektech

**E7-S2: Vulnerability Scanner (Idle Task)**
- Pro každý projekt s git workspace:
  - Parse `package.json` / `build.gradle.kts` / `requirements.txt`
  - Web search pro known CVEs
  - Uložit findings do KB + brain JIRA (pokud severity HIGH+)
- Frekvence: 1× denně per project (pokud idle)

**E7-S3: KB Consistency Checker**
- Detekce:
  - Duplicitní RAG chunks (vysoká similarity, jiný `sourceUrn`)
  - Protichůdné informace (LLM-based detekce)
  - Zastaralé informace (starší než X dní, relevance check)
- Akce: označit k review, navrhnout merge/delete

**E7-S4: Learning Engine**
- Web search pro nové postupy relevantní k technologiím v projektech
- Kritéria: Kotlin, Spring Boot, KMP, Python, FastAPI, LangGraph, K8s
- Uložit jako `kind="best_practice"` do KB (global scope)
- Validation: nový postup musí být podpořen min. 2 zdroji

**E7-S5: Documentation Freshness Monitor**
- Porovnání timestamp posledního doc update vs posledního code update
- Pokud kód se výrazně změnil a docs ne → vytvořit background task na aktualizaci
- Scope: README, API docs, architecture docs

---

### EPIC 8: Deadline Monitoring & Proaktivní Příprava

**Priorita:** 🟡 HIGH
**Odhad:** M (střední)
**Závislosti:** EPIC 2 (Task Pipeline)

#### Problém

Qualifier extrahuje `suggestedDeadline` a `hasFutureDeadline`, ale nikdo na to nereaguje. Chybí countdown monitoring a proaktivní příprava.

#### Stories

**E8-S1: Deadline Tracker Service**
- `DeadlineTrackerService` v Kotlin serveru:
  - Periodický scan KB + JIRA pro items s deadline
  - Threshold alerts: 7d, 3d, 1d, overdue
  - Uložení stavu v MongoDB: `deadline_tracking` kolekce

**E8-S2: Deadline Notifications**
- 7d: info notifikace "Za týden: [task]. Aktuální stav: [status]"
- 3d: warning notifikace + brain JIRA update
- 1d: urgent notifikace + návrh prioritizace
- Overdue: critical notifikace + re-prioritizace background queue

**E8-S3: Proaktivní Příprava**
- Při 3d threshold:
  - JERVIS analyzuje co je potřeba k dokončení
  - Vytvoří breakdown (subtasky) pokud chybí
  - Odhadne zbývající effort
  - Navrhne plán na zbývající dny
- Při 1d threshold:
  - Auto-prioritizuje related tasky na TOP fronty
  - Pokud je coding potřeba: navrhne zjednodušený scope

**E8-S4: Deadline Dashboard Widget**
- UI komponenta: "Blížící se termíny" s countdown
- Barevné kódování: zelená (>7d), žlutá (3-7d), oranžová (1-3d), červená (<1d)

---

### EPIC 9: Chat Intelligence & Memory Enhancement

**Priorita:** 🟡 HIGH
**Odhad:** M (střední)
**Závislosti:** Žádné

#### Problém

Chat dnes zvládá jednoduchý context switching, ale nezvládá nuance: "jak jsme to řešili?" (odkaz na minulou konverzaci), "vlastně ten projekt A..." (návrat k předchozímu tématu), přirozené přepínání mezi tématy.

#### Stories

**E9-S1: Topic Tracker**
- Detekce témat v konverzaci (LLM-based)
- Metadata na každé zprávě: `topics: ["project-X", "bug-123", "architecture-decision"]`
- Při návratu k tématu: automaticky load relevant KB context

**E9-S2: Conversation Memory Consolidation**
- Aktuálně: rolling summaries (20 msg bloky)
- Zlepšení: topic-aware summaries — souhrn per-topic, ne per-block
- Cross-session recall: "minule jsme se bavili o..." → search KB + summaries

**E9-S3: Intent Decomposition Enhancement**
- Stávající `handler_decompose.py` rozšířit o:
  - Multi-intent parsing: "udělej X a taky zjisti Y" → 2 paralelní akce
  - Implicit reference resolution: "ten bug" → najít nejbližší zmiňovaný bug v kontextu
  - Clarification strategy: ptát se jen když opravdu nerozumí, ne preventivně

**E9-S4: Action Memory (Co JERVIS pro mě udělal)**
- Nová KB kategorie: `kind="action_log"`
- Po každé provedené akci: uložit `{co, proč, kdy, výsledek}`
- Chat může odpovědět na: "co jsi dnes udělal?", "jak dopadl ten PR?"

---

### EPIC 10: Dynamic Filtering Rules (Chat-driven)

**Priorita:** 🟢 MEDIUM
**Odhad:** S (malý)
**Závislosti:** EPIC 1 (Guidelines)

#### Problém

Uživatel chce řídit filtrování informací konverzačně: "tohle už nechci sledovat", "security alerts vždy na maximum".

#### Stories

**E10-S1: Filter Rules Data Model**
- `filtering_rules` kolekce:
  - `scope`: global / client / project
  - `source_type`: email / jira / git / wiki
  - `condition`: regex/keyword match na subject, body, labels
  - `action`: `IGNORE` | `LOW_PRIORITY` | `NORMAL` | `HIGH_PRIORITY` | `URGENT`
- Default: žádná pravidla (vše prochází)

**E10-S2: Chat Tools pro Filtering**
- `set_filter_rule(scope, source_type, condition, action)`
- `list_filter_rules(scope?)`
- `remove_filter_rule(rule_id)`
- LLM rozumí přirozenému jazyku: "ignoruj emaily od marketing@" → vytvoří pravidlo

**E10-S3: Qualifier Integration**
- Qualifier kontroluje filter rules PŘED vyhodnocením actionability
- Pokud match `IGNORE` → task → DONE (ne READY_FOR_GPU)
- Pokud match `URGENT` → zvýšit priority score

---

## FÁZE 3 — Epicy

---

### EPIC 11: Slack / Teams / Discord Integration

**Priorita:** 🟢 MEDIUM
**Odhad:** XL (velmi velký)
**Závislosti:** EPIC 1 (Guidelines)

#### Stories

**E11-S1: ConnectionCapability rozšíření**
- Nový capability: `CHAT_READ`, `CHAT_SEND`
- Nové providery: `SLACK`, `MICROSOFT_TEAMS`, `DISCORD`
- Protocol: REST API (Slack Web API, MS Graph, Discord API)

**E11-S2: Slack Connection Service**
- `service-slack` (nový Ktor microservice)
- OAuth2 flow pro Slack workspace
- Capabilities: read channels, send messages, read threads, react
- Polling handler: sledovat channels, DMs, mentions

**E11-S3: Teams Connection Service**
- `service-teams` (nový Ktor microservice)
- Poznámka: korporátní prostředí — podpora pro service account / app registration
- Capabilities: read channels, send messages, read threads

**E11-S4: Chat Source Indexer**
- `ChatContinuousIndexer`: analogie k `EmailContinuousIndexer`
- Specifika: threading (konverzace), reactions (sentiment), mentions (relevance)
- Qualifier: CHAT messages mají specifické priority patterns (urgentní = emoji, @mention)

**E11-S5: Chat Reply Action**
- `CHAT_REPLY` jako nový approval action
- Odpověď do Slack threadu / Teams conversation
- S preview v approval dialogu

---

### EPIC 12: Google Calendar Integration

**Priorita:** 🟢 MEDIUM
**Odhad:** M (střední)
**Závislosti:** Žádné

#### Stories

**E12-S1: Google Calendar Connection**
- Nový provider: `GOOGLE_CALENDAR` (v rámci `GOOGLE_WORKSPACE`)
- OAuth2 flow pro Google Calendar API
- Capabilities: `CALENDAR_READ`, `CALENDAR_WRITE`

**E12-S2: Calendar Polling & Indexing**
- Periodický sync: events → KB
- Indexace: co, kdy, kde, kdo, opakování
- "Dnes mám:" kontext pro chat system prompt

**E12-S3: Availability Awareness**
- `AvailabilityService`:
  - Kdy má uživatel volno (free slots)
  - Kdy je JERVIS nejlépe využitelný (user busy = JERVIS pracuje autonomně)
  - Smart scheduling: urgentní notifikace jen v free sloty (pokud ne life-threatening)

**E12-S4: Calendar Write Actions (s approval)**
- `calendar_create_event(title, start, end, description, attendees?)`
- `calendar_block_time(title, start, end)` — focus time
- Vše přes approval gate

**E12-S5: Scheduler Integration**
- JERVIS ví kdy jsou deadlines → vidí v kalendáři co je → plánuje:
  - "Zítra máte celý den meetingy, dnes zpracuji urgentní tasky"
  - "V pátek je deadline X, blokuji čtvrtek odpoledne na finalizaci"

---

### EPIC 13: System Prompt Self-Evolution

**Priorita:** 🟢 MEDIUM
**Odhad:** M (střední)
**Závislosti:** EPIC 4 (Approval)

#### Problém

JERVIS se nemůže učit z chyb na úrovni svého chování. Pokud opakovaně dělá špatná rozhodnutí, nemá jak si "zapsat poznámku" do svého system promptu.

#### Stories

**E13-S1: Prompt Architecture — Base + Dynamic**
- System prompt rozdělit na:
  - `base_prompt`: neměnný, definuje identitu a core chování
  - `learned_behaviors`: dynamická sekce, JERVIS si může dopisovat
  - `user_corrections`: uživatelské korekce ("vždy dělej X", "nikdy Y")
- Storage: MongoDB `system_prompt_sections` kolekce

**E13-S2: Behavior Learning Loop**
- Po každém USER_TASK denial:
  - LLM analyzuje proč uživatel zamítl
  - Navrhne nové pravidlo pro `learned_behaviors`
  - Pravidlo jde přes approval ("Chci si zapsat: [pravidlo]. Souhlasíte?")
- Po opakovaném schválení stejného typu akce:
  - Navrhne relaxaci approval pravidla

**E13-S3: User Correction Interface**
- Chat: "Zapomeň na to, JIRA komentáře piš vždy česky"
  - → Uloží do `user_corrections` sekce
  - → Příští system prompt load to obsahuje
  - → KB záznam pro persistence

**E13-S4: Prompt Version History**
- Každá změna dynamic sekcí = nová verze
- Rollback možnost v UI
- Diff view: co se změnilo a proč

---

### EPIC 14: Anti-Hallucination Guard

**Priorita:** 🟡 HIGH
**Odhad:** M (střední)
**Závislosti:** Žádné (paralelizovatelný)

#### Problém

LLM halucinuje — přebírá fakta z chat historie bez ověření, vymýšlí file paths, API endpointy.

#### Stories

**E14-S1: Fact-Check Pipeline**
- Každá odpověď obsahující faktické tvrzení (filename, URL, API, číslo):
  - Verifikace proti KB
  - Verifikace proti git workspace (pokud kódový kontext)
  - Označení: ✅ ověřeno / ⚠️ neověřeno / ❌ v rozporu s KB
- Implementace: post-processing node v agentic loop

**E14-S2: Source Attribution**
- Každé tvrzení musí mít zdroj:
  - KB chunk reference
  - Git file:line reference
  - Web search URL
  - Chat history message reference
- Pokud žádný zdroj → explicitně označit "Toto je můj odhad, neověřeno"

**E14-S3: Contradiction Detector**
- Při ukládání do KB:
  - Porovnat s existujícími chunks ve stejném scope
  - Pokud contradiction → escalate na USER_TASK
  - Neuložit protichůdnou informaci bez approval

**E14-S4: Confidence Scoring**
- Každá odpověď ohodnocena confidence (0–1):
  - 0.9+ = plně podpořeno KB daty
  - 0.7–0.9 = částečně podpořeno
  - < 0.7 = nízká confidence, doporučeno ověření
- Confidence zobrazeno v UI (subtilně, ne rušivě)

---

### EPIC 15: Apple Watch App

**Priorita:** 🟢 MEDIUM
**Odhad:** M (střední)
**Závislosti:** EPIC 4 (Approval), notifikace (existuje)

#### Stories

**E15-S1: watchOS Extension (Companion App)**
- Sdílení dat s iOS app přes WatchConnectivity
- Minimální UI: seznam aktivních notifikací

**E15-S2: Quick Notes**
- Dictation → text → uložit jako note / background task
- Siri shortcut: "Hey Siri, JERVIS poznámka: [text]"

**E15-S3: Approval na zápěstí**
- Push notifikace s action buttons: ✅ Approve / ❌ Deny
- Quick reply pro clarification otázky

**E15-S4: Quick Record**
- Tlačítko pro spuštění nahrávání (meeting/rozhovor)
- Start → iPhone/backend zpracuje (Whisper)

**E15-S5: Complications**
- Complication: počet pending approvals
- Complication: stav orchestrátoru (idle/working/waiting)

---

### EPIC 16: Brain Workflow Structure

**Priorita:** 🟢 MEDIUM
**Odhad:** S (malý)
**Závislosti:** Brain (existuje)

#### Stories

**E16-S1: JIRA Issue Types & Workflow**
- Definovat issue types pro brain projekt:
  - Task (běžný úkol)
  - Bug (nalezený problém)
  - Finding (zjištění z monitoringu)
  - Review (code review finding)
  - Learning (naučené best practices)
- Workflow: Open → In Progress → Review → Done / Blocked

**E16-S2: Confluence Space Structure**
- Auto-vytvoření hierarchie:
  - `/Architecture` — rozhodnutí a design docs
  - `/Daily Reports` — denní shrnutí aktivit
  - `/Client: {name}` — per-client pages
  - `/Knowledge` — naučené postupy
  - `/Drafts` — rozpracované dokumenty

**E16-S3: Daily Report Generator**
- Automatický denní report (idle task):
  - Co se dnes udělalo (completed tasks)
  - Co se naučilo (new KB entries)
  - Co čeká (pending approvals, upcoming deadlines)
  - Problémy (errors, stuck tasks)
- Uložit do Confluence `/Daily Reports/{date}`

---

### EPIC 17: Environment Agent Enhancement

**Priorita:** 🟢 MEDIUM
**Odhad:** S (malý)
**Závislosti:** Environment (existuje)

#### Stories

**E17-S1: On-Demand Environment Agent**
- Nový typ K8s Job: `ENVIRONMENT_AGENT`
- Claude CLI s MCP jervis-server + kubectl access
- Spouštěn coding agentem (sub-dispatch) nebo orchestrátorem
- Kontext: namespace, deployment list, service endpoints

**E17-S2: Deployment Validation**
- Po deploy do environment:
  - Health check všech podů
  - Smoke test (pokud definován v guidelines)
  - Log analysis (error patterns)
- Report zpět coding agentovi

**E17-S3: Debug Assistance**
- Coding agent řekne: "Potřebuji logy z podu X"
- Environment agent: `kubectl logs` → parsuje → vrací relevantní sekce
- Environment agent: `kubectl describe` → vrací status + events

---

## Závislostní graf

```
EPIC 1 (Guidelines) ──────────┐
    │                          │
    ├──► EPIC 2 (Task Pipeline)│
    │        │                 │
    │        ├──► EPIC 3 (Review)
    │        │        │
    │        │        ├──► EPIC 4 (Approval)
    │        │        │        │
    │        │        │        ├──► EPIC 5 (Action Execution)
    │        │        │        │        │
    │        │        │        │        └──► KOMPLETNÍ AUTONOMOUS LOOP
    │        │        │        │
    │        │        │        ├──► EPIC 13 (Self-Evolution)
    │        │        │        └──► EPIC 15 (Watch)
    │        │
    │        ├──► EPIC 7 (KB Maintenance)
    │        ├──► EPIC 8 (Deadline Monitor)
    │        └──► EPIC 10 (Filtering Rules)
    │
    └──► EPIC 11 (Slack/Teams)

EPIC 6 (2nd GPU) ────── paralelní, bez závislostí
EPIC 9 (Chat) ───────── paralelní, bez závislostí
EPIC 12 (Calendar) ──── paralelní, bez závislostí
EPIC 14 (Anti-Haluc.) ─ paralelní, bez závislostí
EPIC 16 (Brain Struct) ─ paralelní, bez závislostí
EPIC 17 (Env Agent) ──── paralelní, bez závislostí
```

---

## Doporučené pořadí implementace

| Pořadí | EPIC | Odhad | Důvod |
|--------|------|-------|-------|
| 1 | EPIC 6: 2nd GPU | M | HW je ready, okamžitý throughput gain, odblokuje paralelní práci |
| 2 | EPIC 1: Guidelines | L | Foundation pro vše ostatní, bez pravidel JERVIS neví CO kontrolovat |
| 3 | EPIC 14: Anti-Hallucination | M | Kvalita odpovědí musí být high PŘED autonomním provozem |
| 4 | EPIC 2: Task Pipeline | XL | Core autonomous loop — od discovery po execution |
| 5 | EPIC 3: Code Review | L | Kvalitní gate před tím než kód opustí JERVIS |
| 6 | EPIC 4: Approval Framework | L | Bezpečnostní vrstva — vše schváleno uživatelem |
| 7 | EPIC 5: Action Execution | L | Uzavření loop — JERVIS může skutečně DĚLAT věci |
| 8 | EPIC 9: Chat Intelligence | M | Zlepšení UX hlavního interface |
| 9 | EPIC 8: Deadline Monitor | M | Proaktivita — JERVIS hlídá čas |
| 10 | EPIC 7: KB Maintenance | L | Idle time utilization — JERVIS se učí |
| 11 | EPIC 16: Brain Structure | S | Quick win — organizace interního prostředí |
| 12 | EPIC 10: Filtering Rules | S | Quick win — user control přes chat |
| 13 | EPIC 13: Self-Evolution | M | JERVIS se zlepšuje sám |
| 14 | EPIC 12: Calendar | M | Nový zdroj dat + plánování |
| 15 | EPIC 11: Slack/Teams | XL | Velký scope, ale ne kritický pro MVP |
| 16 | EPIC 17: Env Agent | S | Enhancement, ne blocker |
| 17 | EPIC 15: Watch | M | Nice-to-have, UX improvement |

---

## Milestones

### M1 — "JERVIS kóduje" (EPIC 1 + 2 + 3 + 4 + 5 + 6)
JERVIS sám najde co dělat, naplánuje, zakóduje, zkontroluje, předloží ke schválení a po OK provede.

### M2 — "JERVIS je proaktivní" (EPIC 7 + 8 + 9 + 14)
JERVIS hlídá termíny, udržuje kvalitu KB, detekuje halucinace, lépe komunikuje v chatu.

### M3 — "JERVIS je všude" (EPIC 11 + 12 + 15)
JERVIS je napojený na Slack/Teams, kalendář, hodinky — kompletní ekosystém.

### M4 — "JERVIS se učí" (EPIC 10 + 13 + 16 + 17)
JERVIS si sám upravuje chování, organizuje svůj prostor, řídí environment agenty.

---

*Tento dokument je living document. Aktualizovat při každém dokončeném milníku.*
