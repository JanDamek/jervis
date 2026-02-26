# BUG: Spring Data MongoDB "Parameter does not have a name" — STÁLE PŘETRVÁVÁ

**Datum:** 2026-02-26
**Priorita:** CRITICAL (BackgroundEngine + qualification nefunkční)
**Typ:** BUG
**Stav:** Předchozí fix (javaParameters + default hodnoty) NEPOMOHL

---

## Symptom

BackgroundEngine qualification loop padá každých 30s:

```
[ERROR] TaskQualificationService - Qualification stream failure:
  Parameter org.springframework.data.mapping.Parameter@7f29a073 does not have a name
org.springframework.data.mapping.MappingException
    at PersistentEntityParameterValueProvider.getParameterValue(...)
    at ClassGeneratingEntityInstantiator.extractInvocationArguments(...)
    at MappingMongoConverter.read(...)
    at ReactiveMongoTemplate$ProjectingReadCallback.doWith(...)
```

## Co už bylo vyzkoušeno (NEFUNGUJE)

1. ✅ `javaParameters.set(true)` v `backend/server/build.gradle.kts` + `backend/common-services/build.gradle.kts`
2. ✅ Default hodnoty pro všechny constructor parametry v `QualificationStepRecord`, `OrchestratorStepRecord`, `FilteringRuleDocument`
3. ✅ Nasazen nový build (ověřeno: pod startoval 09:23, error z 09:24)

## Klíčové zjištění: `ProjectingReadCallback`

Stack trace prochází přes `ProjectingReadCallback` (NE `ReadDocumentCallback`).
To znamená Spring Data používá **projekci** při čtení — ne přímou deserializaci entity.

Qualification query: `findByStateAndNextQualificationRetryAtIsNullOrderByQueuePositionAscCreatedAtAsc`
Vrací `Flow<TaskDocument>` — měl by vrátit entity, ne projekci. Proč Spring Data používá projekci?

## Podezření: Kotlin value classes + Spring Data projection

`TaskDocument` používá **inline value classes** jako typy polí:
- `@Id val id: TaskId` — inline value class wrapping String
- `val clientId: ClientId` — inline value class wrapping String
- `val projectId: ProjectId?` — nullable inline value class
- `val sourceUrn: SourceUrn` — inline value class wrapping String

**Kotlin value classes mají v bytecodu mangled názvy parametrů** (např. `clientId-<hash>`).
Spring Data nedokáže namapovat tyto mangled názvy na MongoDB pole.

Viz MEMORY gotchas: "Spring Data value class issues"

## Testovací dokument v DB

```json
{
  "_id": "69a000cad75b66407c01d8e1",
  "type": "EMAIL_PROCESSING",
  "clientId": "68a336adc3acf65a48cab3e7",
  "sourceUrn": "email::conn:69875ec77dffdba8c7c4d241,...",
  "state": "READY_FOR_QUALIFICATION",
  "processingMode": "BACKGROUND",
  "qualificationSteps": [],
  "orchestratorSteps": [],
  "_class": "com.jervis.entity.TaskDocument"
}
```

Dokument vypadá normálně. Problém je v **Java/Kotlin bytecode → Spring Data mapping**.

## Navrhované řešení (v pořadí priority)

### 1. Zkusit `@PersistenceCreator` konstruktor bez value classes

Přidat sekundární konstruktor s raw typy:
```kotlin
@Document(collection = "tasks")
data class TaskDocument(
    @Id val id: TaskId = TaskId.generate(),
    ...
) {
    companion object {
        @org.springframework.data.annotation.PersistenceCreator
        @JvmStatic
        fun create(
            id: String,
            type: TaskTypeEnum,
            clientId: String,
            sourceUrn: String,
            projectId: String?,
            ...
        ) = TaskDocument(
            id = TaskId(id),
            clientId = ClientId(clientId),
            sourceUrn = SourceUrn(sourceUrn),
            projectId = projectId?.let { ProjectId(it) },
            ...
        )
    }
}
```

### 2. Přidat custom Spring Data Converter pro value classes

```kotlin
@ReadingConverter
class StringToTaskIdConverter : Converter<String, TaskId> {
    override fun convert(source: String) = TaskId(source)
}

@WritingConverter
class TaskIdToStringConverter : Converter<TaskId, String> {
    override fun convert(source: TaskId) = source.value
}

// Registrovat v MongoConfig:
@Configuration
class MongoConfig {
    @Bean
    fun customConversions() = MongoCustomConversions(listOf(
        StringToTaskIdConverter(),
        TaskIdToStringConverter(),
        StringToClientIdConverter(),
        ClientIdToStringConverter(),
        // ... pro všechny value classes
    ))
}
```

### 3. Nahradit value classes v TaskDocument typealias/String

Nejjednodušší ale nejméně typově bezpečné:
```kotlin
// Místo:
val clientId: ClientId
// Použít:
val clientId: String
```

A konverzi dělat v mapper vrstvě.

### 4. Upgrade Spring Boot / Spring Data

Ověřit jestli novější verze Spring Data MongoDB lépe podporuje Kotlin inline value classes.
Aktuální: Spring Boot 4.0.1. Zkontrolovat changelog pro value class fixes.

## Soubory k prozkoumání

| Soubor | Proč |
|--------|------|
| `backend/server/.../entity/TaskDocument.kt` | Value class properties |
| `backend/common-services/.../types/` | Definice `TaskId`, `ClientId`, `ProjectId`, `SourceUrn` |
| `backend/server/.../repository/TaskRepository.kt` | Query metody s value class parametry |
| `backend/server/.../config/MongoConfig.kt` | Custom converters (pokud existuje) |
| `backend/server/build.gradle.kts` | javaParameters flag |
| `gotchas.md` | Dokumentované Spring Data + value class problémy |

## Dopad

- **BackgroundEngine**: qualification padá → žádné nové tasky se nezpracují
- **Execution loop**: pokud najde READY_FOR_GPU task, taky padne
- **Chat UI**: history reload padá na stejné chybě
- **Workaround**: Foreground chat přes Python orchestrátor funguje (nepoužívá TaskDocument read)
