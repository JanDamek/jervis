# Bug: UI crash při otevření klienta s expirovaným GitLab tokenem

**Severity**: CRITICAL (celé UI okno zmizí)
**Date**: 2026-02-23

## Symptom

Po otevření nastavení klienta MMB celé UI okno spadne s:
```
ProviderAuthException: GitLab API error (listProjects): HTTP 401 -
{"error":"invalid_token","error_description":"Token is expired. You can either do re-authorization or token refresh."}
```
Exception propaguje přes kRPC do Compose coroutine scope → zruší parent scope → celé okno zmizí.

## Řetězec volání

```
UI: ClientEditForm (LaunchedEffect)
  → repository.connections.listAvailableResources(connectionId, capability)
  → kRPC WebSocket
  → ConnectionRpcImpl.listAvailableResources()
      → refreshTokenIfNeeded()     ← pro BEARER auth: no-op!
      → providerRegistry.withClient("GITLAB") { it.listResources(...) }
          → GitLabProviderService.listResources()
              → GitLabApiClient.listProjects(baseUrl, token)
                  → checkProviderResponse()
                      → throw ProviderAuthException(401, ...)
```

## Příčiny

### 1. kRPC exception propaguje do UI a zabíjí okno
`ConnectionRpcImpl.listAvailableResources()` má `catch (_: Exception)` a vrací `emptyList()`,
**ALE** kRPC může exception doručit jako `CancellationException`-subtype, který `catch (e: Exception)`
v UI coroutine nechytí → zruší celý coroutine scope → crash.

UI volání v `ClientEditForm.kt`, `ProjectEditForm.kt`, `ProjectGroupEditForm.kt`:
```kotlin
scope.launch {
    // catch (_: Exception) nestačí pro kRPC-wrapped CancellationException
    val res = repository.connections.listAvailableResources(...)
}
```

### 2. BaseRpcImpl re-throwuje všechny výjimky
`BaseRpcImpl.kt:36`:
```kotlin
catch (e: Exception) {
    logger.error(e) { "RPC Error in $operation" }
    throw e   // ← propaguje na kRPC klienta
}
```
Jakýkoliv service používající `BaseRpcImpl` (ProjectRpcImpl, ProjectGroupRpcImpl, atd.)
propustí backend exceptions na klienta.

### 3. Žádný token refresh pro BEARER auth
`ConnectionRpcImpl.kt:253-268`:
```kotlin
if (connection.authType != AuthTypeEnum.OAUTH2) {
    return connection   // ← BEARER tokeny se nikdy nerefreshují
}
```
GitLab Personal Access Tokeny nejdou refreshovat programaticky — uživatel musí ručně
vytvořit nový. Ale **aplikace o tom uživatele neinformuje**.

### 4. Starý GitLabClient nemá checkProviderResponse
`backend/server/.../service/gitlab/GitLabClient.kt:47` — starší klient pro polling:
```kotlin
return json.decodeFromString(response.bodyAsText())  // ← ignoruje HTTP status
```
Na 401 dostane JSON error body a padne na `SerializationException` místo typované `ProviderAuthException`.

## Doporučená oprava

### A. UI nesmí spadnout (priorita 1)
Všechna `scope.launch {}` v UI co volají RPC musí mít robustní error handling:
```kotlin
scope.launch {
    try {
        val res = repository.connections.listAvailableResources(...)
        availableResources = availableResources + (key to res)
    } catch (e: CancellationException) {
        throw e  // nikdy nežrat CancellationException
    } catch (e: Exception) {
        availableResources = availableResources + (key to emptyList())
        // zobrazit warning uživateli
    }
}
```

### B. Detekce expirovaného tokenu (priorita 2)
1. V `ConnectionRpcImpl.listAvailableResources` — při chycení `ProviderAuthException(401)`
   aktualizovat `ConnectionDocument.state` na nový stav (např. `AUTH_EXPIRED`)
2. V UI zobrazit badge/warning na connection kartě: "Token expiroval — obnovte v nastavení připojení"
3. Uživatel řeší v nastavení připojení (Connections), NE v nastavení klienta

### C. Starý GitLabClient — přidat checkProviderResponse (priorita 3)
V `backend/server/.../service/gitlab/GitLabClient.kt`:
```kotlin
val responseText = response.checkProviderResponse("GitLab", "listProjects")
return json.decodeFromString(responseText)
```

## Dotčené soubory

| Soubor | Řádek | Problém |
|--------|-------|---------|
| `shared/.../settings/sections/ClientEditForm.kt` | ~123 | `catch` nestačí pro kRPC CancellationException |
| `shared/.../settings/sections/ProjectEditForm.kt` | ~130 | Stejný problém |
| `shared/.../settings/sections/ProjectGroupEditForm.kt` | ~100 | Stejný problém |
| `backend/.../rpc/ConnectionRpcImpl.kt` | 216-246, 253-268 | Catch OK ale chybí auth state update; no refresh pro BEARER |
| `backend/.../rpc/BaseRpcImpl.kt` | 36 | Re-throwuje vše na klienta |
| `backend/.../service/gitlab/GitLabClient.kt` | 47 | Chybí checkProviderResponse |
| `backend/service-gitlab/.../GitLabApiClient.kt` | 62 | Zdroj ProviderAuthException (OK) |
| `backend/common-services/.../http/ResponseValidation.kt` | 25 | checkProviderResponse (OK) |
