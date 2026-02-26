# BUG: UI se nepřeloží — 4 kompilační chyby

**Datum:** 2026-02-26
**Priorita:** CRITICAL (blokuje UI build)
**Typ:** BUG
**Příčina:** EPIC merge přidal nové eventy a přepsal GuidelinesSettings

---

## Chyba 1: `when` expression not exhaustive — MainViewModel.kt:233

### Problém

EPIC merge přidal nový event `JervisEvent.ApprovalRequired` (EPIC 5) do `JervisEvent` sealed class,
ale `handleGlobalEvent()` v `MainViewModel.kt` ho nemá v `when` výrazu.

### Oprava

```kotlin
// MainViewModel.kt řádek 233, přidat branch:
is JervisEvent.ApprovalRequired -> {
    // EPIC 5: Show approval dialog for action requiring user approval
    notification.handleUserTaskCreated(
        JervisEvent.UserTaskCreated(
            clientId = event.clientId,
            taskId = event.taskId,
            title = "Schválení: ${event.action}",
            isApproval = true,
            interruptAction = event.action,
            interruptDescription = event.preview,
            timestamp = event.timestamp,
        )
    )
}
```

Nebo jednodušeji pokud ještě nemá handler:
```kotlin
is JervisEvent.ApprovalRequired -> { /* TODO: EPIC 5 approval flow */ }
```

---

## Chyba 2: Smart cast impossible — ApprovalNotificationDialog.kt:117,124,135

### Problém

`event.errorDetail` je `String?` (nullable) deklarovaný v jiném modulu (`common-dto`).
Kotlin neumožňuje smart cast přes hranice modulů — property se může změnit mezi kontrolou a použitím.

### Oprava

Přidat lokální proměnnou na řádku 115:

```kotlin
// ApprovalNotificationDialog.kt řádek 115, PŘED if blok:
val errorDetail = event.errorDetail
if (errorDetail != null) {
    val summary = errorDetail.lineSequence().take(3).joinToString("\n")
    // ... (řádek 117)

    if (errorDetail.lines().size > 3) {
        // ... (řádek 124)

        Text(
            text = errorDetail,  // řádek 135
            // ...
        )
    }
}
```

Celý blok `if (event.errorDetail != null)` nahradit:
```kotlin
val errorDetail = event.errorDetail
if (errorDetail != null) {
    // ... celý blok zůstává stejný, jen nahradit event.errorDetail → errorDetail
```

---

## Chyba 3: Unresolved reference `getProjectsByClientId` — GuidelinesSettings.kt:159

### Problém

EPIC merge přidal `GuidelinesSettings.kt` který volá `repository.projects.getProjectsByClientId(cid)`,
ale správný název metody v repository je `listProjectsForClient(clientId)`.

### Oprava

```kotlin
// GuidelinesSettings.kt řádek 159:
// PŘED:
projects = repository.projects.getProjectsByClientId(cid)
// PO:
projects = repository.projects.listProjectsForClient(cid)
```

---

## Chyba 4: No value passed for parameter `label` — GuidelinesSettings.kt:536

### Problém

`JSwitch` composable vyžaduje `label: String` jako první parametr (viz `DesignForms.kt:129`),
ale `ApprovalRuleRow` volá `JSwitch(checked=..., onCheckedChange=...)` bez `label`.

### Oprava

`ApprovalRuleRow` již zobrazuje label v `Text(label)` na řádku 533. Jsou dvě možnosti:

**Varianta A** — přidat label do JSwitch (duplikát, ale jednoduché):
```kotlin
JSwitch(
    label = label,
    checked = rule.enabled,
    onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
)
```

**Varianta B** — zrušit `Text(label)` a nechat jen JSwitch (čistší):
```kotlin
// Smazat řádek 533: Text(label, modifier = Modifier.weight(1f))
// a přidat label + modifier do JSwitch:
JSwitch(
    label = label,
    checked = rule.enabled,
    onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
    modifier = Modifier.weight(1f),
)
```

---

## Soubory k opravě

| Soubor | Řádek | Chyba |
|--------|-------|-------|
| `shared/ui-common/.../MainViewModel.kt` | 233 | Přidat `is ApprovalRequired` branch |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | 115 | Lokální proměnná pro smart cast |
| `shared/ui-common/.../settings/sections/GuidelinesSettings.kt` | 159 | `getProjectsByClientId` → `listProjectsForClient` |
| `shared/ui-common/.../settings/sections/GuidelinesSettings.kt` | 536 | Přidat `label` parametr do `JSwitch` |
