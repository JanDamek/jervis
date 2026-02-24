# Bugy v background handleru — task create + Jira issue type

**Severity**: MEDIUM
**Date**: 2026-02-24

## Bug 1: create_background_task — field mismatch Python tool vs Kotlin DTO

**Chyba v logu**:
```
MissingFieldException: Field 'query' is required for type InternalCreateTaskRequest, but it was missing
```

Python tool definice (`tools.py`) posílá:
- `title` (required), `description` (required), `client_id`, `project_id`, `priority`

Kotlin DTO (`InternalTaskApiRouting.kt:256-261`) očekává:
- `query` (required), `clientId` (required), `projectId`

| Python tool | Kotlin DTO | Problém |
|-------------|-----------|---------|
| `title` | — | Kotlin nemá `title` |
| `description` | — | Kotlin nemá `description` |
| — | `query` (required) | Python neposílá `query` |
| `client_id` | `clientId` | Jiný název (snake vs camel) |
| `priority` | — | Kotlin nemá `priority` |

**Handler** v Pythonu (`handler_tools.py` nebo `executor.py`) musí mapovat
tool argumenty na Kotlin API, ale zjevně posílá raw argumenty bez transformace.

**Oprava** (výběr):
- A) Opravit Python handler — mapovat `title`+`description` → `query`, `client_id` → `clientId`
- B) Rozšířit Kotlin DTO — přidat `title`, `description`, `priority`, přijímat snake_case

## Bug 2: brain_create_issue — Jira issue type "Task" neexistuje

**Chyba v logu**:
```
Jira create issue failed with 400: {"errors":{"issuetype":"Zadejte platný typ požadavku"}}
```

Background handler volá `brain_create_issue(issue_type="Task")`, ale česká Jira instance
nemá issue type "Task" — má český ekvivalent (např. "Úkol").

**Oprava**:
- Zjistit dostupné issue types přes Jira API (`/rest/api/3/issuetype`)
- Buď mapovat "Task" → český název v brain tool handleru
- Nebo nastavit v Jira konfiguraci anglické typy

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/service-orchestrator/app/chat/handler_tools.py` nebo `app/tools/executor.py` | Mapování tool args → Kotlin API (#1) |
| `backend/server/.../rpc/internal/InternalTaskApiRouting.kt` | Případně rozšířit DTO (#1) |
| `backend/server/.../service/integration/brain/BrainJiraService.kt` | Issue type mapping (#2) |
