# Jervis Documentation

> **Note:** All documentation is in English. UI language is Czech.

## Core Documentation (Always Current)

These documents are the **single source of truth** for their respective areas and are kept up-to-date with every change:

### Architecture & Design

- **[architecture.md](architecture.md)** - System architecture, module boundaries, service interactions, high-level design
- **[ui-design.md](ui-design.md)** - **SSOT** for UI layout, adaptive patterns, component signatures, dialogs, typography, spacing
- **[structures.md](structures.md)** - Data processing pipeline, CPU/GPU routing, qualification flow, BackgroundEngine

### Implementation Guides

- **[guidelines.md](guidelines.md)** - Quick reference with inline code patterns, decision trees, component usage
- **[implementation.md](implementation.md)** - Implementation details, conventions, patterns, best practices
- **[reference.md](reference.md)** - Terminology, naming conventions, architecture layers, quick lookup

### Service-Specific

- **[knowledge-base.md](knowledge-base.md)** - **SSOT** for Knowledge Base: graph schema, RAG, ingest, normalization, indexers
- **[orchestrator-final-spec.md](orchestrator-final-spec.md)** - Python Orchestrator specification: async dispatch, approval flow, concurrency
- **[orchestrator-detailed.md](orchestrator-detailed.md)** - Detailed orchestrator reference: nodes, LLM calls, K8s Jobs, state machine

### Planning & Tasks

- **[TODO.md](TODO.md)** - Active TODO list, planned features, known issues

---

## Archive

Historical documents, completed migrations, and outdated analyses are in **[archive/](archive/)**:

- `kb-analysis-and-recommendations.md` - KB analysis from 2026-02-05 (superseded by knowledge-base.md)
- `koog-audit.md` - Koog framework removal (completed 2026-02-07)
- `task-scheduling-analysis.md` - Scheduler bug fixes (completed 2026-02-11)
- `operations.md` - OAuth2 Phase 1 deployment checklist (historical)

---

## Documentation Workflow

### When to Update Docs

**After every code change**, update relevant documentation:

| Changed Code | Update These Docs |
|--------------|-------------------|
| UI component or pattern | `ui-design.md` (SSOT) + `guidelines.md` (inline examples) |
| Data processing / routing | `structures.md` |
| KB / graph schema | `knowledge-base.md` |
| Architecture / module boundaries | `architecture.md` |
| Implementation conventions | `implementation.md` |
| Orchestrator behavior | `orchestrator-final-spec.md` or `orchestrator-detailed.md` |

### Pull Request Checklist

- [ ] Code changes done
- [ ] Relevant docs updated
- [ ] No outdated information left
- [ ] Cross-references still valid

---

## Quick Links

- Project instructions: `../CLAUDE.md` (loaded into every Claude Code session)
- Build scripts: `../k8s/` (deployment scripts for all services)
- Memory: `~/.claude/projects/-Users-damekjan-git-jervis/memory/` (persistent across sessions)
