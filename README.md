# Jervis

A Kotlin-first assistant and service toolkit for reasoning over codebases, documents, and conversations. It provides a
Spring Boot backend with optional JavaFX client and Kotlin Multiplatform shared modules. Jervis aims to help developers
explore repositories, explain code, generate tasks, propose architectures, and automate routine actions while following
strict engineering and compliance guidelines.

---

## Overview

- Purpose: unify conversations, code, git history, and docs into a coherent memory that can generate insights and
  actions.
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
- Datastores: MongoDB, Weaviate (vector)
- LLMs: Anthropic Claude, OpenAI GPT, Ollama, LM Studio
- Protocols: Model Context Protocol (MCP)
- Build Tool: Gradle (Kotlin DSL)

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
            |   MongoDB    |       |    Weaviate    |
            |  documents   |       |  vector index  |
            +--------------+       +----------------+
```

---

## Setup (Development)

Prerequisites:

- JDK 21+
- Gradle 8.7+ (or use the Gradle Wrapper if present)
- Node 18+ (only if building UI assets)
- Docker (optional; for local MongoDB and Weaviate)

Steps:

1. Clone the repo and open in IntelliJ IDEA (Kotlin plugin enabled).
2. Configure environment variables/secrets as needed for LLMs:
    - ANTHROPIC_API_KEY, OPENAI_API_KEY
    - Optional: OLLAMA_ENDPOINT, LM_STUDIO_ENDPOINT
3. Start local services (optional):
    - MongoDB: `docker run -p 27017:27017 mongo:7`
    - Weaviate:
      `docker run -p 8080:8080 -p 50051:50051 semitechnologies/weaviate:latest --host 0.0.0.0 --port 8080 --scheme http`
4. Build and run the server (Gradle):
    - Build: `gradle -q build -x test`
    - Run: `gradle bootRun`
    - Tip: If the Gradle Wrapper is available, prefer `./gradlew build` and `./gradlew bootRun`
5. Verify health: open `http://localhost:8080` (or configured port).

---

## Compliance

This repository follows the Kotlin-first engineering rules defined in .junie/guidelines.md. All code and documentation
must:

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

- **Language**: Kotlin
- **Framework**: Spring Boot (WebFlux)
- **Database**: MongoDB
- **Vector DB**: Weaviate
- **UI**: Java Swing + Static Web (persona selection)
- **LLMs**: Anthropic Claude, OpenAI GPT, Ollama, LM Studio
- **Protocols**: MCP (via Koog library)
- **Build Tool**: Maven

---

## Persona Selection (No Auth)

A lightweight startup screen lets users choose a persona without authentication.

- Screen path: open the app root at `/` (served from `resources/static/index.html`).
- Roles: Developer, Designer, Manager.
- Storage: role is saved in the HTTP session (no database persistence).
- API:
    - `GET /api/session/role` → `{ "role": "DEVELOPER|DESIGNER|MANAGER|null" }`
    - `POST /api/session/role` with `{ "role": "DEVELOPER|DESIGNER|MANAGER" }`
- Mockups: Figma placeholder link is included in the page footer.

## Server–User Component Split

Best practice: Server–UI communication (Desktop and Mobile)

- Contracts live in :common-api as `I***Service` interfaces annotated with `@HttpExchange`.
- Server controllers implement these interfaces directly (fail fast, no DTO leakage across layers).
- Desktop and Android create clients from the same interfaces using `HttpServiceClientFactory` in `:api-client`.
- iOS is non‑JVM; recommended approach is either: (a) implement the same interfaces with a Ktor client on iOS, or (b) (
  optional) introduce a thin `service-mobile` adapter that reuses `:api-client` and exposes mobile‑friendly endpoints.
  Desktop remains the primary UI.

See docs/architecture.md for the architecture diagram, API contract (/api/v0), and rationale for the separation between
the Server (APIs/business logic) and User (UI/client) layers.

- JavaFX client is optional; current focus is the Spring Boot server with Kotlin-first coroutines.
- MCP integration is via adapters; additional tools can be added over time.

---

## Monorepo Structure

As of 2025-10-12, this repository is organized as a mono‑repo to host all platforms:

