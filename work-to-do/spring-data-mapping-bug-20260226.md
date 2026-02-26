# BUG: Spring Data MongoDB "Parameter does not have a name" — BackgroundEngine + Chat

**Datum:** 2026-02-26
**Priorita:** CRITICAL (BackgroundEngine i chat nefunkční)
**Typ:** BUG
**Příčina:** EPIC merge (commity ccab6098, d694ab8e, 2ccc18df)

---

## Symptomy

### Server — BackgroundEngine padá v execution loop i qualification

```
[ERROR] c.j.s.background.BackgroundEngine - Error in execution loop - will retry in PT1M
org.springframework.data.mapping.MappingException: Parameter org.springframework.data.mapping.Parameter@2b38ecec does not have a name
    at o.s.d.m.model.PersistentEntityParameterValueProvider.getParameterValue(...)
    at o.s.d.m.model.ClassGeneratingEntityInstantiator.extractInvocationArguments(...)
    at o.s.d.mongodb.core.convert.MappingMongoConverter.read(...)
    at o.s.d.mongodb.core.ReactiveMongoTemplate$ProjectingReadCallback.doWith(...)

[ERROR] c.j.s.b.TaskQualificationService - Qualification stream failure: Parameter ... does not have a name
```

BackgroundEngine retry loop: padá → čeká 1 min → padá → čeká 1 min → ...
Kvalifikace nefunguje → žádné background tasky se nezpracují.

### UI — Chat stream padá při reload history

```
ChatViewModel: Chat stream started, reloading history
Uncaught exception in thread AWT-EventQueue-0: Parameter ... does not have a name
org.springframework.data.mapping.MappingException: Parameter ... does not have a name
    at o.s.d.mongodb.core.convert.MappingMongoConverter.read(...)
    at o.s.d.mongodb.core.ReactiveMongoTemplate$ReadDocumentCallback.doWith(...)
```

UI se odpojí a znovu připojí (resilientFlow restart).

## Analýza

Chyba `Parameter does not have a name` je klasický Spring Data MongoDB + Kotlin problém.
Nastává když `ClassGeneratingEntityInstantiator` nedokáže najít jména constructor parametrů v bytecodu.

### Podezřelé entity z EPIC merge

**1. TaskDocument — nové embedded data classes:**
```kotlin
// Nové v EPIC merge:
val qualificationSteps: List<QualificationStepRecord> = emptyList()
val orchestratorSteps: List<OrchestratorStepRecord> = emptyList()
val priorityScore: Int? = null
val priorityReason: String? = null
val actionType: String? = null
val estimatedComplexity: String? = null
```

Embedded classes:
```kotlin
data class QualificationStepRecord(
    val timestamp: Instant,    // ← Instant bez default hodnoty
    val step: String,          // ← String bez default hodnoty
    val message: String,       // ← String bez default hodnoty
    val metadata: Map<String, String> = emptyMap(),
)

data class OrchestratorStepRecord(
    val timestamp: Instant,    // ← Instant bez default hodnoty
    val node: String,
    val message: String,
    val goalIndex: Int = 0,
    val totalGoals: Int = 0,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
)
```

**2. FilteringRuleDocument — nový entity:**
```kotlin
data class FilteringRuleDocument(
    @Id val id: ObjectId = ObjectId(),
    val sourceType: String,       // ← BEZ default hodnoty
    val conditionType: String,    // ← BEZ default hodnoty
    val conditionValue: String,   // ← BEZ default hodnoty
    ...
)
```

**3. Existující value classes v TaskDocument:**
- `TaskId`, `ClientId`, `ProjectId`, `SourceUrn` — inline value classes
- Viz `gotchas.md` — známý problém s Spring Data + Kotlin value classes

### Klíčové: stack trace obsahuje `ProjectingReadCallback`

Na serveru chyba prochází přes `ProjectingReadCallback` (ne `ReadDocumentCallback`).
To naznačuje, že problém může být v **repository projection query**, ne v přímém čtení entity.

Zkontrolovat:
- Nové repository metody v `TaskRepository.kt` (EPIC 2 priority queries)
- `FilteringRuleRepository.kt` query metody
- Derived query s value class parametry (`ClientId`, `ProjectId`)

## Soubory k prozkoumání

| Soubor | Proč |
|--------|------|
| `backend/server/.../entity/TaskDocument.kt` | Nové embedded classes + value class fieldy |
| `backend/server/.../entity/FilteringRuleDocument.kt` | Nový entity, fields bez defaults |
| `backend/server/.../repository/TaskRepository.kt` | Nové EPIC 2 query metody |
| `backend/server/.../repository/FilteringRuleRepository.kt` | Nový repository |
| `backend/server/.../service/background/BackgroundEngine.kt` | Execution loop + qualification |
| `backend/server/.../service/background/TaskQualificationService.kt` | Qualification stream |
| `gradle/libs.versions.toml` | Ověřit Spring Boot / Spring Data verzi |

## Známé řešení pro Spring Data + Kotlin

1. **Přidat `-parameters` do Kotlin compileru** (build.gradle):
   ```kotlin
   tasks.withType<KotlinCompile> {
       compilerOptions {
           javaParameters.set(true)
       }
   }
   ```

2. **Všem constructor parametrům embedded classes dát default hodnoty**

3. **Používat `@PersistenceCreator` nebo `@ConstructorProperties`** pro entity s value classes

4. **Nové embedded data classes nemít povinné parametry bez defaultu** — `QualificationStepRecord` a `OrchestratorStepRecord` mají `timestamp`, `step`, `message` bez defaultu

## Dopad

- **BackgroundEngine**: padá každou minutu, žádné background tasky se nezpracují
- **Qualification**: nefunkční, nové tasky se nekvalifikují
- **Chat**: history reload padá, UI se odpojuje/připojuje opakovaně
- **Foreground chat přes Python orchestrátor**: funguje (nepoužívá Spring Data pro čtení TaskDocument)
