# Jervis – UI Design System (Compose Multiplatform) – SSOT

Poslední aktualizace: 2026‑01‑31

Tento dokument je jediný zdroj pravdy (SSOT) pro UI zásady a sdílené komponenty.

---

## 0) Datový model a vztahy (Connection / Client / Project)

**Hierarchie:**
1. **Connection** (Připojení) – technické připojení na externí systém (GitHub, GitLab, Jira, Confluence, Bitbucket...)
   - Obsahuje: credentials, URL, auth type
   - Je globální nebo přiřazeno klientovi

2. **Client** (Klient) – organizace/tým
   - **Má přiřazené Connections** (`connectionIds`) - např. GitHub org, Jira workspace
   - **Má výchozí Git commit konfiguraci** pro všechny své projekty:
     - `gitCommitMessageFormat`, `gitCommitAuthorName/Email`, `gitCommitCommitterName/Email`
     - `gitCommitGpgSign`, `gitCommitGpgKeyId`

3. **Project** (Projekt) – konkrétní projekt v rámci klienta
   - **Vybírá zdroje z connections klienta**:
     - `gitRepositoryConnectionId` + `gitRepositoryIdentifier` – které Git repo z které connection
     - `jiraProjectConnectionId` + `jiraProjectKey` – který Jira projekt
     - `confluenceSpaceConnectionId` + `confluenceSpaceKey` – který Confluence space
   - **Může přepsat Git commit konfiguraci klienta** (když je `null`, dědí se z klienta)

**UI workflow:**
1. V **Connections** se vytvoří technická připojení (např. GitHub org, Jira workspace)
2. V **Clients** se připojení přiřadí klientovi a nastaví výchozí Git konfigurace
3. V **Projects** se vyberou konkrétní zdroje (repo, Jira project, Confluence space) z klientových connections
4. Projekt může přepsat Git konfiguraci klienta (checkbox "Přepsat konfiguraci klienta")

---

## 1) Zásady
- Používej existující sdílené komponenty z `com.jervis.ui.design` (nepřidávej nové bez důvodu).
- Fail‑fast v UI: chyby zobrazuj otevřeně přes `JErrorState`, neukrývej.
- Jednotné stavy obrazovek (loading/error/empty) přes sdílené komponenty `JCenteredLoading`, `JErrorState`, `JEmptyState`.
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

## 2) Sdílené komponenty (com.jervis.ui.design)
- Základní layout:
  - `JTopBar(title, onBack, actions)` – Hlavní navigační lišta.
  - `JSection(title, content)` – Obal pro logické bloky s pozadím a odsazením.
  - `JActionBar(content)` – Lišta pro akční tlačítka (zarovnaná vpravo).
- Tabulky a seznamy:
  - `JTableHeaderRow`, `JTableHeaderCell` – Hlavička tabulkového zobrazení.
  - `JTableRowCard(selected, content)` – Karta pro řádek v seznamu/tabulce.
- Stavové UI:
  - `JCenteredLoading()` – Spinner vycentrovaný na ploše.
  - `JErrorState(message, onRetry)` – Chybový stav s možností opakování.
  - `JEmptyState(message, icon)` – Prázdný stav (např. žádná data).
- Akce / utility (com.jervis.ui.util):
  - `JRunTextButton(onClick, enabled, text)` – Tlačítko pro spuštění akce s ikonou ▶.
  - `ConfirmDialog(visible, title, message, confirmText, onConfirm, onDismiss, isDestructive)` – Potvrzovací dialog.
  - `RefreshIconButton(onClick)` / `DeleteIconButton(onClick)` / `EditIconButton(onClick)` – Standardizovaná tlačítka s emoji ikonami.
  - `CopyableTextCard(text, label)` – Karta s textem, který lze kliknutím zkopírovat.

## 3) Vzorové použití
Top bar:
```kotlin
JTopBar(
    title = "Nastavení",
    onBack = onBack,
    actions = {
        RefreshIconButton(onClick = ::reload)
    }
)
```

Mazání s potvrzením:
```kotlin
var showDelete by remember { mutableStateOf(false) }

DeleteIconButton(onClick = { showDelete = true })

ConfirmDialog(
    visible = showDelete,
    title = "Smazat připojení",
    message = "Opravdu chcete smazat toto připojení? Tuto akci nelze vrátit.",
    confirmText = "Smazat",
    onConfirm = {
        showDelete = false
        handleDelete()
    },
    onDismiss = { showDelete = false }
)
```

Stavy:
```kotlin
if (isLoading) {
    JCenteredLoading()
} else if (error != null) {
    JErrorState(message = error, onRetry = ::load)
} else if (items.isEmpty()) {
    JEmptyState(message = "Žádné položky nenalezeny")
} else {
    // Content
}
```

## 4) Migrace a pravidla
- Nahrazuj přímé `TopAppBar` → `JTopBar`.
- Nahrazuj přímé `CircularProgressIndicator` uprostřed plochy → `JCenteredLoading()`.
- Ad‑hoc loading/error/empty UI nahraď sdílenými komponentami.
- Pro formuláře a sekce v nastavení používej `JSection`.
- Pro akce pod formuláři používej `JActionBar`.
- Nezaváděj nové parametry/side‑effects do sdílených komponent bez domluvy.

## 5) Spacing a styl
- Používej sdílené spacing konstanty z `JervisSpacing`:
  - `outerPadding` (10.dp) – vnější okraj.
  - `sectionPadding` (12.dp) – vnitřní okraj sekce.
  - `itemGap` (8.dp) – mezera mezi prvky.
- Dodržuj konzistentní odsazení mezi sekcemi a vnitřními prvky.
