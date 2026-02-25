# Meeting Timeline — starší týdny nejdou rozbalit

**Datum:** 2026-02-25
**Typ:** BUG
**Závažnost:** MEDIUM

## Symptom

Na MeetingsScreen se zobrazují skupiny starších týdnů (např. "Týden 16.2. – 22.2." s "7 nahrávek")
s ikonou ▶, ale kliknutí na ně je nerozbalí. "Tento týden" zobrazuje meetingy správně (vždy rozbalený).

Screenshot: Desktop app, scope MMB/nUFO.

## Analýza kódu

### UI (MeetingsScreen.kt:356-380)
- `TimelineGroupHeader` používá `OutlinedCard(onClick = onClick)` — klikatelný
- Click volá `viewModel.toggleGroup(group)`
- `isExpanded` závisí na `expandedGroups.containsKey(key)` kde key = `group.periodStart`

### ViewModel (MeetingViewModel.kt:216-242)
```kotlin
fun toggleGroup(group: MeetingGroupDto) {
    val key = group.periodStart
    if (_expandedGroups.value.containsKey(key)) {
        _expandedGroups.value = _expandedGroups.value - key
        return
    }
    val clientId = lastClientId ?: return  // ⚠️ SILENT RETURN pokud null
    scope.launch {
        _loadingGroups.value = _loadingGroups.value + key
        try {
            val items = repository.meetings.listMeetingsByRange(...)
            _expandedGroups.value = _expandedGroups.value + (key to items)
        } catch (e: Exception) {
            println(...)  // ⚠️ stdout, ne logger
            _error.value = "..."
        } finally {
            _loadingGroups.value = _loadingGroups.value - key
        }
    }
}
```

### Backend (MeetingRpcImpl.kt:745-764)
- `listMeetingsByRange` parsuje ISO dates, query MongoDB, loguje na DEBUG level
- Vypadá správně

## Možné příčiny (sestupně dle pravděpodobnosti)

### 1. kRPC WebSocket connection drop
- `loadTimeline` (první load) projde OK — nastaví `lastClientId` a vrátí data
- Pozdější `listMeetingsByRange` volání může selhat pokud se WebSocket odpojil
- Catch block tiskne na stdout (ne logger) — chyba neviditelná v logách
- `_error.value` se nastaví ale uživatel ji nemusí vidět pokud je na jiné části UI

### 2. `lastClientId` resetovaný na null
- `startRecording(clientId = null)` na řádku 358 resetuje `lastClientId = null`
- Pokud se zavolá jakýkoli recording-related kód s `clientId = null` → `toggleGroup` tiše returnuje
- Nepravděpodobné pokud se nic nenahrávalo, ale možné edge case

### 3. Compose Desktop platforma
- `OutlinedCard(onClick)` v LazyColumn na Desktop může mít gesture conflict se scrollováním
- Krátký tap vs. scroll threshold

## Diagnostika (pro development agenta)

1. **Přidat logging do toggleGroup**:
   ```kotlin
   fun toggleGroup(group: MeetingGroupDto) {
       val key = group.periodStart
       println("[Meeting] toggleGroup: key=$key lastClientId=$lastClientId")
       // ...
   }
   ```

2. **Změnit println na logger.info** v catch bloku (řádek 236)

3. **Přidat println do TimelineGroupHeader onClick** pro potvrzení že click se fire-uje

## Fix

Po diagnostice implementovat opravu dle nalezené příčiny. Pravděpodobně:
- Pokud kRPC drop: přidat retry nebo reconnect logiku do toggleGroup
- Pokud null clientId: přidat fallback na `selectedClientId` parametr
- Pokud gesture conflict: změnit z `OutlinedCard(onClick)` na `Modifier.clickable`

## Soubory
- `shared/ui-common/.../meeting/MeetingViewModel.kt` — toggleGroup + diagnostika
- `shared/ui-common/.../meeting/MeetingsScreen.kt` — TimelineGroupHeader
- `backend/server/.../rpc/MeetingRpcImpl.kt` — listMeetingsByRange (logger level)
