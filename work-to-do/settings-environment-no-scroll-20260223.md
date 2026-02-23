# Nastavení + Prostředí: obsah nejde scrollovat (P0)

> **Datum:** 2026-02-23
> **Zjištěno:** Screenshots — v Nastavení (Obecné) i v Prostředí K8s nejde listovat dolů
> **Dopad:** Uživatel se nemůže dostat na spodní část obsahu

---

## 1. BUG: Nastavení (Obecné) — Column bez verticalScroll

### Root Cause

**Soubor:** `shared/ui-common/.../screens/settings/SettingsScreen.kt:150`

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    JSection(title = "Vzhled") { ... }
    JSection(title = "Lokalizace") { ... }
    JSection(title = "Mozek Jervise") { ... }  // ← oříznut, pod okrajem
}
```

`Column` **nemá** `.verticalScroll(rememberScrollState())`. Obsah přesahující výšku okna je oříznut — uživatel nevidí spodní sekce (Mozek Jervise je částečně viditelný, vše pod ním nedostupné).

Ze screenshotu: "Mozek Jervise" sekce je vidět jen částečně (Confluence space dropdown oříznut), scrollbar neexistuje.

### Řešení

```kotlin
val scrollState = rememberScrollState()
Column(
    modifier = Modifier.verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
```

**Poznámka:** Jiné settings sekce scroll mají (IndexingSettings:131, WhisperSettings:152, ClientEditForm:203, ProjectEditForm:207, ProjectGroupEditForm:154) — jen GeneralSettings (hlavní panel v SettingsScreen.kt) ho nemá.

---

## 2. BUG: Prostředí K8s — detail nelze scrollovat

### Root Cause

**Soubor:** `shared/ui-common/.../screens/EnvironmentViewerScreen.kt:170-171`

```kotlin
Column(
    modifier = Modifier.fillMaxSize().padding(padding).padding(JervisSpacing.outerPadding),
) {
    // chips (řádek 185) — fixní
    // status namespace (řádek 228) — fixní
    // LazyColumn (řádek 268) — scrolluje jen SVŮJ obsah
}
```

Vnější `Column` nemá scroll. Obsahuje:
1. **Chips** pro výběr prostředí (horizontální, fixní)
2. **Status namespace** (Stav, Pody, Deploymenty) — fixní, nescrolluje
3. **LazyColumn** s pody, deploymenty, službami — scrolluje jen uvnitř sebe

**Problém:** `LazyColumn` uvnitř `Column` bez explicitní výšky nemá omezenou výšku → nemůže správně scrollovat. Navíc obsah nad `LazyColumn` (status, chips) zabírá prostor ale nescrolluje.

Ze screenshotu: Detail prostředí BMS-Commerzbank ukazuje "Základní informace", "Komponenty", "Konfigurace" — ale pod tím je content oříznutý.

### Řešení (směr)

**Varianta A:** Celý obsah do jednoho scrollable kontejneru:
```kotlin
Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
) {
    // chips, status, resource sections...
}
```
A resource list jako inline `Column` místo `LazyColumn` (pokud je málo položek).

**Varianta B:** Chips a status jako sticky header, `LazyColumn` s `weight(1f)`:
```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    // chips — fixní
    // status — fixní
    LazyColumn(modifier = Modifier.weight(1f)) {
        // pody, deploymenty, služby...
    }
}
```

---

## 3. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../screens/settings/SettingsScreen.kt` | 150 | `Column` bez `verticalScroll` |
| `shared/ui-common/.../screens/EnvironmentViewerScreen.kt` | 170-171 | Vnější `Column` bez scroll |
| `shared/ui-common/.../screens/EnvironmentViewerScreen.kt` | 268 | `LazyColumn` uvnitř non-scrollable Column |
| `shared/ui-common/.../screens/settings/sections/IndexingSettings.kt` | 131 | Příklad správného scrollu ✓ |
| `shared/ui-common/.../screens/settings/sections/WhisperSettings.kt` | 152 | Příklad správného scrollu ✓ |
