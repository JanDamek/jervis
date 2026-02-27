# Build — neexhaustivní `when` pro nové ConnectionCapability hodnoty

**Priorita**: HIGH (build FAIL)

## Problém

Do `ConnectionCapability` enum byly přidány 4 nové hodnoty:
`CHAT_READ`, `CHAT_SEND`, `CALENDAR_READ`, `CALENDAR_WRITE`

Tři `when` výrazy v UI je nemají → **build FAIL**.

## Chyby

```
ClientsSharedHelpers.kt:18 — getCapabilityLabel()
ClientsSharedHelpers.kt:28 — getIndexAllLabel()
ConnectionsSettings.kt:337 — CapabilityChip()
```

## Co opravit

### 1. `ClientsSharedHelpers.kt:18` — `getCapabilityLabel()`

Přidat:
```kotlin
ConnectionCapability.CHAT_READ -> "Chat (čtení)"
ConnectionCapability.CHAT_SEND -> "Chat (odesílání)"
ConnectionCapability.CALENDAR_READ -> "Kalendář (čtení)"
ConnectionCapability.CALENDAR_WRITE -> "Kalendář (zápis)"
```

### 2. `ClientsSharedHelpers.kt:28` — `getIndexAllLabel()`

Přidat:
```kotlin
ConnectionCapability.CHAT_READ -> "Indexovat všechny kanály"
ConnectionCapability.CHAT_SEND -> "Použít všechny kanály"
ConnectionCapability.CALENDAR_READ -> "Indexovat všechny kalendáře"
ConnectionCapability.CALENDAR_WRITE -> "Spravovat všechny kalendáře"
```

### 3. `ConnectionsSettings.kt:337` — `CapabilityChip()`

Přidat:
```kotlin
ConnectionCapability.CHAT_READ -> "Chat Read" to MaterialTheme.colorScheme.secondary
ConnectionCapability.CHAT_SEND -> "Chat Send" to MaterialTheme.colorScheme.secondary
ConnectionCapability.CALENDAR_READ -> "Calendar Read" to MaterialTheme.colorScheme.tertiary
ConnectionCapability.CALENDAR_WRITE -> "Calendar Write" to MaterialTheme.colorScheme.tertiary
```

## Soubory

- `shared/ui-common/.../sections/ClientsSharedHelpers.kt`
- `shared/ui-common/.../sections/ConnectionsSettings.kt`
- `shared/common-dto/.../connection/ConnectionDtos.kt` (zdroj nových hodnot)