```
jervis/
├── core/     (shared DTOs, business logic — Kotlin Multiplatform)
├── server/   (Spring Boot backend — Kotlin, WebFlux)
├── desktop/  (JavaFX UI — Kotlin)
├── ios/      (Swift client)
└── android/  (Kotlin Android app)
```

- Rationale and details: see docs/mono-repo-structure.md
- Dependency management plan: see docs/dependency-management-plan.md
- CI/CD: see .github/workflows/monorepo-ci.yml (path‑based builds for affected modules)

---

## Docker Desktop disk space (macOS) — fixing "no space left on device"

If Docker builds fail with messages like "no space left on device" even though your macOS drive has plenty of free
space, the cause is usually Docker Desktop’s internal disk image quota. Docker stores images, layers, and build caches
in a dedicated disk image with a fixed maximum size. When that quota is exhausted, builds fail regardless of host free
space.

Follow these steps to resolve it.

1) Increase Docker Desktop disk image size (recommended)

- Open Docker Desktop → Settings → Resources → Advanced
- Find "Disk image size" and move the slider up (128–256 GB is typical for heavy builds like OCR + Joern)
- Click "Apply & Restart"
- Optional: Settings → Resources → Advanced → "Disk image location" to move the image to a larger/faster volume (if
  available in your Docker Desktop version)

2) Prune unused Docker data to free space immediately

- Inspect overall usage:
    - docker system df
- Aggressively prune unused objects (images/containers/networks/volumes):
    - docker system prune -af --volumes
- Prune classic builder cache:
    - docker builder prune -af
- If you use Buildx (recommended), also prune BuildKit caches:
    - docker buildx prune -af
- Inspect Buildx cache usage per builder:
    - docker buildx du -v

3) Re-run the build (Apple Silicon building for Intel target)

- Default local build:
    - docker build -t jervis:latest .
- Force Intel/amd64 on Apple Silicon (useful for parity with Intel servers):
    - docker buildx build --platform=linux/amd64 -t jervis:latest .
- If cache corruption is suspected, add --no-cache (slower, but fresh):
    - docker buildx build --platform=linux/amd64 --no-cache -t jervis:latest .

Notes specific to this repository

- The Dockerfile already minimizes peak disk usage when fetching Joern by streaming the 1.8 GB ZIP directly into the
  extractor (no large temporary ZIP file on disk).
- The OCR base stage installs many language packs and tools; combined with Joern, total image size is substantial.
  Increasing Docker Desktop’s disk image size is usually necessary for a smooth build experience.
- After resizing/pruning, if you still encounter space errors, verify the new quota took effect (Docker Desktop →
  Settings → Resources) and re-check usage with docker system df.

---

## Docker multi-stage images and how to build them

This repository uses a single multi‑stage Dockerfile that defines multiple final runtime images:

- runtime-server (Spring Boot orchestrator)
- runtime-weaviate (Weaviate vector database)
- runtime-tika (document parsing service)
- runtime-joern (code analysis service)
- runtime-whisper (speech‑to‑text service)

Important: A plain `docker build` only produces the last stage (runtime-server). To build other images, specify the
target explicitly with `--target` and provide your own tag, for example:

- Server (last stage, built by default):
    - docker build -t jervis-runtime-server:latest .
- Weaviate:
    - docker build --target runtime-weaviate -t jervis-runtime-weaviate:latest .
    - docker run -d --rm --name weaviate -p 8080:8080 jervis-runtime-weaviate:latest
    - Verify readiness: curl -sf http://localhost:8080/v1/.well-known/ready
- TiKa:
    - docker build --target runtime-tika -t jervis-runtime-tika:latest .
- Joern:
    - docker build --target runtime-joern -t jervis-runtime-joern:latest .
- Whisper:
    - docker build --target runtime-whisper -t jervis-runtime-whisper:latest .

CI workflow

- A GitHub Actions workflow at `.github/workflows/docker-images.yml` builds all targets in a matrix and runs a smoke
  test for `runtime-weaviate` by starting the container and checking the readiness endpoint from the host.
- This ensures future PRs won’t break multi‑stage builds or the Weaviate runtime image.
