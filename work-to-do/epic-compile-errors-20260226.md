# EPIC Compile Errors — server build failed

**Priorita**: CRITICAL (blokuje nasazení)
**Stav**: ✅ VŠECHNY OPRAVENY (2026-02-26)

## Chyba 1: ChatContinuousIndexer.kt — `findAllActive` neexistuje ✅ OPRAVENO

**Soubor**: `backend/server/.../integration/chat/ChatContinuousIndexer.kt:51`

```
e: Unresolved reference 'findAllActive'
e: Cannot infer type for value parameter 'conn'
e: Unresolved reference 'capabilities'
```

**Oprava**: `connectionService.findAllActive()` → `connectionService.findAllValid()`
+ `capabilities` → `availableCapabilities` + přidáno `toList()` pro Flow konverzi.


## Chyba 2: ActionExecutorService.kt — `preview` neexistuje na request ✅ OPRAVENO

**Soubor**: `backend/server/.../service/action/ActionExecutorService.kt:544-545`

```
e: Unresolved reference 'preview'
e: Unresolved reference 'value'
```

**Oprava**: `request.preview` → `request.payload["content"] ?: buildApprovalPreview(request)`,
`clientId.value` → `clientId` (String, ne value class).


## Chyba 3: EnvironmentAgentService.kt — `inContainer` špatný chain ✅ OPRAVENO

**Soubor**: `backend/server/.../service/environment/EnvironmentAgentService.kt:126`

```
e: Unresolved reference 'inContainer'
```

**Oprava**: `inContainer()` přesunuto před `tailingLines()` v Fabric8 fluent API chain.
