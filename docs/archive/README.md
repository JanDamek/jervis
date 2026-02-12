# Documentation Archive

This directory contains historical documentation that is no longer actively maintained but preserved for reference.

## Archived Documents

### `kb-analysis-and-recommendations.md`
- **Date:** 2026-02-05
- **Status:** Superseded by `../knowledge-base.md`
- **Content:** Critical analysis of KB service, multi-tenant filtering issues, entity canonization recommendations
- **Why archived:** The recommendations were implemented and the current KB architecture is now documented in `knowledge-base.md`

### `koog-audit.md`
- **Date:** 2026-02-07
- **Status:** Completed migration
- **Content:** Complete removal of Koog framework dependencies (26 files, 31 orchestrator classes, 3 Gradle deps)
- **Why archived:** Migration completed. All AI orchestration now handled by Python LangGraph orchestrator

### `task-scheduling-analysis.md`
- **Date:** 2026-02-11
- **Status:** Fixed
- **Content:** Analysis of scheduler bugs (tasks never executing, "Unnamed Task" everywhere)
- **Why archived:** Issues fixed via `runSchedulerLoop()` implementation in BackgroundEngine

### `operations.md`
- **Date:** 2026-02-05
- **Status:** Historical
- **Content:** OAuth2 Phase 1 deployment checklist, RPC security verification, pre-deployment testing procedures
- **Why archived:** Specific to OAuth2 Phase 1 implementation; current deployment procedures in build scripts and CLAUDE.md

---

## When to Archive

Documents should be moved to archive when:
- Superseded by newer documentation (e.g., analysis → SSOT doc)
- Describing completed migrations or one-time changes
- Historical debugging/analysis that is no longer relevant
- Phase-specific procedures that are outdated

Active documentation lives in `docs/` and is updated with every code change.
