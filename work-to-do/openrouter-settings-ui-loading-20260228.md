# OpenRouter Settings — UI se jen točí (loading spinner)

**Priorita**: MEDIUM
**Status**: OPEN

---

## Problém

V Nastavení → OpenRouter se zobrazí jen loading spinner (JCenteredLoading) a nic se nenačte.
Uživatel vidí nekonečné otáčení bez chybové hlášky.

## Pravděpodobná příčina

`LaunchedEffect(Unit) { loadSettings() }` volá `repository.openRouterSettings.getSettings()`
přes kRPC. Pokud:
1. kRPC service `IOpenRouterSettingsService` není správně zaregistrovaný v Ktor RPC routingu
2. Nebo server vrací chybu kterou UI nezachytí (timeout bez exception)

V server logu žádný OpenRouter error — buď se volání nedostane na server, nebo tiše selže.

## Diagnostika

1. Zkontrolovat kRPC server-side routing — je `IOpenRouterSettingsService` registrovaný
   v `KtorRpcServer.kt` nebo ekvivalentním setup?
2. Zkontrolovat MongoDB kolekci — existuje `openRouterSettings` kolekce?
3. Testovat přímo: kRPC call z debug klienta nebo curl na WebSocket endpoint

## Soubory

- `shared/ui-common/.../settings/sections/OpenRouterSettings.kt` — UI composable
- `backend/server/.../rpc/OpenRouterSettingsRpcImpl.kt` — server RPC implementace
- `shared/common-api/.../service/IOpenRouterSettingsService.kt` — kRPC interface
- `shared/domain/.../di/NetworkModule.kt` — kRPC client setup
- `backend/server/.../rpc/KtorRpcServer.kt` — RPC routing registrace
