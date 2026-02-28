# Environment — přidání komponenty tiše selže

**Priorita**: HIGH
**Status**: DONE

---

## Problém

V "Správa prostředí" → Komponenty → "+ Přidat" dialog se vyplní, potvrdí, ale komponenta se nevytvoří. Dialog se zavře a nic se nestane.

## Potvrzená příčina (2026-03-01)

Tichý `catch (_: Exception) {}` na **9 místech** v 5 souborech:

| Soubor | Místo | Řádky |
|--------|-------|-------|
| **ComponentsTab.kt** | `saveEnvironment()` | 75-79 |
| **PropertyMappingsTab.kt** | `saveEnvironment()` | 81-85 |
| **EnvironmentManagerScreen.kt** | templates load | 84-86 |
| **EnvironmentManagerScreen.kt** | provision akce | 269-272 |
| **EnvironmentManagerScreen.kt** | stop akce | 277-280 |
| **EnvironmentManagerScreen.kt** | delete akce | 285-288 |
| **EnvironmentManagerScreen.kt** | sync akce | 293-296 |
| **LogsEventsTab.kt** | `loadPods()` | 74-78 |
| **LogsEventsTab.kt** | `loadEvents()` | 102-106 |

**Pozitivní vzor k následování** — K8sResourcesTab.kt je vzorně udělaný:
- Exception → `error` proměnná → `JErrorState(message, onRetry)` (řádky 137-140)

## Řešení

### 1. Nahradit tiché catch bloky error state zobrazením

Vzor z K8sResourcesTab (správný):
```kotlin
var error by remember { mutableStateOf<String?>(null) }

fun saveEnvironment(updatedEnv: EnvironmentDto) {
    scope.launch {
        try {
            repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
            error = null
            onUpdated()
        } catch (e: Exception) {
            error = "Chyba při ukládání: ${e.message}"
        }
    }
}

// V composable:
error?.let { msg ->
    JErrorState(message = msg, onRetry = { error = null })
}
```

### 2. Investigovat proč backend padá

Zkontrolovat:
- `EnvironmentRpcImpl.updateEnvironment()` — co vrací, jaké validace dělá
- Server logy při pokusu o update
- Zda `environment.id` existuje v DB
- Zda DTO serializace/deserializace je korektní (value class gotcha?)

## Soubory (9 catch bloků k opravení)

- `shared/ui-common/.../screens/environment/ComponentsTab.kt` — saveEnvironment
- `shared/ui-common/.../screens/environment/PropertyMappingsTab.kt` — saveEnvironment
- `shared/ui-common/.../screens/environment/EnvironmentManagerScreen.kt` — templates + 4 akce
- `shared/ui-common/.../screens/environment/LogsEventsTab.kt` — loadPods + loadEvents
- `backend/server/.../rpc/EnvironmentRpcImpl.kt` — zkontrolovat updateEnvironment

## Ověření

1. Přidat komponentu → buď se vytvoří, nebo se zobrazí srozumitelná chyba
2. Žádný tichý `catch (_: Exception) {}` v celém environment UI
3. Provisioning/stop/delete/sync → při chybě zobrazit error state
