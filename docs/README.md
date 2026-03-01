# Jervis Documentation

> **Note:** All documentation is in English. UI language is Czech.

## Core Documentation

These documents are the **single source of truth** and are kept up-to-date with every change:

### Quick Start

- **[guidelines.md](guidelines.md)** - **START HERE** - Essential rules and patterns for coding agents

### System Architecture

- **[architecture.md](architecture.md)** - System architecture, modules, services, workspace, GPU/CPU routing
- **[structures.md](structures.md)** - Data processing pipeline, task states, background engine

### Service-Specific

- **[knowledge-base.md](knowledge-base.md)** - **SSOT** for Knowledge Base: graph schema, RAG, ingest, normalization
- **[orchestrator-final-spec.md](orchestrator-final-spec.md)** - Python Orchestrator specification: async dispatch, approval flow
- **[orchestrator-detailed.md](orchestrator-detailed.md)** - Detailed orchestrator reference: nodes, LLM calls, K8s Jobs, state machine
- **[ui-design.md](ui-design.md)** - **SSOT** for UI: adaptive layout, component signatures, patterns, dialogs

### Implementation Details

- **[implementation.md](implementation.md)** - Implementation details, conventions, patterns, best practices

---

## Documentation Workflow

### When to Update Docs

**After every code change**, update relevant documentation:

| Changed Code                     | Update These Docs                                          |
|----------------------------------|------------------------------------------------------------|
| UI component or pattern          | `ui-design.md` + `guidelines.md`                           |
| Data processing / routing        | `structures.md`                                            |
| KB / graph schema                | `knowledge-base.md`                                        |
| Architecture / module boundaries | `architecture.md`                                          |
| Orchestrator behavior            | `orchestrator-final-spec.md` or `orchestrator-detailed.md` |
| Implementation conventions       | `implementation.md`                                        |

### Pull Request Checklist

- [ ] Code changes done
- [ ] Relevant docs updated
- [ ] No duplicated helpers (check `ClientsSharedHelpers.kt`)
- [ ] All interactive elements ≥ 44dp touch target
- [ ] Cards use `CardDefaults.outlinedCardBorder()` or `JCard`
- [ ] Loading/empty/error states use `JCenteredLoading` / `JEmptyState` / `JErrorState`
- [ ] No hardcoded paths (use `DirectoryStructureService`)
- [ ] DB queries filter at DB level (no `.filter {}` in Kotlin)

---

## Quick Links

- Project instructions: `../CLAUDE.md` (loaded into every Claude Code session)
- Build scripts: `../k8s/` (deployment scripts for all services)
- Memory: `~/.claude/projects/-Users-damekjan-git-jervis/memory/` (persistent across sessions)
