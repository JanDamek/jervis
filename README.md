# Jervis

A Kotlin-first assistant and service toolkit for reasoning over codebases, documents, and conversations. It provides a Spring Boot backend with optional JavaFX client and Kotlin Multiplatform shared modules. Jervis aims to help developers explore repositories, explain code, generate tasks, propose architectures, and automate routine actions while following strict engineering and compliance guidelines.

---

## Overview

- Purpose: unify conversations, code, git history, and docs into a coherent memory that can generate insights and actions.
- Key features:
  - End-to-end context ingestion (code, docs, git metadata)
  - Task and architecture suggestions with rationale
  - MCP-based tool execution and integrations
  - Pluggable LLM providers with automatic routing
  - Safe, observable execution (logging, tracing, limits)
- Target users:
  - Software engineers, tech leads, solution architects
  - Developer experience and platform teams

---

## Technology Stack

- Language: Kotlin
- Backend: Spring Boot (coroutines-first)
- UI: JavaFX (planned/optional) and CLI utilities
- Multiplatform: Kotlin Multiplatform for shared models/utilities
- Datastores: MongoDB, Qdrant (vector)
- LLMs: Anthropic Claude, OpenAI GPT, Ollama, LM Studio
- Protocols: Model Context Protocol (MCP)
- Build Tool: Maven

---

## Architecture

```
                 +-----------------------+
                 |       Developers      |
                 |  (CLI / JavaFX UI)    |
                 +-----------+-----------+
                             |
                             |  Commands, Queries
                             v
+----------------------------+-----------------------------+
|                        Spring Boot Server                |
|  - HTTP/WS API                                             |
|  - Orchestration & services (coroutines)                  |
|  - Providers routing (LLM selection)                      |
|  - MCP tool adapters                                      |
+---------------------+--------------------+----------------+
                      |                    |
                      |                    |
                      v                    v
            +---------+----+       +------+---------+
            |   MongoDB    |       |     Qdrant     |
            |  documents   |       |  vector index  |
            +--------------+       +----------------+
```

---

## Setup (Development)

Prerequisites:
- JDK 21+
- Maven 3.9+
- Node 18+ (only if building UI assets)
- Docker (optional; for local MongoDB and Qdrant)

Steps:
1. Clone the repo and open in IntelliJ IDEA (Kotlin plugin enabled).
2. Configure environment variables/secrets as needed for LLMs:
   - ANTHROPIC_API_KEY, OPENAI_API_KEY
   - Optional: OLLAMA_ENDPOINT, LM_STUDIO_ENDPOINT
3. Start local services (optional):
   - MongoDB: `docker run -p 27017:27017 mongo:7`
   - Qdrant: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant:latest`
4. Build and run the server:
   - `mvn -q -DskipTests package`
   - `mvn spring-boot:run`
5. Verify health: open `http://localhost:8080` (or configured port).

---

## Compliance

This repository follows the Kotlin-first engineering rules defined in .junie/guidelines.md. All code and documentation must:
- Use Kotlin and coroutines first; avoid Java-style patterns
- Keep English-only identifiers and documentation
- Enforce immutability and null-safety (no `!!` in domain/service code)
- Prefer sealed types/strategies over deeply nested conditionals
- Use structured logging and avoid println

For the full rule set and rationale, see .junie/guidelines.md.

---

## Acceptance Criteria

- README describes project purpose, features, and target users
- Technology stack lists Kotlin, Spring Boot, JavaFX, and Kotlin Multiplatform
- Dedicated Compliance section explicitly links to .junie/guidelines.md
- Setup instructions included for local development
- Architecture diagram illustrates user (CLI/JavaFX) and server components with data stores
- Content is English-only and consistent with the guidelines

---

## Notes

- JavaFX client is optional; current focus is the Spring Boot server with Kotlin-first coroutines.
- MCP integration is via adapters; additional tools can be added over time.
