# Jervis – UI Design System (Compose Multiplatform) – SSOT

**Last updated:** 2026-02-05
**Status:** Production Documentation

This document is the single source of truth (SSOT) for UI guidelines and shared components.

---

## 0) Data Model and Relationships (Connection / Client / Project)

**Hierarchy:**
1. **Connection** – Technical connection to external system (GitHub, GitLab, Jira, Confluence, Bitbucket...)
   - Contains: credentials, URL, auth type
   - Has `capabilities`: Set<ConnectionCapability> (BUGTRACKER, WIKI, REPOSITORY, EMAIL, GIT)
   - Can be global or assigned to a client

2. **Client** – Organization/team
   - **Has assigned Connections** (`connectionIds`) - e.g., GitHub org, Jira workspace
   - **Has connectionCapabilities** - default capability configuration for all projects:
     ```kotlin
     data class ClientConnectionCapabilityDto(
         val connectionId: String,
         val capability: ConnectionCapability,
         val enabled: Boolean = true,
         val resourceIdentifier: String? = null,
         val indexAllResources: Boolean = true,      // true = index all, false = only selected
         val selectedResources: List<String> = emptyList()
     )
     ```
   - **Has default Git commit configuration** for all its projects

3. **Project** – Specific project within a client
   - **Has connectionCapabilities** - overrides client's defaults when set:
     ```kotlin
     data class ProjectConnectionCapabilityDto(
         val connectionId: String,
         val capability: ConnectionCapability,
         val enabled: Boolean = true,
         val resourceIdentifier: String? = null,     // e.g., "PROJ-KEY", "SPACE-KEY", "owner/repo"
         val selectedResources: List<String> = emptyList()
     )
     ```
   - **Inheritance**: If project doesn't have a capability configured, it inherits from client
   - **Can override client's Git commit configuration** (when `null`, inherits from client)

**UI Workflow:**
1. In **Settings → Připojení** create technical connections (e.g., GitHub, Atlassian)
2. In **Settings → Klienti** → click client → "Konfigurace schopností":
   - Assign connections to client
   - For each connection capability: enable/disable, choose "Index all" vs "Only selected resources"
3. In **Settings → Projekty** → click project → "Konfigurace schopností projektu":
   - Override client's capability configuration if needed
   - Select specific resource (repo, Jira project, Confluence space) for each capability
4. Project can override client's Git configuration (checkbox "Přepsat konfiguraci klienta")

---

## 1) Guidelines
- Use existing shared components from `com.jervis.ui.design` (don't add new ones without reason).
- Fail-fast in UI: display errors openly via `JErrorState`, don't hide them.
- Unified screen states (loading/error/empty) via shared components `JCenteredLoading`, `JErrorState`, `JEmptyState`.
- Desktop is the primary platform; mobile is a port of shared screens.
- Code, comments, and logs in English.

### Mobile-first and Shared Screens (Desktop + iPhone)
- All screens are defined in `shared/ui-common` and must work on both desktop and iPhone (Compose Multiplatform).
- No fixed widths; use `Modifier.fillMaxWidth()` and scrolling (`LazyColumn` / `verticalScroll`).
- Touch targets on mobile ≥ 44dp, texts and labels must be readable on small displays.
- Design dialogs and forms with wrapping into columns for narrow displays.

### Development Mode (UI Rules)
- Nothing is masked in UI: passwords, tokens, keys and other "secrets" are always visible (this app is not public).
- In DocumentDB (Mongo) nothing is encrypted – we store plaintext.

## 2) Shared Components (com.jervis.ui.design)
- Basic layout:
  - `JTopBar(title, onBack, actions)` – Main navigation bar.
  - `JSection(title, content)` – Wrapper for logical blocks with background and padding.
  - `JActionBar(content)` – Bar for action buttons (right-aligned).
- Tables and lists:
  - `JTableHeaderRow`, `JTableHeaderCell` – Header for table view.
  - `JTableRowCard(selected, content)` – Card for row in list/table.
- State UI:
  - `JCenteredLoading()` – Centered spinner.
  - `JErrorState(message, onRetry)` – Error state with retry option.
  - `JEmptyState(message, icon)` – Empty state (e.g., no data).
- Actions / utilities (com.jervis.ui.util):
  - `JRunTextButton(onClick, enabled, text)` – Button for running action with ▶ icon.
  - `ConfirmDialog(visible, title, message, confirmText, onConfirm, onDismiss, isDestructive)` – Confirmation dialog.
  - `RefreshIconButton(onClick)` / `DeleteIconButton(onClick)` / `EditIconButton(onClick)` – Standardized buttons with emoji icons.
  - `CopyableTextCard(text, label)` – Card with text that can be copied on click.

## 3) Example Usage
Top bar:
```kotlin
JTopBar(
    title = "Settings",
    onBack = onBack,
    actions = {
        RefreshIconButton(onClick = ::reload)
    }
)
```

Delete with confirmation:
```kotlin
var showDelete by remember { mutableStateOf(false) }

DeleteIconButton(onClick = { showDelete = true })

ConfirmDialog(
    visible = showDelete,
    title = "Delete Connection",
    message = "Are you sure you want to delete this connection? This action cannot be undone.",
    confirmText = "Delete",
    onConfirm = {
        showDelete = false
        handleDelete()
    },
    onDismiss = { showDelete = false }
)
```

States:
```kotlin
if (isLoading) {
    JCenteredLoading()
} else if (error != null) {
    JErrorState(message = error, onRetry = ::load)
} else if (items.isEmpty()) {
    JEmptyState(message = "No items found")
} else {
    // Content
}
```

## 4) Migration and Rules
- Replace direct `TopAppBar` → `JTopBar`.
- Replace direct `CircularProgressIndicator` centered on screen → `JCenteredLoading()`.
- Replace ad-hoc loading/error/empty UI with shared components.
- Use `JSection` for forms and sections in settings.
- Use `JActionBar` for actions below forms.
- Don't add new parameters/side-effects to shared components without agreement.

## 5) Spacing and Style
- Use shared spacing constants from `JervisSpacing`:
  - `outerPadding` (10.dp) – outer margin.
  - `sectionPadding` (12.dp) – inner padding of section.
  - `itemGap` (8.dp) – gap between items.
- Maintain consistent spacing between sections and inner elements.
