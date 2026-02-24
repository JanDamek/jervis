# Bug: Git commit config se nepředává coding agentům

**Severity**: HIGH
**Date**: 2026-02-24

## Stav

UI a DTOs jsou **kompletní** — uživatel může nastavit commit message pattern, autora, GPG podpis
na úrovni klienta i projektu. Ale nastavení se **nikdy nedostane k coding agentovi**.

## Co funguje

| Vrstva | Stav |
|--------|------|
| DTOs (`GitConfigDto`, `ClientDto`, `ProjectDto`) | ✅ kompletní |
| Entity (`GitCommitConfig` na `ClientDocument` + `ProjectDocument`) | ✅ kompletní |
| UI formuláře (klient + projekt + GPG certifikáty) | ✅ kompletní |
| Mapper (Client/ProjectMapper) | ✅ kompletní |

## Co chybí — pipeline server → orchestrátor → agent

### 1. `ProjectRulesDto` neobsahuje git config

`PythonOrchestratorClient.kt` — `ProjectRulesDto` má jen:
`branchNaming`, `commitPrefix`, `requireReview`, `requireTests`, `autoPush`...

**Chybí:** `gitAuthorName`, `gitAuthorEmail`, `gitCommitterName`, `gitCommitterEmail`,
`gitGpgSign`, `gitGpgKeyId`, `gitMessagePattern`, `gitMessageFormat`

### 2. `buildProjectRules()` nenačítá git config

`AgentOrchestratorService.kt:495-525` — sestavuje `ProjectRulesDto`, ale
**nečte `gitCommitConfig`** z klienta/projektu.

### 3. `workspace_manager.py` nenastavuje git config

`workspace_manager.py:109-200` — vytváří `.jervis/` + `.claude/mcp.json` + `CLAUDE.md`,
ale **nevolá `git config`** pro:
- `git config user.name "<author>"`
- `git config user.email "<email>"`
- `git config commit.gpgsign true`
- `git config user.signingkey "<keyId>"`

### 4. `git_ops.py` předává jen prefix

`git_ops.py:76-83` — commit instrukce obsahují jen `commit_prefix`.
**Chybí:** author/email, GPG, message pattern s placeholdery.

### 5. Claude/Junie služby nedostávají git config

Obě služby spouští CLI (`claude --print` / `junie`) bez jakéhokoli git configu.
Závisí na #3 a #4 — pokud workspace_manager nastaví `git config`, agenti to zdědí.

## Plán opravy

1. **Rozšířit `ProjectRulesDto`** o git config pole (nebo vnořený objekt `gitConfig`)
2. **`buildProjectRules()`** — načíst `gitCommitConfig` z projektu (fallback na klienta)
3. **`workspace_manager.py`** — po clone/checkout volat `git config --local` se všemi hodnotami
4. **`git_ops.py`** — commit instrukce rozšířit o message pattern s placeholdery
5. **GPG klíč** — pokud `gpgSign=true`, importovat klíč do workspace (z GPG certificate store)

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/server/.../configuration/PythonOrchestratorClient.kt` | Rozšířit ProjectRulesDto |
| `backend/server/.../service/agent/coordinator/AgentOrchestratorService.kt` | buildProjectRules + gitCommitConfig |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | `git config --local` setup |
| `backend/service-orchestrator/app/graph/nodes/git_ops.py` | Commit instrukce s pattern + author |
| `backend/service-orchestrator/app/config.py` | Nové config pole pro git settings |
