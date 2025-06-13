# Modular Structure for Jervis Application

This directory contains the modular components of the Jervis application, as described in the requirements:

- **git-watcher**: Monitors repositories and new commits
- **indexer**: Extracts structure, creates embeddings (code and text)
- **vectordb**: Integration with Qdrant for vector search
- **rag-core**: RAG orchestration and context management for LLM
- **llm-coordinator**: Decides on query decomposition, LLM selection, and prompt construction
- **assistant-api**: Provides OpenAI-compatible REST API for tools like IntelliJ
- **tray-ui**: System tray icon with GUI for project and key management (Kotlin Compose Desktop)

Each module is implemented as a package within this directory.