Jervis — Project Overview

This repository uses a single source of truth (SSOT) for all engineering and UI rules. If you are looking for how to code, design, or configure the system, start here:

- Engineering & Architecture Guidelines (SSOT): docs/guidelines.md
- UI Design System (SSOT): docs/ui-design.md

Notes for developers (current dev mode):
- Mobile-first shared UI: all screens live in shared/ui-common and must work on Desktop and iPhone (Compose Multiplatform).
- Fail-fast: no deprecations, no compatibility layers; data types can evolve (breaking changes allowed during development).
- Secrets visible in UI: do not mask passwords/tokens in the UI. This app is private; treat it as a development tool.
- DocumentDB (Mongo): no encryption; store plaintext during development.

Runtime & models
- Model/providers and endpoints are defined in backend/server/src/main/resources/models-config.yaml.
- Ollama is used for both LLM and embeddings; embeddings run on the CPU instance.

RAG & vector store
- Weaviate is used with vectorizer="none". Embedding dimensions must match model settings in models-config.yaml.
