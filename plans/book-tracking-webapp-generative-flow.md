# GENERATIVE Flow: Web App pro evidenci knih

**Úkol:** "Naprogramuj web aplikaci pro evidenci knih"  
**Kategorie:** GENERATIVE (design → plan_epic → execute waves)  
**Očekávaný výstup:** Plně funkční web aplikace s backendem, frontendem, databází, testy, dokumentací a deploymentem

---

## 1. Architektonický přehled

### 1.1 Technologický stack (navrženo orchestrátorem)

| Vrstva | Technologie | Důvod volby | Agent |
|--------|-------------|-------------|-------|
| **Backend** | Spring Boot 3.4 + Kotlin | Typově bezpečný, JVM, mature ecosystem | OpenHands (komplexní) |
| **API** | REST + OpenAPI 3.0 | Standard, easy to document | Claude (code) |
| **Databáze** | PostgreSQL 16 | ACID, JSON support, proven | OpenHands (setup) |
| **ORM** | JPA + Hibernate | Spring Boot standard | Claude (CRUD) |
| **Frontend** | React 19 + TypeScript + Vite | Modern, fast, typed | OpenHands (setup) |
| **UI kit** | Material-UI (MUI) | Komponenty, theming | Claude (components) |
| **State** | React Query + Zustand | Server state + local state | Claude (integration) |
| **Testování** | JUnit 5 + Mockito (BE), Jest + React Testing Library (FE), Cypress (E2E) | Full coverage | Aider (test generation) |
| **Build** | Gradle (BE), npm (FE) | Standard tools | Claude (config) |
| **Docker** | Multi-stage builds | Production-ready | OpenHands (Dockerfiles) |
| **K8s** | Deployments + Services + Ingress + PVC | Scalable | Claude (manifests) |
| **CI/CD** | GitHub Actions | Integrated, popular | Aider (workflows) |
| **Dokumentace** | ADRs + OpenAPI + README | Architektura, API, usage | Respond node (LLM + KB) |

### 1.2 Architektura (monolit s modulární strukturou)

```
┌─────────────────────────────────────────────────────────────┐
│                     Kubernetes Cluster                      │
├─────────────────────────────────────────────────────────────┤
│  Ingress (nginx)                                            │
│    ↓                                                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │          Spring Boot Backend (Deployment)           │   │
│  │  ┌────────────┐  ┌────────────┐  ┌─────────────┐  │   │
│  │  │   Book     │  │   Author   │  │   Loan       │  │   │
│  │  │   Module   │  │   Module   │  │   Module    │  │   │
│  │  └────────────┘  └────────────┘  └─────────────┘  │   │
│  │         │ JPA/Hibernate │                          │   │
│  │         └────────────────┘                          │   │
│  │                    PostgreSQL (PVC)                 │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           React Frontend (Deployment)               │   │
│  │  ┌────────────┐  ┌────────────┐  ┌─────────────┐  │   │
│  │  │   Pages    │  │ Components │  │   Hooks      │  │   │
│  │  │  - Home    │  │  - BookCard│  │  - useBooks  │  │   │
│  │  │  - Books   │  │  - BookForm│  │  - useAuth   │  │   │
│  │  │  - Authors │  │  - NavBar  │  │             │  │   │
│  │  │  - Loans   │  │           │  │             │  │   │
│  │  └────────────┘  └────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 API Endpoints (OpenAPI 3.0)

```
GET    /api/v1/books              # List all books (pagination, filter)
POST   /api/v1/books              # Create book
GET    /api/v1/books/{id}         # Get book details
PUT    /api/v1/books/{id}         # Update book
DELETE /api/v1/books/{id}         # Delete book

GET    /api/v1/authors            # List authors
POST   /api/v1/authors            # Create author
GET    /api/v1/authors/{id}       # Get author
PUT    /api/v1/authors/{id}       # Update author
DELETE /api/v1/authors/{id}       # Delete author

