---

## Usage Guide

### API Keys
- Set **Anthropic** and **OpenAI** keys in settings  
- Optional: configure **Ollama/LM Studio endpoints** for local models  

### MCP Examples
- `"run command ls -la"`  
- `"send email to user@example.com with subject 'Test'"`  

### Token Limits
- Default Anthropic rate limits: **20k input / 4k output tokens/min**  
- Fallback: OpenAI auto-switch if limit exceeded  

### Local Models
- **GPU small model** – quick tasks (summaries, titles)  
- **CPU larger model** – complex reasoning, code explanations  
- **External (Ollama/LM Studio)** – configurable providers  
- **Automatic routing** – based on query complexity  

---

## Architecture – Onion Refactoring

- **Domain** – core objects, rules
- **Service Layer** – orchestration & business logic
- **Repository Layer** – persistence (Mongo + Qdrant)
- **Separation of Concerns** → clean testability, maintainability, scalability

---

## Vision & Benefits

- Understands **full project context**
- Connects **conversations, code, git, docs** into one memory
- **Generates tasks**, suggests architecture, explains code
- Continuously **learns and adapts** to project conventions

---

## Technology Stack

- **Language**: Kotlin
- **Framework**: Spring Boot (WebFlux)
- **Database**: MongoDB
- **Vector DB**: Qdrant
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