# AI Assistant Guidelines for Code (Kotlin-first)

This document defines strict rules for how AI assistants must generate and modify code in this project.  
The goal is **maximum architecture correctness, readability, and consistency**.

---

## 0️⃣ Architectural Safety Rules

### 0.1 Follow Existing Architecture First

- Inspect existing patterns and reuse them.
- Do **NOT** introduce new frameworks, abstractions, or parameter additions unless explicitly required.

### 0.2 Fail Fast — Do Not Guess

- Do not swallow exceptions.
- Do not attempt fallback logic unless explicitly required.
- Unexpected states **must throw clear errors**.

### 0.3 Do Not Invent Anything

- No new config values, no new annotations or parameters.
- No new utility classes unless already used elsewhere.
- No unused DTOs, methods, or entities — **no dead code**.

### 0.4 No Auto-Generated Tests

- Write/update tests **only when explicitly requested**.

---

## 1️⃣ Main Language Rules

- **Always write idiomatic Kotlin**, not Java-style Kotlin.
- Main stack:
    - **Spring Boot**
    - **Coroutines first**
    - Reactor only for interop at context boundaries

---

## 2️⃣ English-Only Code

- All code, logs, comments **must be in English**.
- Any Czech text must be **immediately translated**.
- Inline comments (`//`) indicate bad code readability.
    - Remove them if they state “what”
    - Move to KDoc if they state “why”

---

## 3️⃣ SOLID Principles

- Enforce proper abstractions and separation.
- Eliminate business logic spreading across layers.

---

## 4️⃣ Favor If-less Programming

- Prefer sealed types, polymorphism, strategies.
- Use exhaustive `when` for small state sets.
- Use branching only when it is the **clearest** solution.

---

## 5️⃣ Naming Conventions

- PascalCase: Classes, Interfaces, Enums
- camelCase: Methods, Variables
- No unnecessary abbreviations

---

## 6️⃣ Constants & Immutability

- Prefer `val`
- Replace values with `const val` where possible

---

## 7️⃣ Object Modeling Rules

### 7.1 Domain Objects (Business models)

- Immutable (`val` fields + `.copy()`)
- Used in **Service and use-case logic**
- Represent real business rules
- ❌ Not allowed in REST or external APIs

### 7.2 Entities (Persistence layer models)

- Used in **Repository only** and **service persistence logic**
- Can be mutated if storage requires it
- **Never exposed outside business internals**

### 7.3 DTOs (API contracts)

- Used **only** for:
    - REST controllers
    - Messaging / API boundaries
- Immutable
- Annotated with `@Serializable`

---

## 8️⃣ Object Boundary Enforcement ✅

| Layer      | Allowed Input | Allowed Output | Can call            | Forbidden                        |
|------------|:-------------:|:--------------:|---------------------|----------------------------------|
| Controller |      DTO      |      DTO       | Service             | Repository, Entity               |
| Service    |    Domain     |     Domain     | Repository, Service | Controller, DTO                  |
| Repository |    Entity     |     Entity     | Database            | Controller, Service, Domain, DTO |

---

### ✅ Mapping Rules

| Convert         | Where      |
|-----------------|------------|
| DTO → Domain    | Controller |
| Domain → DTO    | Controller |
| Domain → Entity | Service    |
| Entity → Domain | Service    |

---

### ❌ Hard Prohibitions

- Controllers returning Entities → ❌
- Services receiving DTOs → ❌
- Controllers accessing Repositories → ❌
- Domain models with persistence annotations → ❌
- DTOs in domain logic → ❌

Failure to respect these rules must be corrected before any other work proceeds.

---

## 9️⃣ Null Safety

- ❌ Never use `!!` (except documented Java interop)
- Prefer:
    - `?.`
    - `?:`
    - `requireNotNull()`, `checkNotNull()`
    - sealed results or `Result`

---

## 🔟 Readability & Structure

- Keep functions small and single-purpose.
- Extract shared code into extension functions.
- No duplication.

---

## 1️⃣1️⃣ Collections

- Prefer immutable Kotlin collections (`listOf`, `mapOf`)

---

## 1️⃣2️⃣ Serialization

- Default: **Kotlinx Serialization**
- Avoid reflection-based Jackson unless interop needed

---

## 1️⃣3️⃣ Coroutines First

- Use `suspend fun` where async is expected.
- Use `Flow<T>` for streams > 1 element.
- **Never call `.block()`** in production.

---

## 1️⃣4️⃣ Observability & Logging

- Always use structured logging (`logger.info {}`)
- Include **correlation IDs (trace/MDC IDs)** where available

---

## 1️⃣5️⃣ Dependency Injection

- Use constructor injection only
- No field injection or manual singletons

---

## ✅ Output Requirements for AI

- Modify code directly — not suggestions only.
- Follow Kotlin style guidelines.
- Enforce all rules above automatically when writing code.
- If a rule cannot be followed → **ask before proceeding**.
- When unsure → **choose the simplest approach aligned with existing patterns**.

---

## ✅ Final Rule

> **The domain model is the source of truth.  
> Controllers handle communication.  
> Repositories handle persistence.**  
> No crossing of responsibilities.