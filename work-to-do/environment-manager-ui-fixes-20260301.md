# Environment Manager — UI opravy a doplnění

**Priorita**: HIGH
**Status**: OPEN

---

## Problém

Environment Manager má několik UX problémů, které brání efektivnímu používání:

1. **OverviewTab je read-only** — nelze editovat název, popis, tier, namespace, storage, agent instructions
2. **ComponentEditPanel nemá výběr image/verze** — po vytvoření komponenty nejde změnit verzi z šablony
3. **PropertyMappings UX je matoucí** — uživatel neví co kam patří, chybí vysvětlení konceptu
4. **onSave callback není propojený** — OverviewTab má `onSave` parametr, ale EnvironmentManagerScreen ho nepředává
5. **Silent catch bloky** — 9 míst kde se chyby tiše polknou (`catch (_: Exception) {}`), uživatel nevidí chybovou hlášku

---

## 1. OverviewTab — přepsat na editovatelný

### Současný stav
OverviewTab zobrazuje jen `JKeyValueRow` (read-only). Uživatel nemůže změnit žádné pole.

### Řešení
Přepsat na editovatelná pole:
- **name** → `JTextField`
- **description** → `JTextField` (multiline, 2-4 řádky)
- **tier** → `JDropdown` (DEV/STAGING/PROD)
- **namespace** → `JTextField` (enabled jen pro PENDING/STOPPED/ERROR)
- **storageSizeGi** → `JTextField` (filtrovat jen digits)
- **agentInstructions** → `JTextField` (multiline, 3-8 řádků)

Read-only ponechat: clientId, groupId, projectId, state, components summary, property mappings summary.

Přidat change detection (`hasChanges`) a tlačítko "Uložit změny" (JPrimaryButton), které se zobrazí jen při změně.

### Soubory
- `shared/ui-common/.../screens/environment/OverviewTab.kt` — přepsat celý

---

## 2. onSave propojení v EnvironmentManagerScreen

### Současný stav
`OverviewTab` přijímá `onSave: (EnvironmentDto) -> Unit = {}`, ale `EnvironmentDetail` v `EnvironmentManagerScreen.kt` ho nepředává.

### Řešení
V `EnvironmentManagerScreen.kt` řádek 263 přidat:
```kotlin
onSave = { updatedEnv ->
    scope.launch {
        try {
            repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
            onUpdated()
        } catch (e: Exception) {
            // zobrazit chybu uživateli
        }
    }
},
```

### Soubory
- `shared/ui-common/.../screens/environment/EnvironmentManagerScreen.kt` — řádky 262-298

---

## 3. Silent catch bloky → error state

### Současný stav
EnvironmentManagerScreen má 9 míst s `catch (_: Exception) {}`:
- řádek 86: `templates = repository.environments.getComponentTemplates()` — load šablon
- řádek 231: `status = repository.environments.getEnvironmentStatus(...)` — load statusu
- řádky 271, 279, 287, 295: akce onProvision, onStop, onDelete, onSync

### Řešení
Přidat `var actionError by remember { mutableStateOf<String?>(null) }` do `EnvironmentDetail` a zobrazit `JErrorState` při chybě. Každý catch blok nastaví `actionError = "Chyba při X: ${e.message}"`.

### Soubory
- `shared/ui-common/.../screens/environment/EnvironmentManagerScreen.kt`

---

## 4. ComponentEditPanel — přidat výběr image/verze

### Současný stav
`ComponentEditPanel.kt` řádky 89-116 má verze dropdown ale logika je nekompletní:
- Po změně typu se verze neresetuje (chybí `LaunchedEffect(type)`)
- Docker image field nemá placeholder
- Chybí toggle "Vlastní image" jako v `AddComponentDialog`

### Řešení
Sjednotit logiku s `AddComponentDialog`:
1. Přidat `LaunchedEffect(type)` — při změně typu resetovat image na první verzi ze šablony
2. Přidat `var useCustomImage by remember { ... }` — toggle pro ruční override
3. Pokud image odpovídá šabloně → předvybrat verzi v dropdownu
4. Přidat "Vlastní image" / "Zpět na výběr verze" toggle (JTextButton)
5. Přidat image preview pod dropdown (monospace text)