GET    /api/v1/loans              # List loans (active, returned)
POST   /api/v1/loans              # Create loan (borrow book)
PUT    /api/v1/loans/{id}/return  # Return book

GET    /api/v1/statistics         # Dashboard stats (books, authors, loans)
```

### 1.4 Databázové schéma

```sql
-- authors table
CREATE TABLE authors (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    birth_year INT,
    death_year INT,
    nationality VARCHAR(100),
    biography TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- books table
CREATE TABLE books (
    id BIGSERIAL PRIMARY KEY,
    isbn VARCHAR(20) UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    author_id BIGINT REFERENCES authors(id) ON DELETE SET NULL,
    published_year INT,
    genre VARCHAR(100),
    description TEXT,
    total_copies INT DEFAULT 1,
    available_copies INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- loans table
CREATE TABLE loans (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT REFERENCES books(id) ON DELETE CASCADE,
    user_name VARCHAR(255) NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    borrowed_at TIMESTAMP DEFAULT NOW(),
    due_date DATE NOT NULL,
    returned_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_books_author ON books(author_id);
CREATE INDEX idx_books_title ON books(title);
CREATE INDEX idx_loans_book ON loans(book_id);
CREATE INDEX idx_loans_user ON loans(user_email);
CREATE INDEX idx_loans_due ON loans(due_date) WHERE returned_at IS NULL;
```

---

## 2. GENERATIVE Flow: intake → design → plan_epic → waves

### 2.1 Task classification (intake node)

**User query:** "Naprogramuj web aplikaci pro evidenci knih"

**Intake LLM analysis:**
```json
{
  "task_category": "generative",
  "task_action": "code",
  "external_refs": [],
  "complexity": "complex",
  "goal_clear": true,
  "clarification_questions": [],
  "reasoning": "User wants a complete web application from scratch. This requires architecture design, multiple components (backend, frontend, database), and deployment. GENERATIVE category."
}
```

**Routing:** `intake` → `evidence_pack` → `design` (GENERATIVE path)

### 2.2 Evidence pack (KB context)

**Prefetch KB context:**
- Spring Boot best practices (from KB)
- React + TypeScript patterns (from KB)
- PostgreSQL schema design (from KB)
- Docker + K8s deployment templates (from KB)
- CI/CD workflows for Java/React (from KB)

**EvidencePack:**
```json
{
  "kb_results": [
    {"source": "spring-boot-best-practices", "content": "..."},
    {"source": "react-typescript-patterns", "content": "..."},
    {"source": "postgresql-schema-design", "content": "..."},
    {"source": "docker-multistage-build", "content": "..."},
    {"source": "k8s-deployment-patterns", "content": "..."}
  ],
  "tracker_artifacts": [],
  "chat_history_summary": "",
  "external_refs": [],
  "facts": ["Web app needed", "Book tracking domain", "Full stack required"],
  "unknowns": ["Specific UI requirements", "Authentication needs", "Scale requirements"]
}
```

### 2.3 Design node (architektonické rozhodnutí)

**Účel:** Navrhnout celkovou architekturu, technologický stack, modulární strukturu, API design, databázové schéma.

**LLM prompt (CLOUD_REASONING - Anthropic Claude Sonnet):**
```
You are an enterprise software architect. Design a complete web application for book tracking.

Context:
- Client: Generic (no specific constraints)
- Project: Book tracking system
- Evidence: [KB results about Spring Boot, React, PostgreSQL]
- Unknowns: UI specifics, auth, scale

Deliverable JSON:
{
  "architecture": "monolith_with_modules",
  "backend": {
    "framework": "spring_boot_kotlin",
    "java_version": 21,
    "spring_boot_version": "3.4.0",
    "modules": ["book", "author", "loan", "statistics"],
    "api_style": "rest_openapi",
    "authentication": "none",  // default, can be changed later
    "database": "postgresql"
  },
  "frontend": {
    "framework": "react_typescript",
    "build_tool": "vite",
    "ui_library": "material_ui",
    "state_management": ["react_query", "zustand"],
    "routing": "react_router_v7"
  },
  "database": {
    "type": "postgresql",
    "version": "16",
    "orm": "jpa_hibernate",
    "schema": "see tables: authors, books, loans"
  },
  "deployment": {
    "containerization": "docker_multistage",
    "orchestration": "kubernetes",
    "ingress": "nginx",
    "persistent_storage": "pvc_for_postgres"
  },
  "testing": {
    "unit": "junit5_mockito",
    "integration": "testcontainers",
    "e2e": "cypress",
    "coverage_target": 0.8
  },
  "ci_cd": {
    "platform": "github_actions",
    "jobs": ["build", "test", "docker_build", "deploy"]
  },
  "documentation": {
    "api": "openapi_3.0",
    "architecture": "adrs",
    "readme": true
  },
  "approval_required": ["architecture", "database_schema", "api_design"]
}
```

**Interrupt:** Pokud `approval_required` obsahuje "architecture", volá `interrupt()` s:
```json
{
  "action": "generative_design",
  "description": "Navržená architektura: Spring Boot monolit s moduly, React frontend, PostgreSQL. Schválit?",
  "details": {
    "backend": "Spring Boot 3.4 (Kotlin)",
    "frontend": "React 19 + TypeScript + Vite",
    "database": "PostgreSQL 16",
    "deployment": "K8s + Docker",
    "ci_cd": "GitHub Actions"
  },
  "risk_level": "MEDIUM"
}
```

**User approval:** "Ano, pokračuj."

**Output:** `final_result` obsahuje architektonický design (text + JSON). State: `design_approved = true`.

### 2.4 plan_epic node (rozdělení na waves)

**Účel:** Rozložit design do epiců (waves) s priority a dependencies.

**LLM prompt (LOCAL_STANDARD - Qwen):**
```
You are a technical planner. Break down the book tracking web app into implementation waves.

Design approved:
- Backend: Spring Boot monolith with modules (book, author, loan, statistics)
- Frontend: React + TypeScript + Vite + MUI
- Database: PostgreSQL with JPA/Hibernate
- Deployment: Docker + K8s
- Testing: JUnit, Jest, Cypress
- CI/CD: GitHub Actions

Create 5 waves with goals and dependencies:

Wave 1: Foundation & Backend Core
  - Setup project structure (Gradle, Spring Boot)
  - Database schema (PostgreSQL, Flyway)
  - JPA entities (Book, Author, Loan)
  - Repository layer
  - Basic REST controllers (CRUD)
  - Global exception handling
  - Validation

Wave 2: Business Logic & API
  - Service layer implementation
  - Business rules (loan constraints, availability)
  - DTOs and mappers
  - API documentation (OpenAPI)
  - Integration tests (Testcontainers)

Wave 3: Frontend Core
  - Vite + React + TypeScript setup
  - Routing (React Router)
  - State management (React Query + Zustand)
  - UI components (MUI)
  - Pages: Book list, Book detail, Author list
  - API integration

Wave 4: Advanced Features
  - Loan management UI
  - Statistics dashboard
  - Search and filtering
  - Form validation
  - Error handling UI

Wave 5: Deployment & Quality
  - Docker multi-stage builds (backend + frontend)
  - K8s manifests (Deployments, Services, Ingress, PVC)
  - GitHub Actions CI/CD
  - E2E tests (Cypress)
  - Documentation (ADRs, README, API guide)

Output JSON:
{
  "waves": [
    {
      "id": "wave1",
      "title": "Foundation & Backend Core",
      "description": "...",
      "goals": [
        {
          "id": "w1g1",
          "title": "Setup Spring Boot project",
          "description": "Create Gradle build, application.yml, main class",
          "complexity": "simple",
          "dependencies": []
        },
        {
          "id": "w1g2",
          "title": "Implement database schema",
          "description": "Create SQL migrations for authors, books, loans tables",
          "complexity": "simple",
          "dependencies": ["w1g1"]
        },
        ...
      ]
    },
    ...
  ]
}
```

**Output:** `goals` (flattened list with wave_id metadata), `current_goal_index = 0`

### 2.5 Execution loop (waves → goals → steps)

**Flow:**
```
plan_epic → select_goal (w1g1) → plan_steps → execute_step → evaluate
       → advance_step (w1g2) → ... → advance_goal (w1g2) → select_goal
       → ... → all goals done → git_operations → finalize
```

**Goal dependencies:** Respect wave order (w1 → w2 → w3 → w4 → w5). Within wave, goals may have dependencies.

---

## 3. Wave-by-wave breakdown

### Wave 1: Foundation & Backend Core

**Goals (5):**

| Goal ID | Title | Complexity | Dependencies | Steps |
|---------|-------|------------|--------------|-------|
| w1g1 | Setup Spring Boot project | simple | [] | 1 step (CODE) |
| w1g2 | Implement database schema | simple | w1g1 | 1 step (CODE) |
| w1g3 | Create JPA entities | medium | w1g2 | 1 step (CODE) |
| w1g4 | Implement repository layer | simple | w1g3 | 1 step (CODE) |
| w1g5 | Basic REST CRUD controllers | medium | w1g4 | 1 step (CODE) |

**Step details (w1g1 example):**

```python
CodingStep(
    index=0,
    instructions="""
Create a Spring Boot 3.4 project with Kotlin.

Requirements:
1. Use Gradle Kotlin DSL (build.gradle.kts)
2. Dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa, postgresql-r2dbc, validation, lombok (or kotlinx.serialization)
3. Application properties (application.yml):
   - server.port=8080
   - spring.datasource.url=jdbc:postgresql://localhost:5432/booktracker
   - spring.datasource.username=postgres
   - spring.datasource.password=postgres
   - spring.jpa.hibernate.ddl-auto=none (use Flyway)
   - spring.jpa.show-sql=true
4. Main class: @SpringBootApplication BookTrackerApplication.kt
5. Directory structure:
   src/main/kotlin/com/jervis/booktracker/
     - BookTrackerApplication.kt
     - config/
     - controller/
     - service/
     - repository/
     - model/
     - dto/
   src/main/resources/
     - application.yml
     - data.sql (optional test data)
6. Include Dockerfile for backend (multi-stage, uses gradle with jdk21, runs with jre21)

Return: List of created files with paths.
""",
    step_type=StepType.CODE,
    agent_type=AgentType.OPENHANDS  # Complex setup, multiple files
)
```

**Agent selection:**
- w1g1: OpenHands (complex project setup, many files)
- w1g2: Claude (SQL migrations, straightforward)
- w1g3: Claude (JPA entities, annotations)
- w1g4: Claude (repositories, JPA)
- w1g5: OpenHands (REST controllers, many endpoints)

**Evaluation criteria:**
- All files created in correct paths
- Gradle builds successfully
- Application.yml configured
- Dockerfile present
- No compilation errors

---

### Wave 2: Business Logic & API

**Goals (4):**

| Goal ID | Title | Complexity | Dependencies | Steps |
|---------|-------|------------|--------------|-------|
| w2g1 | Service layer implementation | medium | w1g5 | 3 steps (CODE) |
| w2g2 | DTOs and mappers | simple | w2g1 | 1 step (CODE) |
| w2g3 | OpenAPI documentation | simple | w2g2 | 1 step (CODE) |
| w2g4 | Integration tests | medium | w2g3 | 1 step (CODE) |

**w2g1 steps:**
1. BookService (CRUD + business rules)
2. AuthorService (CRUD)
3. LoanService (borrow, return, validation)

**Agent selection:** Claude for all (focused coding, moderate complexity)

---

### Wave 3: Frontend Core

**Goals (4):**

| Goal ID | Title | Complexity | Dependencies | Steps |
|---------|-------|------------|--------------|-------|
| w3g1 | React + Vite + TypeScript setup | medium | [] (independent) | 1 step (CODE) |
| w3g2 | UI component library (MUI) | simple | w3g1 | 1 step (CODE) |
| w3g3 | Routing and state management | medium | w3g2 | 1 step (CODE) |
| w3g4 | Book list and detail pages | medium | w3g3 | 2 steps (CODE) |

**w3g1 step (OpenHands):**
```
Initialize Vite React TypeScript project.
Install dependencies: react-router-dom, @mui/material, @emotion/react, @emotion/styled, axios, zustand, @tanstack/react-query.
Configure tsconfig.json, vite.config.ts.
Create directory structure:
  src/
    components/
    pages/
    hooks/
    services/
    store/
    types/
    utils/
Create main.tsx, App.tsx with Router.
Return: package.json, all config files, src/ structure.
```

**Agent selection:**
- w3g1: OpenHands (project setup)
- w3g2: Claude (MUI theme, base components)
- w3g3: Claude (routing, state)
- w3g4: OpenHands (pages, API integration)

---

### Wave 4: Advanced Features

**Goals (3):**

| Goal ID | Title | Complexity | Dependencies | Steps |
|---------|-------|------------|--------------|-------|
| w4g1 | Loan management UI | medium | w3g4, w2g3 | 2 steps (CODE) |
| w4g2 | Statistics dashboard | medium | w4g1 | 1 step (CODE) |
| w4g3 | Search, filtering, validation | medium | w4g2 | 2 steps (CODE) |

**w4g1 steps:**
1. Loan form component (borrow book)
2. Loan list with return action

**Agent selection:** Claude (focused UI work)

---

### Wave 5: Deployment & Quality

**Goals (5):**

| Goal ID | Title | Complexity | Dependencies | Steps |
|---------|-------|------------|--------------|-------|
| w5g1 | Docker multi-stage builds | medium | w2g4, w3g4 | 2 steps (CODE) |
| w5g2 | K8s manifests | medium | w5g1 | 1 step (CODE) |
| w5g3 | GitHub Actions CI/CD | medium | w5g2 | 1 step (CODE) |
| w5g4 | E2E tests (Cypress) | medium | w3g4 | 1 step (CODE) |
| w5g5 | Documentation | simple | w5g4 | 1 step (RESPOND) |

**w5g1 steps:**
1. Backend Dockerfile (Gradle → JAR → JRE)
2. Frontend Dockerfile (npm build → nginx)

**w5g5 step (RESPOND):**
```
Generate comprehensive documentation:
1. ADRs (001-project-structure, 002-backend-framework, 003-frontend, 004-database, 005-deployment)
2. README.md (setup, build, run, test)
3. API guide (OpenAPI summary, example requests)
4. Deployment guide (K8s, Docker)
```

**Agent selection:**
- w5g1: OpenHands (Dockerfiles)
- w5g2: Claude (K8s YAML)
- w5g3: Aider (GitHub Actions YAML)
- w5g4: Aider (Cypress tests)
- w5g5: RESPOND node (LLM generates docs)

---

## 4. Full Goals list (flattened)

```
w1g1: Setup Spring Boot project
w1g2: Implement database schema
w1g3: Create JPA entities
w1g4: Implement repository layer
w1g5: Basic REST CRUD controllers

w2g1: BookService implementation
w2g2: AuthorService implementation
w2g3: LoanService implementation
w2g4: DTOs and mappers
w2g5: OpenAPI documentation
w2g6: Integration tests

w3g1: React + Vite + TypeScript setup
w3g2: MUI component library
w3g3: Routing and state management
w3g4: Book list page
w3g5: Book detail page
w3g6: Author list page

w4g1: Loan form component
w4g2: Loan list with return
w4g3: Statistics dashboard
w4g4: Search and filtering
w4g5: Form validation

w5g1: Backend Dockerfile
w5g2: Frontend Dockerfile
w5g3: K8s manifests
w5g4: GitHub Actions CI/CD
w5g5: E2E tests
w5g6: Documentation
```

**Total:** 21 goals, ~25 steps

---

## 5. Approval flow (git_operations)

### 5.1 Commit approval gates

**ProjectRules:**
```yaml
require_approval_commit: true   # User must approve each wave commit
require_approval_push: false    # Auto-push after commit (or true if desired)
auto_push: false                # Manual push after review
```

**Git operations node:**
- After each wave (every 4-5 goals), `git_operations` node triggers
- Creates branch: `task/{taskId}` (from ProjectRules.branch_naming)
- Commits with prefix: `task({taskId}): Wave 1 - Foundation & Backend Core`
- **Interrupt before commit:** User sees summary of changes, approves
- If approved: Claude agent (ALLOW_GIT=true) performs commit
- If push approval enabled: second interrupt for push

**Interrupt message:**
```
Schválení: Commit změn pro Wave 1 (5 goals, 23 souborů)

Změněné soubory:
- backend/build.gradle.kts
- backend/src/main/kotlin/...
- backend/Dockerfile
- ...

Zpráva: "task(abc123): Wave 1 - Foundation & Backend Core"

Schválit commit? (ano/ne)
```

---

## 6. KB integration

### 6.1 Prefetch for coding agents

**Before each CODE step, `kb/prefetch.py` fetches:**

1. **Project-specific patterns** (if client has previous projects):
   - Spring Boot project structure
   - React component patterns
   - Database design patterns

2. **File-specific context** (for files being modified):
   - If modifying existing files, fetch their history and related KB entries

3. **Domain knowledge** (book tracking):
   - Library management best practices
   - Loan calculation algorithms
   - ISBN validation

**MCP for Claude Code:**
- Claude agent has MCP tool `kb_search(query)` for runtime queries
- Can ask: "How to implement pagination in Spring Boot?" → KB returns relevant snippets

---

## 7. Cross-goal context (GoalSummary)

**After each goal completes, `advance_goal` creates GoalSummary:**

```python
GoalSummary(
    goal_id="w1g1",
    title="Setup Spring Boot project",
    summary="Created Gradle build with Spring Boot 3.4, application.yml, main class, and Dockerfile. Project structure follows standard Spring conventions.",
    changed_files=[
        "build.gradle.kts",
        "src/main/kotlin/com/jervis/booktracker/BookTrackerApplication.kt",
        "src/main/resources/application.yml",
        "Dockerfile"
    ],
    key_decisions=[
        "Chose Kotlin over Java for type safety",
        "Used Gradle Kotlin DSL for build",
        "Selected PostgreSQL as database",
        "Docker multi-stage for production"
    ]
)
```

**Used in `plan_steps` for subsequent goals:**
- LLM sees previous goals' key decisions → maintains consistency
- Avoids redoing decisions (e.g., "use Kotlin" already decided)

---

## 8. Error handling and retries

### 8.1 Evaluation checks

**After each CODE step, `evaluate` node checks:**
1. **Success:** Agent reported success? If not → FAILED
2. **Forbidden files:** Check against `rules.forbidden_files` (e.g., `*.env`, `secrets/*`) → BLOCKED if violated
3. **Max changed files:** `len(changed_files) > rules.max_changed_files` (default 20) → WARNING (but acceptable)
4. **Compilation errors:** If agent output indicates errors → FAILED

**If FAILED or BLOCKED:**
- `evaluation.acceptable = false`
- `next_step` routes to `finalize` (skip remaining steps in current goal)
- `git_operations` still runs (partial progress saved)
- `finalize` generates error summary

### 8.2 Retry logic

- Failed steps are **not automatically retried** (user must approve re-run)
- Task state becomes `DISPATCHED_GPU` (FOREGROUND) or deleted (BACKGROUND)
- User can send new message to retry: "Zkus to znovu pro krok w2g1"
- New task created with same `orchestratorThreadId` → resume from checkpoint

---

## 9. Final report (finalize node)

**When all goals complete (or error), `finalize` generates:**

```markdown
# Web aplikace pro evidenci knih – Implementace dokončena

## Shrnutí
Implementována kompletní web aplikace pro správu knihoven s 21 cíli ve 5 vlnách.

## Architektura
- **Backend:** Spring Boot 3.4 (Kotlin), JPA/Hibernate, PostgreSQL
- **Frontend:** React 19 + TypeScript + Vite, Material-UI
- **Deployment:** Docker + Kubernetes
- **CI/CD:** GitHub Actions

## Klíčová rozhodnutí
1. Monolit s modulární strukturou (jednoduchá správa, vývoj)
2. PostgreSQL pro ACID a JSON podporu
3. React Query pro server state, Zustand pro UI state
4. Multi-stage Docker builds pro optimalizaci

## Výsledky
- **Backend:** 45 Java/Kotlin souborů, 12 REST endpointů
- **Frontend:** 32 React komponenty, 8 stránek
- **Testy:** 85 JUnit testů, 45 Jest testů, 12 Cypress scénářů (coverage 87%)
- **Dokumentace:** 5 ADRs, OpenAPI spec, README, deployment guide

## Branches
- `task/abc123` (committed, ready for push)
- `task/abc123-w1` (Wave 1)
- `task/abc123-w2` (Wave 2)
- ...

## Další kroky
1. Push do Git repozitáře (schváleno)
2. Deploy na staging prostředí
3. Uživatelský test
4. Production deploy po validaci
```

---

## 10. Mermaid diagram: Complete GENERATIVE flow

```mermaid
graph TD
    Start[User query: "Naprogramuj web aplikaci pro evidenci knih"] --> Intake
    Intake --> EvidencePack
    EvidencePack --> Design
    
    Design -->|Interrupt: approval| UserApproval[User approves design]
    UserApproval --> PlanEpic
    
    PlanEpic --> SelectGoal[w1g1: Setup Spring Boot]
    
    SelectGoal --> PlanSteps
    PlanSteps --> ExecuteStep[w1g1 step 1: CODE]
    ExecuteStep --> Evaluate
    Evaluate -->|acceptable| AdvanceStep
    Evaluate -->|not acceptable| FinalizeError[Finalize with error]
    
    AdvanceStep -->|more steps| ExecuteStep
    AdvanceStep -->|goal done| AdvanceGoal
    AdvanceGoal -->|more goals| SelectGoal
    AdvanceGoal -->|wave done| GitOps{Interrupt: commit?}
    
    GitOps -->|User approves| GitCommit[Claude commits]
    GitCommit -->|push required?| GitPush{Interrupt: push?}
    GitPush -->|Yes, approved| DoPush[Claude pushes]
    GitPush -->|No| SelectGoal
    
    SelectGoal -->|all goals done| Finalize
    Finalize --> Done[Complete]
    
    FinalizeError --> Done
    
    style Design fill:#e1f5e1
    style PlanEpic fill:#e1f5e1
    style GitOps fill:#fff3e0
    style UserApproval fill:#ffecb3
```

---

## 11. ProjectRules for this task

```python
ProjectRules(
    branch_naming="task/{taskId}",
    commit_prefix="task({taskId}):",
    require_review=False,  # Skip code review for speed
    require_tests=True,    # Require tests
    require_approval_commit=True,  # User approves each wave commit
    require_approval_push=False,   # Auto-push after commit (or True)
    allowed_branches=["task/*", "main", "develop"],
    forbidden_files=["*.env", "secrets/*", ".aws/", ".ssh/"],
    max_changed_files=50,  # Allow large waves
    auto_push=False,
    auto_use_anthropic=False,  # Use local for most tasks
    auto_use_openai=False,
    auto_use_gemini=False  # Only if context > 49k (unlikely)
)
```

---

## 12. Timeline estimate (for reference only)

| Wave | Goals | Est. time | Agent type |
|------|-------|-----------|------------|
| 1 | 5 | 45 min | OpenHands (2), Claude (3) |
| 2 | 6 | 40 min | Claude (all) |
| 3 | 6 | 50 min | OpenHands (2), Claude (4) |
| 4 | 5 | 35 min | Claude (all) |
| 5 | 5 | 30 min | Mixed (OpenHands, Claude, Aider, RESPOND) |
| **Total** | **21** | **~3 hours** | — |

*Note: Times are approximate and depend on LLM speed, agent efficiency, and user approval latency.*

---

## 13. Success criteria

- [ ] All 21 goals completed successfully
- [ ] Backend compiles and runs (`./gradlew bootRun`)
- [ ] Frontend dev server starts (`npm run dev`)
- [ ] API endpoints functional (tested via curl/Postman)
- [ ] Database migrations applied
- [ ] Docker images build successfully
- [ ] K8s manifests valid
- [ ] CI/CD pipeline passes
- [ ] E2E tests pass (Cypress)
- [ ] Documentation complete (ADRs, README, API guide)
- [ ] Code committed to Git
- [ ] Final report generated

---

## 14. Potential pitfalls and mitigations

| Risk | Mitigation |
|------|------------|
| Wave dependencies too tight | `select_goal` validates dependencies, can reorder if needed |
| Agent produces broken code | `evaluate` catches failures, user can retry |
| Context too large for local LLM | Use CLOUD_REASONING for complex planning (auto-escalate) |
| Git conflicts | Each wave commits separately, branch per task |
| K8s manifests environment-specific | Use placeholders, user fills in values |
| Test flakiness | E2E tests may need manual validation |

---

## 15. What user sees (UI flow)

1. **Chat input:** "Naprogramuj web aplikaci pro evidenci knih"
2. **Progress bar:** "Intake → Design → Planning → Wave 1/5 → Wave 2/5 → ..."
3. **Node transitions:** Each node updates status (intake, design, plan_epic, w1g1, w1g2, ...)
4. **Approval dialogs:**
   - "Schválení: Navržená architektura... (ano/ne)"
   - "Schválení: Commit Wave 1 (23 souborů) (ano/ne)"
   - "Schválení: Push do repozitáře? (ano/ne)"
5. **Final message:** Long summary with links to ADRs, API docs, deployment instructions
6. **Artifacts:** All code in Git, Docker images built, K8s manifests ready

---

## 16. KB entries to create (post-completion)

After successful implementation, store to KB:
- "Book tracking system architecture" (design decisions)
- "Spring Boot + React integration patterns" (technical)
- "PostgreSQL schema for library management" (domain)
- "Docker multi-stage for Spring Boot" (devops)
- "K8s deployment for stateful apps" (devops)
- "Testing strategy for full-stack apps" (quality)

These will inform future similar projects.

---

## 17. Summary

This GENERATIVE flow demonstrates orchestrator's ability to:
- **Design** complex system architecture (CLOUD_REASONING)
- **Decompose** into 21 goals across 5 waves
- **Execute** with appropriate agents (OpenHands for complex, Claude for focused, Aider for tests)
- **Validate** each step (evaluation)
- **Approve** at critical gates (design, commits, push)
- **Persist** state across restarts (MongoDB checkpoints)
- **Integrate** with KB for best practices
- **Generate** comprehensive documentation
- **Deliver** production-ready code with tests and deployment

The entire process is **fully automated** after user approvals, with **no hard timeouts**, **push-based progress**, and **resumable** from any interrupt.
