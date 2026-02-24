# Bug: Git commit config se nepředává coding agentům

**Severity**: HIGH
**Date**: 2026-02-24
**Status**: DONE

## Vyřešeno

| Vrstva | Stav |
|--------|------|
| DTOs (`GitConfigDto`, `ClientDto`, `ProjectDto`) | ✅ kompletní |
| Entity (`GitCommitConfig` na `ClientDocument` + `ProjectDocument`) | ✅ kompletní |
| UI formuláře (klient + projekt + GPG certifikáty) | ✅ kompletní |
| Mapper (Client/ProjectMapper) | ✅ kompletní |
| `ProjectRulesDto` — git config pole | ✅ přidáno |
| `buildProjectRules()` — načítá gitCommitConfig z projektu/klienta | ✅ implementováno |
| `workspace_manager.py` — `git config --local` setup | ✅ implementováno |
| `git_ops.py` — commit instrukce s pattern + author | ✅ implementováno |
| Python `ProjectRules` model — git config pole | ✅ přidáno |

## Pipeline

```
Kotlin loadProjectRules()
  → čte gitCommitConfig z ProjectDocument (fallback ClientDocument)
  → plní ProjectRulesDto git_author_name, git_author_email, ...
  → posílá v OrchestrateRequestDto.rules

Python execute.py
  → čte rules.git_* z state
  → předává do workspace_manager.prepare_workspace(git_config=...)
  → workspace_manager volá git config --local

Python git_ops.py
  → commit instrukce obsahují message pattern + author
  → znovu nastaví git config --local (idempotent)
```
