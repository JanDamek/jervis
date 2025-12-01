# Jervis – UI Design System (Compose Multiplatform) – SSOT

Poslední aktualizace: 2025‑11‑28

Tento dokument je jediný zdroj pravdy (SSOT) pro UI zásady a sdílené komponenty.

## 1) Zásady
- Používej existující sdílené komponenty (nepřidávej nové bez důvodu).
- Fail‑fast v UI: chyby zobrazuj otevřeně, neukrývej.
- Jednotné stavy obrazovek (loading/error/empty) přes sdílené komponenty.
- Desktop je primární platforma; mobil je port sdílených obrazovek.
- Kód, komentáře a logy v angličtině.

### Mobile‑first a sdílené obrazovky (Desktop + iPhone)
- Všechny obrazovky jsou definované ve `shared/ui-common` a musí fungovat jak na desktopu, tak na iPhonu (Compose Multiplatform).
- Žádné pevné šířky; používej `Modifier.fillMaxWidth()` a skrolování (`LazyColumn` / `verticalScroll`).
- Touch‑targety na mobilu ≥ 44dp, texty a labely musí být čitelné na malém displeji.
- Dialogy a formuláře navrhuj se zalamováním do sloupců pro úzké displeje.

### Development mód (UI pravidla)
- V UI nic nemaskujeme: hesla, tokeny, klíče a jiné „secrets“ jsou vždy viditelné (tato aplikace není veřejná).
- V DocumentDB (Mongo) se nic nešifruje – ukládáme plaintext. 

## 2) Sdílené komponenty (shared/ui-common)
- Základ:
  - `com.jervis.ui.design.JTopBar`
  - `com.jervis.ui.design.JSection`
  - `com.jervis.ui.design.JActionBar`
  - `com.jervis.ui.design.JTableHeaderRow`, `JTableHeaderCell`
  - `com.jervis.ui.design.JTableRowCard`
- Stavové UI:
  - `com.jervis.ui.design.JCenteredLoading`
  - `com.jervis.ui.design.JErrorState(message, onRetry)`
  - `com.jervis.ui.design.JEmptyState(message)`
- Akce / utility:
  - `com.jervis.ui.design.JRunTextButton`
  - `com.jervis.ui.util.ConfirmDialog`
  - `com.jervis.ui.util.RefreshIconButton` / `DeleteIconButton` / `EditIconButton`
  - `com.jervis.ui.util.CopyableTextCard`

## 3) Vzorové použití
Top bar:
```kotlin
JTopBar(
    title = "Screen Title",
    onBack = onBack,
    actions = {
        com.jervis.ui.util.RefreshIconButton(onClick = ::reload)
    }
)
```

Mazání s potvrzením:
```kotlin
com.jervis.ui.util.DeleteIconButton(onClick = { showDelete = true })
com.jervis.ui.util.ConfirmDialog(
    visible = showDelete,
    title = "Delete {ItemType}",
    message = "Are you sure you want to delete this {item}? This action cannot be undone.",
    confirmText = "Delete",
    onConfirm = ::handleDelete,
    onDismiss = { showDelete = false }
)
```

Stavy:
```kotlin
// Loading
com.jervis.ui.design.JCenteredLoading()

// Error s retry
com.jervis.ui.design.JErrorState(
    message = "Failed to load data",
    onRetry = ::reload
)

// Empty
com.jervis.ui.design.JEmptyState(message = "No items found")
```

Indexovací akce:
```kotlin
com.jervis.ui.design.JRunTextButton(onClick = ::runNow)
```

## 4) Migrace a pravidla
- Nahrazuj přímé `TopAppBar` → `JTopBar`.
- Ad‑hoc loading/error/empty UI nahraď sdílenými komponentami.
- Nezaváděj nové parametry/side‑effects do sdílených komponent bez domluvy.

## 5) Spacing a styl
- Používej sdílené spacing konstanty (např. `JervisSpacing`).
- Dodržuj konzistentní odsazení mezi sekcemi a vnitřními prvky.
