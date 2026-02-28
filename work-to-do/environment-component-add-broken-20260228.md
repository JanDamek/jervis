# Environment — přidání komponenty tiše selže

**Priorita**: HIGH
**Status**: OPEN

---

## Problém

V "Správa prostředí" → Komponenty → "+ Přidat" dialog se vyplní, potvrdí, ale komponenta se nevytvoří. Dialog se zavře a nic se nestane.

## Příčina

`ComponentsTab.kt:73-80` — `saveEnvironment()` tiše spolkne výjimku:

```kotlin
fun saveEnvironment(updatedEnv: EnvironmentDto) {
    scope.launch {
        try {
            repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
            onUpdated()
        } catch (_: Exception) {}  // ← BUG: tiše zahodí chybu
    }
}
```

Backend `updateEnvironment` pravděpodobně padá, ale UI o tom neví.

## Řešení

### 1. Zobrazit chybu uživateli

```kotlin
fun saveEnvironment(updatedEnv: EnvironmentDto) {
    scope.launch {
        try {
            repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
            onUpdated()
        } catch (e: Exception) {
            // Zobrazit snackbar s chybou
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        }
    }
}
```

Vyžaduje přidat `snackbarHostState` parametr do `ComponentsTab`.

### 2. Investigovat proč backend padá

Zkontrolovat:
- `EnvironmentRpcImpl.updateEnvironment()` — co vrací, jaké validace dělá
- Server logy při pokusu o update
- Zda `environment.id` existuje v DB
- Zda DTO serializace/deserializace je korektní (value class gotcha?)

### 3. Stejný pattern opravit všude

Stejný `catch (_: Exception) {}` je pravděpodobně i jinde v environment UI — opravit konzistentně:
- `ComponentsTab.kt` — saveEnvironment
- `PropertyMappingsTab.kt` — pokud má stejný pattern
- `OverviewTab.kt` — provisioning/delete akce

## Soubory

- `shared/ui-common/.../screens/environment/ComponentsTab.kt` — přidat error handling
- `shared/ui-common/.../screens/environment/PropertyMappingsTab.kt` — zkontrolovat
- `shared/ui-common/.../screens/environment/OverviewTab.kt` — zkontrolovat
- `backend/server/.../rpc/EnvironmentRpcImpl.kt` — zkontrolovat updateEnvironment

## Ověření

1. Přidat komponentu → buď se vytvoří, nebo se zobrazí srozumitelná chyba
2. Žádný tichý `catch (_: Exception) {}` v celém environment UI
