# EPIC Compile Errors — server build failed

**Priorita**: CRITICAL (blokuje nasazení)

## Chyba 1: ChatContinuousIndexer.kt — `findAllActive` neexistuje

**Soubor**: `backend/server/.../integration/chat/ChatContinuousIndexer.kt:51`

```
e: Unresolved reference 'findAllActive'
e: Cannot infer type for value parameter 'conn'
e: Unresolved reference 'capabilities'
```

**Oprava**: `connectionService.findAllActive()` → `connectionService.findAllValid()`
(metoda `findAllValid()` vrací `Flow<ConnectionDocument>`, je v `ConnectionService.kt:50`)

Pozor: `ConnectionDocument` nemá field `capabilities` — zkontrolovat logiku filtru.


## Chyba 2: ActionExecutorService.kt — `preview` neexistuje na request

**Soubor**: `backend/server/.../service/action/ActionExecutorService.kt:544-545`

```
e: Unresolved reference 'preview'
e: Unresolved reference 'value'
```

**Oprava**: `ActionExecutionRequest` nemá field `preview`. Použít
`buildApprovalPreview(request)` (metoda existuje na řádku 347 téhož souboru):

```kotlin
// ŠPATNĚ:
content = request.preview,

// SPRÁVNĚ:
content = buildApprovalPreview(request),
```


## Chyba 3: EnvironmentAgentService.kt — `inContainer` špatný chain

**Soubor**: `backend/server/.../service/environment/EnvironmentAgentService.kt:126`

```
e: Unresolved reference 'inContainer'
```

**Oprava**: Fabric8 API — `inContainer()` se volá jinak. Vzor z `EnvironmentResourceService.kt:130`:

```kotlin
client.pods()
    .inNamespace(namespace)
    .withName(podName)
    .inContainer(container)   // před tailingLines
    .tailingLines(tailLines)
    .log
```