### Vzor (z AddComponentDialog, EnvironmentDialogs.kt řádky 102-141):
```kotlin
if (versions.isNotEmpty() && !useCustomImage) {
    JDropdown(items = versions, selectedItem = selectedVersion, ...)
    selectedVersion?.let {
        Text(it.image, style = bodySmall.copy(fontFamily = Monospace))
    }
}
if (versions.isNotEmpty()) {
    JTextButton(onClick = { useCustomImage = !useCustomImage }) {
        Text(if (useCustomImage) "Zpět na výběr verze" else "Vlastní image")
    }
}
if (useCustomImage || versions.isEmpty()) {
    JTextField(value = image, label = "Docker image")
}
```

### Soubory
- `shared/ui-common/.../screens/environment/ComponentEditPanel.kt` — řádky 82-116
- Reference: `shared/ui-common/.../settings/sections/EnvironmentDialogs.kt` řádky 43-141

---

## 5. PropertyMappings UX — vysvětlující text

### Současný stav
- Formulář "Nové mapování" má pole bez kontextu — uživatel netuší co kam patří
- "Cílová infra komponenta" = odkud data přicházejí (ne kam jdou) — matoucí label
- "Projekt komponenta" = kam se ENV nastaví — neintuitivní pořadí
- Chybí vysvětlení konceptu mapování
- Empty state říká jen "přidejte PROJECT a infra" bez vysvětlení proč

### Řešení

#### 5.1 Přidat vysvětlení konceptu do ManualMappingForm
Na začátek JSection přidat:
```kotlin
Text(
    "Mapování propojuje infrastrukturní komponentu (DB, Redis, ...) s projektovou " +
        "aplikací. Při provisionování se šablona vyhodnotí a výsledná hodnota se " +
        "nastaví jako ENV proměnná v kontejneru projektu.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

#### 5.2 Přehodit pořadí polí a přejmenovat labels
1. **Zdroj dat** (infra) — `"Zdroj dat (infra komponenta, odkud se berou hodnoty)"`
2. Zobrazit dostupné porty infra komponenty pod dropdownem
3. **Cíl** (projekt) — `"Cíl (projektová aplikace, kam se ENV nastaví)"`
4. **ENV proměnná** — `"Název ENV proměnné v cílovém kontejneru"`, placeholder: `"např. SPRING_DATASOURCE_URL, REDIS_HOST"`
5. **Šablona** — `"Šablona hodnoty (placeholdery se nahradí při provisionování)"`, placeholder: `"např. jdbc:postgresql://{host}:{port}/db"`
6. Rozšířit helper text: `"Placeholdery: {host} = K8s service name infra komponenty, {port} = první port, {name} = název komponenty, {env:VAR_NAME} = hodnota ENV z infra komponenty"`

#### 5.3 Vylepšit empty state
```kotlin
JEmptyState(
    message = if (projectComponents.isEmpty() || infraComponents.isEmpty()) {
        "Mapování propojuje infra komponenty (DB, cache) s projekty přes ENV proměnné. " +
            "Přidejte alespoň jeden PROJECT a jeden infrastrukturní komponent na záložce Komponenty."
    } else {
        "Žádná mapování. Klikněte 'Auto-navrhnout' pro automatické vygenerování " +
            "na základě šablon, nebo 'Přidat' pro ruční vytvoření."
    },
)
```

### Soubory
- `shared/ui-common/.../screens/environment/PropertyMappingsTab.kt` — ManualMappingForm (řádky 405-484), empty state (řádky 165-173)

---

## Závislosti

```
1 (OverviewTab edit) + 2 (onSave wiring) — jdou spolu
3 (error handling) — nezávislé
4 (ComponentEditPanel) — nezávislé
5 (PropertyMappings UX) — nezávislé
```

## Ověření

1. Environment Manager → Přehled tab → editovat název, uložit → reload → změna zůstane
2. Environment Manager → Přehled → provisionovat neexistující namespace → vidět chybovou hlášku (ne tiché selhání)
3. Environment Manager → Komponenty → editovat PostgreSQL → vidět dropdown verzí (17-alpine, 16-alpine, ...)
4. Environment Manager → Mapování → kliknout Přidat → vidět vysvětlení konceptu + srozumitelné labels
