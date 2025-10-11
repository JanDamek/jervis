# AI Assistant Guidelines for Code Refactoring (Kotlin-first)

This document defines the standard instructions for using AI code assistants (e.g., GitHub Copilot Chat, IntelliJ AI
Assistant, Junie) when analyzing and improving Kotlin/Java code in this project.

---

## Objectives

### 1. **Main language**

- Always use **Kotlin** (not Java-style code written in Kotlin syntax).
- Framework: **Spring Boot** (Jetty, WebFlux client).
- Reactive programming: **Coroutines first**, with `reactor-kotlin-extensions` used only for interop.

### 2. **Use English**

- Use **English only** for code, variables, methods, classes, and comments.
- Any non-English text (comments, variables, log messages) **must be immediately translated into English**.
- Do not mix languages within the same source file or method.

### 2.1 **Language & Comments Rules**

- **Code must be entirely in English.**  
  Any non-English text in code, comments, or logs must be immediately translated into English.
- **Inline comments (`// ...`) are treated as a design smell.**  
  Their presence indicates that the code is not self-explanatory.
- When an inline comment is found, the AI assistant **must evaluate whether the comment reveals a readability problem**.
    - If yes → **refactor the code** (rename variables, extract methods, simplify logic) so the comment is no longer
      needed.
    - If no (rare exception) → keep the comment only if it provides **critical context** (e.g., algorithm rationale,
      regulatory reference).
- Comments describing “what” the code does (e.g., `// check if null`, `// increment counter`) **must always be removed
  **.
- Comments describing “why” something is done in a non-obvious way should be moved into a **KDoc** placed above the
  declaration.
- Inline comments are allowed **only in exceptional cases**, and their existence should trigger a code review to
  evaluate whether refactoring is possible.

### 3. **SOLID Principles**

- Apply Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, and Dependency Inversion.

### 4. **Favor If-less Programming**

- **Goal**: reduce deeply nested `if/else` and imperative branching by using **sealed classes, polymorphism, strategy
  pattern, or map-based dispatch**.
- **Enums and sealed hierarchies**:
    - For small, well-defined sets of states, `when` is idiomatic and clear.
    - Prefer exhaustive `when` with sealed classes/enums (compiler guarantees exhaustiveness).
- **When to replace with polymorphism**:
    - If logic grows complex or is spread across multiple places → extract into strategy classes or sealed types with
      overridden behavior.
- **General principle**:
    - Use `when`/`if` where they are the **clearest choice**.
    - Use polymorphism or strategies when you expect **extension and evolution** of behavior.

### 5. **Naming**

- Classes/interfaces: **PascalCase**.
- Variables/methods: **camelCase**.
- Avoid abbreviations, use clear English names.

### 6. **Constants & Immutability**

- Replace magic values with `const val` / `val`.
- Use `val` by default, `var` only if mutation is required.

### 7. **Immutability Rules**

- **Domain objects**
    - Always use `val` for fields.
    - Model updates using `.copy()` instead of `var` mutations.
    - Each `.copy()` creates a new version of the object → easier reasoning, safe concurrency, clear versioning.

- **DTOs (API contracts)**
    - Always immutable (`val`).
    - Represent snapshots of data going in/out of APIs.

- **Persistence entities (R2DBC / DocumentDB)**
    - Prefer immutable models with `val` + `.copy()`.
    - Treat each persisted object as a snapshot of state.
    - Use optimistic versioning (`version`, `syncUid`, or `contextUid`) to track changes.

- **Runtime state holders (context/session/workflow)**
    - `var` is allowed only here, where objects represent mutable, long-lived runtime state.
    - These objects should not be persisted directly, only mapped into immutable documents.

### 8. **Null-safety Rules**

- **Never use `!!`** in domain, DTO, entity, or service code.
- Use safe alternatives:
    - Elvis operator `?:`
    - Safe call `?.`
    - `requireNotNull()` or `checkNotNull()` with meaningful messages
    - `lateinit var` only for DI or lifecycle-initialized properties
    - Sealed types or `Result` instead of nullable values for error states
- `!!` is allowed only in rare interop with Java libraries or framework limitations, and must be documented with a clear
  reason.

### 9. **Variable Lifecycle**

- Limit scope, initialize at the latest valid point.
- Prefer expression style functions (`fun x() = …`).

### 10. **Readability & Structure**

- Extract reusable code into extension functions.
- Keep functions small and single-purpose.
- Eliminate duplication.

### 11. **Reusability**

- Generalize shared logic.
- Prefer **extension functions**, **inline functions**, or **sealed hierarchies**.

### 12. **Data Structures & Generics**

- Use **Kotlin collections** (`listOf`, `mapOf`, etc.) by default.
- Use mutable collections only when modification is required.
- Prefer `Map`/`Set` over manual parallel structures.

### 13. **Serialization Rules**

- Default: **Kotlinx Serialization (`@Serializable`)** for all DTOs and entities.
- Do not rely on reflection-based Jackson unless required for interop.
- Prefer non-nullable fields with default values instead of nullable types.
- Always annotate explicitly if field names differ.

### 14. **Documentation**

- Use **KDoc** (`/** … */`).
- Document **intent**, not trivial implementation.
- No line comments (`//`).

### 15. **Always Fix**

- Typos, naming inconsistencies.
- Java-style patterns in Kotlin (e.g., `== true`, `!!`, `Optional`, builder setters).
- Replace verbose `if/else` with idiomatic `?:`, `?.let`, `takeIf`.

### 16. **Coroutines First**

- Write new code as `suspend fun` where async is expected.
- Prefer coroutines over Reactor APIs, but support interop.
- Controller methods must be `suspend fun`.

### 17. **Reactive Programming**

- Use `Flow<T>` for streams (0–N).
- Use `suspend fun` for single results (0–1).
- Convert to Reactor types (`Mono`, `Flux`) only at integration boundaries.
- **Never** call `.block()` in production.

### 18. **Coroutine Integration**

- Use `reactor-kotlin-extensions`.
- Prefer flows and suspend functions in controllers.
- Use structured concurrency (`withContext`, `SupervisorJob`).

### 19. **Performance & Resource Management**

- Configure `WebClient` connection pools.
- Use correct schedulers.
- Implement backpressure, retry, circuit breakers.

### 20. **Testing**

- Use **kotlinx.coroutines.test.runTest** for coroutine testing.
- For flows, test with `collect` assertions.
- Use Reactor StepVerifier only if explicitly testing interop.
- Test error and edge cases.

### 21. **Production Readiness**

- Circuit breakers, retries, timeouts.
- Observability: metrics, tracing, logging.
- Propagate security context in reactive chains.

### 22. **Dependency Injection**

- Always prefer **constructor injection**.
- Avoid field injection (`@Autowired var`).
- Never use manual singletons, rely on Spring context.
- Use `lateinit var` only for framework-managed beans.

### 23. **Logging Rules**

- Always use structured logging (`logger.info { "message with $var" }`).
- Never use `System.out.println` or `printStackTrace`.
- Always include correlation IDs (MDC/trace IDs) if available.

---

## Output Requirements

- Write **idiomatic Kotlin** (not Java with Kotlin syntax).
- Always refactor **directly in code**, not as suggestions.
- Formatting must follow Kotlin style guidelines (official Kotlin coding conventions).
- Avoid null-safety hacks (`!!`). Prefer `?:`, `?.let`, safe casts, or sealed types.

---