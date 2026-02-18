# Jervis – UI Design System (Compose Multiplatform) – SSOT

**Last updated:** 2026-02-17
**Status:** Production Documentation

This document is the **single source of truth** for UI guidelines, design patterns, and shared components.
All new UI work MUST follow these patterns to keep the app visually and ergonomically unified.

---

## 0) Data Model and Relationships (Connection / Client / Project)

**Hierarchy:**
1. **Connection** – Technical connection to external system (GitHub, GitLab, Jira, Confluence, Bitbucket...)
   - Contains: credentials, URL, auth type
   - Has `capabilities`: Set<ConnectionCapability> (BUGTRACKER, WIKI, REPOSITORY, EMAIL_READ, EMAIL_SEND)
   - Can be global or assigned to a client

2. **Client** – Organization/team
   - **Has assigned Connections** (`connectionIds`) - e.g., GitHub org, Jira workspace
   - **Contains Project Groups** (`ProjectGroupDocument`) - logical grouping of projects
   - **Contains Environments** (`EnvironmentDocument`) - K8s namespace definitions
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

3. **Project Group** – Logical grouping of projects within a client
   - **Has shared resources** (`ProjectResource`, `ResourceLink`)
   - **KB cross-visibility**: All projects in a group share KB data
   - **Environment inheritance**: Group-level environments apply to all projects in group

4. **Project** – Specific project within a client
   - **Belongs to optional group** (`groupId: ProjectGroupId?`)
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
   - **Can override client's Cloud Model Policy** (`cloudModelPolicy: CloudModelPolicy?`, `null` = inherit)

5. **CloudModelPolicy** – Per-provider auto-escalation toggles
   - `autoUseAnthropic: Boolean` – auto-escalate to Anthropic Claude on local failure
   - `autoUseOpenai: Boolean` – auto-escalate to OpenAI GPT-4o on local failure
   - `autoUseGemini: Boolean` – auto-escalate to Gemini for large context (>49k tokens)
   - Client level: defaults for all projects. Project level: nullable override.

**UI Workflow:**
1. In **Settings -> Pripojeni** create technical connections (e.g., GitHub, Atlassian)
2. In **Settings -> Klienti** -> click client -> "Konfigurace schopnosti":
   - Assign connections to client
   - For each connection capability: enable/disable, choose "Index all" vs "Only selected resources"
3. In **Settings -> Projekty** -> click project -> "Konfigurace schopnosti projektu":
   - Override client's capability configuration if needed
   - Select specific resource (repo, Jira project, Confluence space) for each capability
4. Project can override client's Git configuration (checkbox "Prepsat konfiguraci klienta")
5. Cloud model policy: In client form "Cloud modely" section — 3 checkboxes for auto-escalation. Project can override with "Přepsat konfiguraci klienta" checkbox.

---

## 1) Adaptive Layout Architecture

### 1.1) Breakpoints

```kotlin
// JervisBreakpoints.kt
const val WATCH_DP = 200
const val COMPACT_DP = 600
const val MEDIUM_DP = 840
const val EXPANDED_DP = 1200

enum class WindowSizeClass { WATCH, COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowSizeClass(): WindowSizeClass
```

| Width         | Class        | Devices                        |
|---------------|--------------|--------------------------------|
| < 200 dp      | **Watch**    | Smartwatch (future)            |
| < 600 dp      | **Compact**  | iPhone, Android phone          |
| < 840 dp      | **Medium**   | Small tablet                   |
| >= 600 dp     | **Expanded** | iPad, Android tablet, Desktop  |

Detection uses `BoxWithConstraints` inside the layout composables. **Never add platform
expect/actual for layout decisions** -- width-based detection works everywhere.

### 1.2) App-Level Navigation Architecture

**Stack-based navigation** with `AppNavigator`:
- `navigateTo(screen)` pushes current screen to back-stack, shows new screen
- `goBack()` pops back-stack (returns to previous screen, not always Main)
- `navigateAndClearHistory(screen)` resets stack (e.g., navigating to Main from project link)
- `canGoBack: StateFlow<Boolean>` — true when back-stack is not empty

**PersistentTopBar** (always visible above all screens):
```
┌─────────────────────────────────────────────────────┐
│ [←] [≡]  Client ▾ / Project ▾   ● REC  🤖agent K8s●│
└─────────────────────────────────────────────────────┘
```
- **Back arrow** — shown only when `canGoBack` is true
- **Menu dropdown** — reorganized: Daily (User Tasks, Meetings) → Management (Fronta, Plánovač, Indexace, K8s) → Debug (Logs, RAG) → Config (Settings)
- **Client/Project selector** — compact text "ClientName / ProjectName" with dropdown, `weight(1f)`, truncates on small screens
- **Recording indicator** — red blinking dot + duration, clickable → Meetings
- **Agent status** — spinner when running / dot when idle, clickable → AgentWorkload
- **K8s badge** — clickable → toggle environment panel
- **Connection dot** — green (connected), spinner (connecting), refresh icon (disconnected)

**Per-screen JTopBar** shows only the **title** (no back arrow — handled by PersistentTopBar).
Internal navigation (detail → list within a screen) still uses JTopBar's onBack.

### 1.2.1) Navigation Patterns by Mode

| Mode       | Category nav                          | Entity list -> detail            |
|------------|---------------------------------------|---------------------------------|
| Compact    | Full-screen list; tap -> full-screen section | List replaces with full-screen detail form |
| Expanded   | 240 dp sidebar + content side-by-side   | Same (list replaces with detail form)       |

### 1.3) Decision Tree -- Which Layout Composable to Use

```
Need category-based navigation (settings, admin panels)?
  -> JAdaptiveSidebarLayout

Need sidebar navigation alongside primary content (main screen)?
  -> Custom BoxWithConstraints with sidebar + content (see S5.1.1)

Need entity list with create/edit/detail (clients, projects)?
  -> JListDetailLayout + JDetailScreen for the edit form

Need a simple scrollable form (general settings)?
  -> Column with verticalScroll inside a JSection

Need a flat list with per-row actions (connections, logs)?
  -> LazyColumn with JCard items + JActionBar at top
```

---

## 2) Design Principles

### 2.1) Core Rules

| Rule                          | Details                                                                       |
|-------------------------------|-------------------------------------------------------------------------------|
| **Consistency**               | Use shared components from `com.jervis.ui.design`, don't invent new wrappers |
| **Fail-fast in UI**           | Show errors via `JErrorState` with retry, never silently hide                 |
| **Unified screen states**     | Every data-loading screen uses `JCenteredLoading` / `JErrorState` / `JEmptyState` |
| **Touch targets >= 44 dp**    | `JervisSpacing.touchTarget` -- all clickable rows, icon buttons, checkboxes    |
| **No fixed widths**           | Use `fillMaxWidth()`, `weight()`, scrolling. The only fixed width is the sidebar (240 dp on expanded) |
| **Czech UI labels**           | All user-facing text in Czech, code/comments/logs in English                 |
| **No secrets masking**        | Passwords, tokens, keys always visible (private app)                         |
| **No over-engineering**       | Solve the current screen, don't generalize prematurely                       |

### 2.2) Card Style

All list items, resource rows, log entries, connection cards use `JCard`:

```kotlin
JCard(
    modifier = Modifier.fillMaxWidth(),
) {
    // card content
}
```

`JCard` wraps `Card` with `CardDefaults.outlinedCardBorder()` and no elevation. Optional `onClick` and `selected` parameters handle click and selection state (uses `secondaryContainer` for selected).

**Never** use raw `Card(elevation = ..., surfaceVariant)` or manual `outlinedCardBorder()` calls -- always use `JCard`.
Cards in sections (like `JSection`) may omit the border because the section already provides visual grouping.

### 2.3) Touch Targets

Every interactive element must have a minimum height of 44 dp:

```kotlin
// Row with click action
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { ... }
        .heightIn(min = JervisSpacing.touchTarget),
    verticalAlignment = Alignment.CenterVertically,
)

// Icon button -- use JIconButton (enforces 44dp)
JIconButton(icon = Icons.Default.Edit, onClick = { ... })

// Checkbox/RadioButton rows
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
)
```

### 2.4) Action Buttons Placement

```
Top of a list screen    -> JActionBar with JRefreshButton + JAddButton (or JPrimaryButton)
Detail form bottom      -> JDetailScreen provides save/cancel automatically
Inline per-card actions -> Row with Arrangement.spacedBy(8.dp, Alignment.End)
                           using JEditButton, JDeleteButton, JRefreshButton
Delete with confirm     -> JConfirmDialog triggered by JDeleteButton
```

### 2.5) List Pagination & Server-Side Filtering (Universal Pattern)

**RULE:** Never load unbounded data into UI. All list screens MUST paginate server-side.

| Aspect            | Value                                                        |
|-------------------|--------------------------------------------------------------|
| **Page size**     | 10–20 items (configurable per screen, default 20)            |
| **Loading**       | First page on screen open. More via "Načíst další" button or scroll trigger |
| **Filter/search** | Server-side query (regex/like on DB), debounced 300ms        |
| **Chat history**  | Last 10 messages, older loaded on scroll up (`beforeSequence` cursor) |
| **Sort**          | Server-side (typically `createdAt DESC`)                     |

**Backend pattern:**
```kotlin
// RPC interface — offset-based pagination with server-side filter
suspend fun listAll(query: String? = null, offset: Int = 0, limit: Int = 20): PageDto

// PageDto — generic shape for paginated responses
data class PageDto(
    val items: List<ItemDto>,
    val totalCount: Int,
    val hasMore: Boolean,
)

// Service — MongoDB query with regex filter + skip/limit
val criteria = Criteria.where("type").`is`(TYPE)
if (!query.isNullOrBlank()) {
    criteria.orOperator(
        Criteria.where("field1").regex(".*${Regex.escape(query)}.*", "i"),
        Criteria.where("field2").regex(".*${Regex.escape(query)}.*", "i"),
    )
}
val dataQuery = Query(criteria)
    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
    .skip(offset.toLong()).limit(limit)
```

**UI pattern:**
```kotlin
// State
var items by remember { mutableStateOf<List<T>>(emptyList()) }
var hasMore by remember { mutableStateOf(false) }
var totalCount by remember { mutableStateOf(0) }

// Load function (append=true for "load more")
fun load(query: String?, append: Boolean = false) {
    val offset = if (append) items.size else 0
    val page = repository.service.listAll(query, offset, PAGE_SIZE)
    items = if (append) items + page.items else page.items
    hasMore = page.hasMore
    totalCount = page.totalCount
}

// Debounced filter (server-side)
LaunchedEffect(filterText) {
    delay(300)
    load(filterText)
}

// JListDetailLayout with listFooter for "load more"
JListDetailLayout(
    items = items,
    listFooter = if (hasMore) { { LoadMoreButton(...) } } else null,
    ...
)
```

**Forbidden:**
- Loading all records and filtering client-side (`findAll().toList().filter{}`)
- Displaying unbounded lists without pagination
- Client-side sorting of server data (sort on DB)

---

## 3) Shared Components Reference

All components live in the `com.jervis.ui.design` package, split by category (see Section 10).
Each `Design*.kt` file groups related components (buttons, forms, cards, etc.).

### 3.1) Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTopBar` | Navigation bar with IconButton + ArrowBack icon; uses `surfaceContainerHigh` background for subtle elevation in dark theme | `title`, `onBack?`, `actions` |
| `JSection` | Visual grouping with title and padding | `title`, `content` |
| `JActionBar` | Right-aligned action buttons bar | `modifier`, `content: RowScope` |
| `JVerticalSplitLayout` | Two-pane vertical split with draggable handle | `splitFraction`, `onSplitChange`, `topContent`, `bottomContent` |

### 3.2) State Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JCenteredLoading` | Centered circular progress | -- |
| `JErrorState` | Error message (selectable via `SelectionContainer`) + retry button | `message`, `onRetry?` |
| `JEmptyState` | Empty data state with icon (2 overloads) | `message`, `icon` |

### 3.3) Adaptive Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JAdaptiveSidebarLayout<T>` | Sidebar (expanded) / category list (compact) | `categories`, `selectedIndex`, `onSelect`, `onBack`, `title`, `categoryIcon: @Composable (T) -> Unit`, `categoryTitle`, `categoryDescription`, `content` |
| `JListDetailLayout<T>` | List with detail navigation | `items`, `selectedItem`, `isLoading`, `onItemSelected`, `emptyMessage`, `emptyIcon`, `listHeader`, `listFooter?`, `listItem`, `detailContent` |
| `JDetailScreen` | Full-screen edit form with back + save/cancel | `title`, `onBack`, `onSave?`, `saveEnabled`, `actions`, `content: ColumnScope` |
| `JNavigationRow` | Touch-friendly nav row (compact mode) | `icon: @Composable () -> Unit`, `title`, `subtitle?`, `onClick`, `trailing` |
| `JVerticalSplitLayout` | Draggable vertical split (top/bottom) | `splitFraction`, `onSplitChange`, `topContent`, `bottomContent` |
| `JHorizontalSplitLayout` | Draggable horizontal split (left/right) | `splitFraction`, `onSplitChange`, `minFraction`, `maxFraction`, `leftContent`, `rightContent` |

Note: `categoryIcon` in `JAdaptiveSidebarLayout` takes a `@Composable (T) -> Unit` lambda (not a `String`). `JNavigationRow.icon` is also `@Composable () -> Unit` (not `String`).

### 3.4) Data Display Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JKeyValueRow` | Label/value pair with primary-colored label | `label`, `value` |
| `JStatusBadge` | Green/yellow/red status dot with label | `status` |
| `JCodeBlock` | Monospace text display | `content` |
| `JTableHeaderRow` | Table header row | `content` |
| `JTableHeaderCell` | Single header cell | `text`, `weight` |
| `JTableRowCard` | Selectable row card (outlinedCardBorder + secondaryContainer) | `selected`, `onClick`, `content` |

### 3.5) Form Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTextField` | Wraps `OutlinedTextField` with error display | `value`, `onValueChange`, `label`, `placeholder?`, `isError`, `errorMessage?`, `enabled`, `singleLine`, `readOnly`, `trailingIcon?`, `visualTransformation`, `keyboardOptions`, `minLines`, `maxLines` |
| `JDropdown<T>` | Dropdown via `ExposedDropdownMenuBox` | `items`, `selectedItem`, `onItemSelected`, `label`, `itemLabel`, `enabled`, `placeholder` |
| `JSwitch` | Switch with label and optional description | `label`, `checked`, `onCheckedChange`, `description?`, `enabled` |
| `JSlider` | Slider with label and value display | `label`, `value`, `onValueChange`, `valueRange`, `steps`, `valueLabel`, `description?` |
| `JCheckboxRow` | Checkbox with label | `label`, `checked`, `onCheckedChange`, `enabled` |

### 3.6) Button Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JPrimaryButton` | Primary-color Material3 button (2 overloads: one with `enabled`, one with `icon`+`text`) | `onClick`, `enabled`, `content` / `icon`, `text` |
| `JSecondaryButton` | `OutlinedButton` wrapper | `onClick`, `enabled`, `content` |
| `JTextButton` | `TextButton` wrapper | `onClick`, `enabled`, `content` |
| `JDestructiveButton` | Error-colored button | `onClick`, `enabled`, `content` |
| `JRunTextButton` | `TextButton` with running state indicator | `onClick`, `isRunning`, `content` |
| `JIconButton` | Enforces 44dp touch target | `icon: ImageVector`, `onClick`, `contentDescription?` |
| `JRefreshButton` | Delegates to `JIconButton(Icons.Default.Refresh)` | `onClick` |
| `JDeleteButton` | Delegates to `JIconButton(Icons.Default.Delete)`, tinted error | `onClick` |
| `JEditButton` | Delegates to `JIconButton(Icons.Default.Edit)` | `onClick` |
| `JAddButton` | Delegates to `JIconButton(Icons.Default.Add)` | `onClick` |
| `JRemoveIconButton` | Close icon + built-in ConfirmDialog; fires `onConfirmed` only after confirmation | `onConfirmed`, `title`, `message`, `confirmText` |

### 3.7) Card Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JCard` | Outlined card, NO elevation, secondaryContainer for selection | `onClick?`, `selected?`, `modifier`, `content` |
| `JListItemCard` | Standard list item card | `title`, `subtitle?`, `onClick`, `leading?`, `trailing?`, `badges?`, `actions?` |
| `JTableHeaderRow` | Table header row | `content` |
| `JTableRowCard` | Selectable row card (NO elevation, outlinedCardBorder + secondaryContainer) | `selected`, `onClick`, `content` |

### 3.8) Dialog Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JConfirmDialog` | Confirmation with Czech defaults | `visible`, `title`, `message`, `confirmText="Potvrdit"`, `dismissText="Zrusit"`, `isDestructive`, `onConfirm`, `onDismiss` |
| `JFormDialog` | Form dialog with scrollable content | `visible`, `title`, `onConfirm`, `onDismiss`, `confirmEnabled`, `confirmText`, `content` |

### 3.9) Feedback Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JSnackbarHost` | Consistent snackbar placement | `hostState` |

### 3.10) Watch Components (prepared for future)

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JWatchActionButton` | Full-width 56dp button for watch | `text`, `onClick`, `isDestructive?` |
| `JWatchApprovalCard` | Minimal approve/deny card | `title`, `description`, `onApprove`, `onDeny` |

### 3.11) Utility Components (`com.jervis.ui.util`)

| Component | Purpose |
|-----------|---------|
| `RefreshIconButton` | Delegates to `JRefreshButton` (Material `Icons.Default.Refresh`, 44dp) |
| `DeleteIconButton` | Delegates to `JDeleteButton` (Material `Icons.Default.Delete`, 44dp, error tint) |
| `EditIconButton` | Delegates to `JEditButton` (Material `Icons.Default.Edit`, 44dp) |
| `ConfirmDialog` | Confirmation dialog with Czech defaults ("Smazat"/"Zrusit"), keyboard support, `isDestructive` flag |
| `ApprovalNotificationDialog` | Orchestrator approval dialog (approve/deny with reason) |
| `CopyableTextCard` | `SelectionContainer` wrapping, outlinedCardBorder (no explicit copy buttons) |

### 3.12) Shared Form Helpers (`com.jervis.ui.screens.settings.sections.ClientsSharedHelpers.kt`)

These are `internal` functions shared by ClientsSettings and ProjectsSettings:

| Function | Purpose |
|----------|---------|
| `getCapabilityLabel(capability)` | Human-readable label for ConnectionCapability |
| `getIndexAllLabel(capability)` | Label for "Index all..." option per capability |
| `GitCommitConfigFields(...)` | Reusable form fields for git commit configuration |

---

## 4) Spacing Constants

```kotlin
object JervisSpacing {
    val outerPadding = 10.dp       // Outer margin around screens
    val sectionPadding = 12.dp     // Inner padding of JSection
    val itemGap = 8.dp             // Gap between list items
    val touchTarget = 44.dp        // Minimum touch target size
    val sectionGap = 16.dp         // Gap between sections in a form
    val fieldGap = 8.dp            // Gap between form fields within a section
    val watchTouchTarget = 56.dp   // Minimum touch target for watch UI
}
```

Breakpoints are defined in `JervisBreakpoints.kt` (see Section 1.1).

### Usage Guidelines

| Context | Spacing |
|---------|---------|
| Between sections in a form | `Arrangement.spacedBy(JervisSpacing.sectionGap)` |
| Between items in a LazyColumn | `Arrangement.spacedBy(JervisSpacing.itemGap)` |
| JSection internal spacing | `JervisSpacing.sectionPadding` (automatic) |
| Screen outer padding | `JervisSpacing.outerPadding` (automatic in JDetailScreen/JAdaptiveSidebarLayout) |
| Between form fields in a section | `Spacer(Modifier.height(JervisSpacing.fieldGap))` |
| Between label and field group | `Spacer(Modifier.height(12.dp))` |

---

## 5) Screen Anatomy Patterns

### 5.1) Category-Based Settings Screen

```
+---------------------------------------------+
| JAdaptiveSidebarLayout                      |
|                                             |
| EXPANDED (>=600dp):                         |
| +----------+----------------------------+   |
| | Sidebar  | Content                    |   |
| | 240dp    | (remaining width)          |   |
| |          |                            |   |
| | [<- Zpet]| Category Title (h2)       |   |
| |          | Category description       |   |
| |          |                            |   |
| | [*] Cat 1| +-- JSection -----------+  |   |
| |   Cat 2  | | Section content...    |  |   |
| |   Cat 3  | +------------------------+  |   |
| |   Cat 4  |                            |   |
| |   Cat 5  |                            |   |
| +----------+----------------------------+   |
|                                             |
| COMPACT (<600dp):                           |
| +---------------------------------------+   |
| | JTopBar: "Nastaveni" [<- back]        |   |
| |                                       |   |
| | +-- JNavigationRow -----------------+ |   |
| | | [Settings] Obecne             [>] | |   |
| | |    Zakladni nastaveni aplikace    | |   |
| | +-----------------------------------+ |   |
| | +-- JNavigationRow -----------------+ |   |
| | | [Business] Klienti a projekty [>] | |   |
| | |    Sprava klientu, projektu ...   | |   |
| | +-----------------------------------+ |   |
| | ...                                   |   |
| +---------------------------------------+   |
+---------------------------------------------+
```

**Implementation:**

```kotlin
enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
    val description: String,
) {
    GENERAL("Obecne", Icons.Default.Settings, "Zakladni nastaveni aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", Icons.Default.Business, "Sprava klientu, projektu a jejich konfigurace."),
    PROJECT_GROUPS("Skupiny projektu", Icons.Default.Folder, "Logicke seskupeni projektu se sdilenou KB."),
    CONNECTIONS("Pripojeni", Icons.Default.Power, "Technicke parametry pripojeni (Atlassian, Git, Email)."),
    INDEXING("Indexace", Icons.Default.Schedule, "Intervaly automaticke kontroly novych polozek (Git, Jira, Wiki, Email)."),
    ENVIRONMENTS("Prostredi", Icons.Default.Language, "Definice K8s prostredi pro testovani."),
    CODING_AGENTS("Coding Agenti", Icons.Default.Code, "Nastaveni API klicu a konfigurace coding agentu."),
    WHISPER("Whisper", Icons.Default.Mic, "Nastaveni prepisu reci na text a konfigurace modelu."),
}

@Composable
fun SettingsScreen(repository: JervisRepository, onBack: () -> Unit) {
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Nastaveni",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category -> SettingsContent(category, repository) },
    )
}
```

**GENERAL category content (3 sections):**

| JSection title | Fields | Notes |
|---|---|---|
| "Vzhled" | Téma aplikace (Systémové) | Basic app preferences |
| "Lokalizace" | Jazyk (Čeština) | Language settings |
| "Mozek Jervise" | Bugtracker connection dropdown → Jira project dropdown, Wiki connection dropdown → Confluence space dropdown, Root page ID text field | Central Jira+Confluence for orchestrator brain. Connections filtered by capability (BUGTRACKER/WIKI). **Project/space dropdowns load dynamically** via `listAvailableResources()` after connection selection. Brain-reserved resources are **filtered out server-side** from all other resource lists in the app. Saved via `SystemConfigService`. |

**Internal project filtering:** `ProjectDto.isJervisInternal` flag + `filterVisible()` extension hides the orchestrator's internal project from all UI lists (top bar, settings, scheduler, meetings, RAG search). The flag is managed via `SystemConfig.jervisInternalProjectId` and synced when changed.

### 5.1.1) App Layout with PersistentTopBar

The app uses a global `PersistentTopBar` above all screens. The main screen (chat) has no SelectorsRow or AgentStatusRow — these live in the PersistentTopBar.

```
ALL SCREEN SIZES:
+----------------------------------------------+
| [←] [≡] Client / Project ▾  ●REC 🤖idle K8s●|  <-- PersistentTopBar (always visible)
|----------------------------------------------|
| [Recording bar — if recording]               |  <-- RecordingBar (global)
|----------------------------------------------|
| Chat messages...                             |  <-- Per-screen content
|                                              |
|----------------------------------------------|
| [Napiste zpravu...]                [Odeslat] |
+----------------------------------------------+

Menu dropdown (on click ≡):
+----------------------------+
| [List]      Uzivatelske ul.|  ← Daily
| [Mic]       Meetingy       |
|----------------------------|
| [Inbox]     Fronta uloh    |  ← Management
| [Calendar]  Planovac       |
| [Schedule]  Fronta indexace|
| [Dns]       Prostredi K8s  |
|----------------------------|
| [BugReport] Chybove logy  |  ← Debug
| [Search]    RAG Hledani    |
|----------------------------|
| [Settings]  Nastaveni      |  ← Config
+----------------------------+
```

**Menu items (reorganized — daily first, settings last):**

```kotlin
private enum class TopBarMenuItem(val icon: ImageVector, val title: String, val group: Int) {
    USER_TASKS(Icons.AutoMirrored.Filled.List, "Uživatelské úlohy", 0),
    MEETINGS(Icons.Default.Mic, "Meetingy", 0),
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta úloh", 1),
    SCHEDULER(Icons.Default.CalendarMonth, "Plánovač", 1),
    INDEXING_QUEUE(Icons.Filled.Schedule, "Fronta indexace", 1),
    ENVIRONMENT_VIEWER(Icons.Default.Dns, "Prostředí K8s", 1),
    ERROR_LOGS(Icons.Default.BugReport, "Chybové logy", 2),
    RAG_SEARCH(Icons.Default.Search, "RAG Hledání", 2),
    SETTINGS(Icons.Default.Settings, "Nastavení", 3),
}
```

**Implementation:** `PersistentTopBar` in `PersistentTopBar.kt` is rendered in `App.kt` above `RecordingBar` and the screen `when` block. It contains the compact client/project selector, menu, recording indicator, agent status icon, K8s badge, and connection indicator. `MainScreenView` in `MainScreen.kt` only contains chat content (banners, messages, input).

**Chat message types** (`ChatMessage.MessageType`):
- `USER_MESSAGE` — user bubble (primaryContainer, right-aligned)
- `PROGRESS` — compact row with `CircularProgressIndicator` (16dp) + bodySmall text
- `FINAL` — assistant bubble (secondaryContainer, left-aligned)
- `ERROR` — compact row with `Icons.Default.Warning` (16dp, error tint) + bodySmall text in `MaterialTheme.colorScheme.error`

**Chat bubble layout** (`ChatMessageDisplay.kt`):

iMessage/WhatsApp-style chat with content-based width:
- **Spacing**: LazyColumn `contentPadding = PaddingValues(24.dp)`, `verticalArrangement = Arrangement.spacedBy(20.dp)`, bubble internal padding `16.dp`
- **Responsive max width**: Uses `BoxWithConstraints` to calculate max width as `maxWidth - 32.dp`
- **Content-based width**: `Card` with `Modifier.widthIn(min = 48.dp, max = maxBubbleWidth)` adapts to content length
- **User messages**: Plain text, `primaryContainer` background, right-aligned, with Edit + Copy icons
- **Assistant messages**: Markdown rendering, `secondaryContainer` background, left-aligned, with Copy icon
- **Markdown support**: Uses `multiplatform-markdown-renderer:0.29.0` with Material 3 theme colors
- **Workflow steps**: Collapsible step list with status icons (✓ completed, ✗ failed, ↻ in-progress, ⏰ pending) and tool usage
- **Timestamps**: Human-readable formatting via `formatMessageTime()` — today: "HH:mm", yesterday: "Včera HH:mm", this year: "d. M. HH:mm", older: "d. M. yyyy HH:mm"

**Edit & Copy actions** (header row of each bubble):
- User messages: `Icons.Default.Edit` (pencil, 18dp icon in 32dp touch target) → sets input text for re-editing + `Icons.Default.ContentCopy` → copies to clipboard
- Assistant messages: `Icons.Default.ContentCopy` only
- Cross-platform clipboard via `ClipboardUtil` (expect/actual: JVM uses `java.awt.Toolkit`, iOS uses `UIPasteboard`, Android stub)

**History pagination** (`ChatArea` component):
- Initial load: 10 messages via `getChatHistory(limit=10)`
- "Načíst starší zprávy" `TextButton` at top of LazyColumn when `hasMore == true`
- Clicking loads next 10 messages using `beforeSequence` cursor, prepends to existing
- Shows `CircularProgressIndicator` while loading

**Context compression markers** (`CompressionBoundaryIndicator`):
- Displayed between messages where compression occurred
- `HorizontalDivider` + `Icons.Default.Summarize` icon + "Komprese kontextu (N zpráv shrnuto)" label
- Expandable summary text with `AnimatedVisibility`
- Data from `CompressionBoundaryDto` (afterSequence, summary, compressedMessageCount, topics)

**File attachment support** (`InputArea` component):
- `Icons.Default.AttachFile` button (44dp, left of text field) opens platform file picker
- Selected files shown as `AssistChip` with file type icon + filename + close button
- File type icons: Image, PDF, Description (text), FolderZip (archives), InsertDriveFile (other)
- Size limit: reject >10MB with error message
- Files encoded to base64 via `AttachmentDto.contentBase64` for RPC transport
- Backend decodes and saves to storage directory
- Platform file pickers: JVM full implementation (`JFileChooser`), Android/iOS stubs returning null

```kotlin
// Responsive max width calculation
BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val maxBubbleWidth = maxWidth - 32.dp  // Account for LazyColumn padding
    Row(...) {
        Card(
            modifier = Modifier.widthIn(min = 48.dp, max = maxBubbleWidth)
        ) { ... }
    }
}
```

### 5.2) Entity List -> Detail Screen

```
LIST VIEW:
+-------------------------------+
| JActionBar: [Refresh] [+ Pridat] |
|                               |
| +-- JCard ------------------+ |
| | Entity Name           [>] | |
| | subtitle / metadata       | |
| +----------------------------+ |
| +-- JCard ------------------+ |
| | Entity Name 2         [>] | |
| | subtitle / metadata       | |
| +----------------------------+ |
| ...                           |
+-------------------------------+

DETAIL VIEW (replaces list):
+-------------------------------+
| JTopBar: "Entity Name" [<-]  |
|                               |
| +-- JSection: Zakladni -----+ |
| | [JTextField: Nazev]        | |
| | [JTextField: Popis]        | |
| +----------------------------+ |
| +-- JSection: Pripojeni ----+ |
| | Connection cards...        | |
| +----------------------------+ |
| ...                           |
|                               |
| +-- JActionBar --------------+ |
| |          [Zrusit] [Ulozit] | |
| +----------------------------+ |
+-------------------------------+
```

**Implementation:**

```kotlin
@Composable
fun ClientsSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    // ...

    JListDetailLayout(
        items = clients,
        selectedItem = selectedClient,
        isLoading = isLoading,
        onItemSelected = { selectedClient = it },
        emptyMessage = "Zadni klienti nenalezeni",
        emptyIcon = "...",
        listHeader = {
            JActionBar {
                JRefreshButton(onClick = { loadClients() })
                JPrimaryButton(onClick = { /* new */ }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pridat klienta")
                }
            }
        },
        listItem = { client ->
            JCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { selectedClient = client },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(client.name, style = MaterialTheme.typography.titleMedium)
                        Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, null)
                }
            }
        },
        detailContent = { client ->
            ClientEditForm(client, repository, onSave = { ... }, onCancel = { selectedClient = null })
        },
    )
}
```

### 5.3) Edit Form (Detail Screen)

```kotlin
@Composable
private fun ClientEditForm(
    client: ClientDto,
    repository: JervisRepository,
    onSave: (ClientDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(client.name) }
    // ... more state ...

    JDetailScreen(
        title = client.name,
        onBack = onCancel,
        onSave = { onSave(client.copy(name = name, ...)) },
        saveEnabled = name.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
        ) {
            JSection(title = "Zakladni udaje") {
                JTextField(value = name, onValueChange = { name = it }, label = "Nazev")
            }
            JSection(title = "Pripojeni klienta") { ... }
            JSection(title = "Git Commit Konfigurace") {
                GitCommitConfigFields(...)  // Shared helper
            }
            Spacer(Modifier.height(JervisSpacing.sectionGap))  // Bottom breathing room
        }
    }
}
```

### 5.4) Flat List with Per-Row Actions (Connections, Logs)

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    JActionBar {
        JPrimaryButton(onClick = { showCreateDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pridat pripojeni")
        }
    }

    Spacer(Modifier.height(JervisSpacing.itemGap))

    if (isLoading && items.isEmpty()) {
        JCenteredLoading()
    } else if (items.isEmpty()) {
        JEmptyState(message = "Zadna pripojeni nenalezena", icon = "...")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(connections) { connection ->
                JCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        // ... content ...
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            JPrimaryButton(onClick = { ... }) { Text("Test") }
                            JEditButton(onClick = { ... })
                            JDeleteButton(onClick = { ... })
                        }
                    }
                }
            }
        }
    }
}
```

### 5.5) Agent Workload Screen (`AgentWorkloadScreen.kt`) — Accordion Layout

Full-screen view accessed by clicking the agent status icon in `PersistentTopBar`.
Uses an **accordion (harmonika) layout** with 4 sections — only one expanded at a time.
Clicking a collapsed header expands it and collapses the previously expanded section.

**Sections**: Agent (default expanded), Frontend, Backend, Historie

```
State: Agent expanded (default)
+-- JTopBar ("Aktivita agenta", onBack) ----------------------+
| V Agent                          <- expanded header          |
|--------------------------------------------------------------|
|                                                               |
|  Bezi: bms / Chat                                            |
|  "Co se dnes udalo na rannim..."                             |
|                                                               |
|  Cil 1/3                                     [X stop]        |
|  [spinner] Planovani kroku                                   |
|            Analyzing project structure...                      |
|  Krok 2/5                                    35%             |
|  [===========                           ] progress bar       |
|                                                               |
|--------------------------------------------------------------|
| > Frontend (3)                   <- collapsed, 44dp, badge   |
|--------------------------------------------------------------|
| > Backend (1)                    <- collapsed, 44dp, badge   |
|--------------------------------------------------------------|
| > Historie (18)                  <- collapsed, 44dp, badge   |
+--------------------------------------------------------------+

State: Frontend expanded
+-- JTopBar ("Aktivita agenta", onBack) ----------------------+
| V Frontend (3)                   <- expanded header          |
|--------------------------------------------------------------|
|  Chat . bms                                                  |
|  Co se dnes udalo na rannim stand...                         |
|  ─────────────────────────────────                           |
|  Chat . bms                                                  |
|  Shrn vysledky sprintu                                       |
|  ─────────────────────────────────                           |
|  Chat . bms                                                  |
|  Vyres UFO-24                                                |
|                                                               |
|--------------------------------------------------------------|
| [spinner] Agent                  <- collapsed + spinner      |
|--------------------------------------------------------------|
| > Backend (1)                    <- collapsed                |
|--------------------------------------------------------------|
| > Historie (18)                  <- collapsed                |
+--------------------------------------------------------------+
```

**AccordionSectionHeader** — shared header composable:
- `Surface` with `clickable`, min height 44dp
- Expanded: `surfaceContainerHigh` background, `KeyboardArrowDown` icon
- Collapsed: `surface` background, `KeyboardArrowRight` icon
- Badge chip (count > 0): `tertiaryContainer` background, `labelSmall`
- AGENT section collapsed: `CircularProgressIndicator(16dp)` if running, `●` dot if idle

**AgentSectionContent** — when running:
- Project name, task type, task preview
- Orchestrator progress: goal/step counters, node spinner, status message, progress bar
- Stop button (`cancelOrchestration`)
- When idle: `JEmptyState("Agent je necinny", Icons.Default.HourglassEmpty)`
- Note: KB qualification progress is shown in IndexingQueueScreen, NOT here

**QueueSectionContent** (Frontend):
- `LazyColumn` with client-side windowing (20 initial, load more on scroll)
- `CompactQueueItemRow`: type+project (labelSmall), preview (bodySmall, 1 line, ellipsis)
- If more items: "... a dalsich N uloh" text below
- If empty: `JEmptyState`

**BackendQueueSectionContent** (Backend) — DB-level paginated:
- First 20 items from `getPendingTasks()`, more via `getBackgroundTasksPage(limit, offset)`
- Badge shows `backgroundTotalCount` (total from DB)
- Infinite scroll with `snapshotFlow` trigger
- Loading indicator during page fetch

**HistorySectionContent** — grouped by tasks:
- `LazyColumn` with `TaskHistoryItem` composables
- Each item: task preview, project name, time range (start – end)
- Click to expand/collapse node list (animated)
- Nodes: `✓` done, `⟳` running, `○` pending
- Newest task on top

**Data models** (`com.jervis.ui.model.AgentActivityEntry`):
- `AgentActivityEntry`: `id`, `time` (HH:mm:ss), `type` (TASK_STARTED/TASK_COMPLETED/AGENT_IDLE/QUEUE_CHANGED), `description`, `projectName?`, `taskType?`, `clientId?`
- `PendingQueueItem`: `taskId`, `preview`, `projectName`, `processingMode` (FOREGROUND/BACKGROUND), `queuePosition`
- `TaskHistoryEntry`: `taskId`, `taskPreview`, `projectName?`, `startTime`, `endTime?`, `status` (running/done/error), `nodes: List<NodeEntry>`
- `NodeEntry`: `node` (key), `label` (Czech), `status` (DONE/RUNNING/PENDING)
- Activity log stored in `AgentActivityLog` ring buffer (max 200), held by `MainViewModel`
- Task history stored in `MainViewModel.taskHistory: StateFlow<List<TaskHistoryEntry>>`, populated from `OrchestratorTaskProgress` and `OrchestratorTaskStatusChange` events

**Dual-queue state** in `MainViewModel`:
- `foregroundQueue: StateFlow<List<PendingQueueItem>>` -- user-initiated tasks (FOREGROUND)
- `backgroundQueue: StateFlow<List<PendingQueueItem>>` -- system/indexing tasks (BACKGROUND)
- `reorderTask(taskId, newPosition)` -- reorder within current queue
- `moveTaskToQueue(taskId, targetMode)` -- move between FOREGROUND and BACKGROUND

**RPC methods** (`IAgentOrchestratorService`):
- `getPendingTasks()` -- returns both queues with taskId and processingMode
- `reorderTask(taskId, newPosition)` -- change position within queue
- `moveTask(taskId, targetProcessingMode)` -- switch between queues

**Queue status emissions**: Backend emits queue status updates that include both FOREGROUND and BACKGROUND items with their taskId, enabling the UI to display and manage both queues independently.

### 5.6) User Tasks Screen (`UserTasksScreen.kt`)

Full-screen view accessed from hamburger menu ("Uzivatelske ulohy"). Shows escalated tasks that require user attention (failed background tasks, approval requests). Uses `JListDetailLayout` + `JDetailScreen` pattern.

**List view:**
```
+-- JTopBar ("Uzivatelske ulohy", onBack, [Refresh]) ----------+
|                                                                |
| [JTextField: Filtr]                                           |
|                                                                |
| +-- JCard ------------------------------------------------+   |
| | Task title                            [Delete] [>]      |   |
| | * USER_TASK  projectId                                   |   |
| +----------------------------------------------------------+   |
| +-- JCard ------------------------------------------------+   |
| | Another task                          [Delete] [>]      |   |
| | * USER_TASK  projectId                                   |   |
| +----------------------------------------------------------+   |
+----------------------------------------------------------------+
```

**Detail view (replaces list via JListDetailLayout):**
```
+-- JDetailScreen ("Task title", onBack) -----------------------+
|                                                                 |
| +-- JSection: Zakladni udaje --------------------------------+ |
| | Stav: USER_TASK                                             | |
| | Projekt: projectId                                          | |
| | Klient: clientId                                            | |
| +-------------------------------------------------------------+ |
| +-- JSection: Odkaz na zdroj --------------------------------+ |
| |                                     [Kopirovat]             | |
| | https://source-uri...                                       | |
| +-------------------------------------------------------------+ |
| +-- JSection: Popis -----------------------------------------+ |
| |                                     [Kopirovat]             | |
| | Task description text...                                    | |
| +-------------------------------------------------------------+ |
| +-- JSection: Dodatecne instrukce ---------------------------+ |
| | [JTextField: placeholder]                                   | |
| +-------------------------------------------------------------+ |
|                                                                 |
| +-- JActionBar -----------------------------------------------+ |
| |                    [Do fronty] [Agentovi]                    | |
| +-------------------------------------------------------------+ |
+------------------------------------------------------------------+
```

**Routing modes:**
- "Do fronty" (`BACK_TO_PENDING`) -- returns task to BACKGROUND processing queue
- "Agentovi" (`DIRECT_TO_AGENT`) -- sends task directly to FOREGROUND agent processing

**Key components:**
- `UserTasksScreen` -- `JListDetailLayout` with filter, list cards, detail view
- `UserTaskRow` -- `JCard` with title, state badge, projectId, `JDeleteButton`, chevron
- `UserTaskDetail` -- `JDetailScreen` with `JSection` blocks, additional instructions input, routing buttons
- `JKeyValueRow` -- Label/value pair with primary-colored label (replaces TaskDetailField)

**Data flow:**
- Loads tasks from all clients via `repository.userTasks.listActive(clientId)`
- Sorted by creation date (oldest first)
- Client-side filter by title, description, projectId
- Delete via `repository.userTasks.cancel(taskId)` with `JConfirmDialog`

### 5.7) Meetings Screen (`MeetingsScreen.kt`)

Recording management screen accessed from the hamburger menu ("Meetingy").
Lists meeting recordings with state indicators, supports starting new recordings and viewing transcripts.

```
Compact (<600dp):
+-- JTopBar ("Meetingy", onBack, [+ Nova]) ------+
|                                                  |
| * Nahravani  03:42     [Zastavit]  <- only       |
|                                     during rec.  |
| +-- JCard ----------------------------------+    |
| | Standup tym Alfa              ok  15:32    |    |
| | 8.2.2026  *  Standup tym                   |    |
| +--------------------------------------------+    |
| +-- JCard ----------------------------------+    |
| | Sprint review                  ...  1:02:15|    |
| | 7.2.2026  *  Review                       |    |
| +--------------------------------------------+    |
+--------------------------------------------------+
```

**Key components:**
- `MeetingsScreen` -- List + detail, manages setup/finalize dialogs
- `MeetingViewModel` -- State: meetings, isRecording, recordingDuration, selectedMeeting
- `RecordingSetupDialog` -- Client, project, audio device selection, system audio toggle
- `RecordingFinalizeDialog` -- Meeting type (radio buttons), optional title
- `RecordingIndicator` -- Animated red dot + elapsed time + stop button (shown during recording)

**State icons:** RECORDING, UPLOADING, UPLOADED/TRANSCRIBING/CORRECTING, TRANSCRIBED/CORRECTED/INDEXED, FAILED

**MeetingDetailView** uses a split layout with transcript on top and agent chat on bottom:

**PipelineProgress** shows pipeline state with optional controls:
- When `state == TRANSCRIBING`: a stop button (`Icons.Default.Stop`, error-tinted) appears on the right side. Calls `viewModel.stopTranscription()` which resets the meeting to UPLOADED and deletes the K8s Whisper job.
- Below the status text: last transcript segment text preview (`bodySmall`, `alpha(0.7f)`, `maxLines = 2`) gives real-time feedback on transcription progress.

```
Expanded (>=600dp):
+--------------------------------------------------+
| JTopBar: "Meeting Title"  [play] [book] [...]    |
+--------------------------------------------------+
| Metadata: date, type, duration                   |
| PipelineProgress + Error/Questions cards         |
+--------------------------------------------------+
|                                                  |
| TRANSCRIPT PANEL (LazyColumn, selectable text)   |
|   Corrected/Raw toggle chips + action buttons    |
|   Each row: [time] [text] [edit] [play/stop]     |
|                                                  |
+======= DRAGGABLE SPLITTER (JVerticalSplitLayout) +
|                                                  |
| AGENT CHAT PANEL                                 |
|   Chat history (LazyColumn, auto-scroll)         |
|   Processing indicator (if correcting)           |
|   [Instruction JTextField] [Odeslat]             |
|                                                  |
+--------------------------------------------------+

Compact (<600dp):
Same layout but chat panel has fixed 180dp height (no interactive splitter).
```

**Split layout details:**
- Expanded: `JVerticalSplitLayout` with default 70/30 split, draggable via `draggable()` modifier (clamped 0.3..0.9)
- Compact: `Column` with `weight(1f)` transcript + fixed `height(180.dp)` chat
- No `JDetailScreen` wrapper (conflicts with nested scrolling); uses custom `Column` + `JTopBar`

**MeetingDetailView states:**

| State | UI Behaviour |
|-------|-------------|
| RECORDING | Text "Probiha nahravani..." |
| UPLOADING / UPLOADED | Text "Ceka na prepis..." |
| TRANSCRIBING | `JCenteredLoading` + text "Probiha prepis..." + stop button ("Zastavit") + last segment preview |
| CORRECTING | `JCenteredLoading` + text "Probiha korekce prepisu..." |
| CORRECTION_REVIEW | `CorrectionQuestionsCard` + best-effort corrected transcript |
| FAILED | Error card (`errorContainer`) with selectable text + "Přepsat znovu" button + "Zamítnout" (dismiss, only if transcript exists) |
| TRANSCRIBED | Raw transcript only (via `TranscriptPanel`) |
| CORRECTED / INDEXED | `FilterChip` toggle (Opraveny / Surovy) + "Prepsat znovu" button + `TranscriptPanel` |

**MeetingDetailView** actions bar includes:
- Play/Stop toggle (full audio playback)
- Book icon -> navigates to `CorrectionsScreen` sub-view (managed as `showCorrections` state)
- JRefreshButton, JDeleteButton
- When `state == CORRECTION_REVIEW`: `CorrectionQuestionsCard` is shown below the pipeline progress

**TranscriptPanel** -- composable with `FilterChip` toggle, action buttons, and `LazyColumn` wrapped in `SelectionContainer` for text copy support.

**TranscriptSegmentRow** -- each row layout: `[time (52dp)] [text (weight 1f)] [edit button] [play/stop button]`
- Text is always selectable/copyable (no correction mode toggle needed)
- Edit button opens `SegmentCorrectionDialog` with `SegmentEditState` (original + corrected text + timing)
- Play/Stop button plays the audio range from this segment's `startSec` to the next segment's `startSec`
- `playingSegmentIndex` state highlights the currently playing segment's play button

**SegmentCorrectionDialog** -- redesigned `JFormDialog` for segment editing:
- Shows read-only **original (raw) text** in a `Card(surfaceVariant)` with `SelectionContainer` for copy
- Play button next to "Original:" label plays segment audio range
- Editable `JTextField` pre-filled with **corrected text** (from `correctedTranscriptSegments` if exists, else raw)
- Confirm button enabled only when text is non-blank and differs from initial
- "Přepsat segment" button: retranscribes this segment via Whisper (calls `retranscribeSegments` RPC)
- On confirm: auto-switches to "Opraveny" (corrected) view via `showCorrected = true`
- State: `SegmentEditState(segmentIndex, originalText, editableText, startSec, endSec)`

**AudioPlayer** -- `expect class` with platform actuals:
- `play(audioData)` -- full audio playback
- `playRange(audioData, startSec, endSec)` -- range playback for per-segment play
- JVM: `javax.sound.sampled.Clip` with frame position + timer thread
- Android: `MediaPlayer` with `seekTo()` + `Handler.postDelayed()`
- iOS: `AVAudioPlayer` with `currentTime` + `NSTimer`

**AgentChatPanel** -- chat-style panel with:
- `LazyColumn` of chat bubbles (user = primaryContainer, agent = secondaryContainer, error = errorContainer)
- Auto-scroll to newest message via `LaunchedEffect`
- Processing indicator during active correction
- Input row: `JTextField` (1-3 lines) + "Odeslat" button
- Chat history persisted in `MeetingDocument.correctionChatHistory`
- Optimistic user message via `pendingChatMessage` ViewModel state

```kotlin
@Composable
private fun AgentChatPanel(
    chatHistory: List<CorrectionChatMessageDto>,
    pendingMessage: CorrectionChatMessageDto?,
    isCorrecting: Boolean,
    onSendInstruction: (String) -> Unit,
    modifier: Modifier,
)
```

**Audio capture:** `expect class AudioRecorder` with platform actuals:
- Android: AudioRecord API (VOICE_RECOGNITION source)
- Desktop: Java Sound API (TargetDataLine)
- iOS: AVAudioEngine

### 5.8) Corrections Screen (`CorrectionsScreen.kt`)

Sub-view of MeetingDetailView for managing KB-stored transcript correction rules.
Accessible via the book icon in MeetingDetailView action bar.

```
+-- JDetailScreen ("Korekce prepisu", onBack, [+ Pridat]) ---+
|                                                              |
| Jmena osob                  <- category header (primary)    |
| +-- JCard -----------------------------------------------+  |
| | "honza novak" -> "Honza Novak"              [Delete]   |  |
| |  Optional context text                                 |  |
| +--------------------------------------------------------+  |
| Nazvy firem                                                  |
| +-- JCard -----------------------------------------------+  |
| | "damek soft" -> "DamekSoft"                 [Delete]   |  |
| +--------------------------------------------------------+  |
| ...                                                          |
+--------------------------------------------------------------+
```

**Key components:**
- `CorrectionsScreen` -- `JDetailScreen` with `LazyColumn` of entries grouped by category string, add/delete
- `CorrectionViewModel` -- States: `corrections: StateFlow<List<TranscriptCorrectionDto>>`, `isLoading`; Methods: `loadCorrections()`, `submitCorrection()`, `deleteCorrection()`
- `CorrectionCard` -- `JCard` with original->corrected mapping, optional context text, `JDeleteButton`
- `CorrectionDialog` -- `JFormDialog` with fields: original, corrected, category (`JDropdown`), context; reusable (`internal`) from MeetingsScreen correction mode

**Correction categories**: person_name, company_name, department, terminology, abbreviation, general

### 5.8.1) Correction Questions Card

Inline card shown in MeetingDetailView when `state == CORRECTION_REVIEW`. Displays questions from the correction agent when it's uncertain about proper nouns or terminology. The card is in a resizable panel (draggable divider) so users can adjust the split between corrections and transcript.

```
+-- Card (tertiaryContainer) ------------------------------------+
| Agent potrebuje vase upesneni                                   |
| Opravte nebo potvdte spravny tvar (0/N potvrzeno):             |
|                                                                 |
| Correct spelling?                              [Play/Stop]     |
| Puvodne: "jan damek"                                           |
| [Jan Damek] [Jan Dameck]    <- FilterChip options              |
| [____Spravny tvar____]  [Nevim]  [Potvrdit]                   |
|                                                                 |
| "jan damek" -> "Jan Damek"                              [✓]   |
|                                    [Odeslat vse (N)]           |
+-----------------------------------------------------------------+
======== draggable divider (resizable) =========
```

**Correction flow:**
- Each question has a **play button** (±10s audio around the segment) so users can listen before deciding.
- **Potvrdit** confirms the correction locally (collapses to summary row).
- **Nevím** marks the segment for re-transcription with Whisper large-v3 (beam_size=10, ±10s audio extraction).
- **Odeslat vše** submits all confirmed answers:
  - Known corrections are applied **in-place** to the transcript segments (no full re-correction).
  - Each correction is saved as a KB rule for future use.
  - "Nevím" segments trigger targeted re-transcription + correction.
- The LLM agent **filters out** questions whose `original` text matches existing KB correction rules, preventing re-asking of already-corrected terms.
- The pipeline progress bar clears the last transcription segment preview once transcription is complete.

### 5.8) Pending Tasks Screen (`PendingTasksScreen.kt`)

Task queue management screen accessed from the hamburger menu ("Fronta uloh").
Shows filterable list of pending tasks with delete capability. Uses **Pattern D** (flat list with per-row actions).

```
+-- JTopBar ("Fronta uloh (42)", onBack, [Refresh]) ----------+
|                                                               |
| +-- JSection ("Filtry") -----------------------------------+ |
| | [Typ ulohy v Vse]    [Stav v Vse]                        | |
| +------------------------------------------------------------+ |
|                                                               |
| +-- JCard ------------------------------------------------+  |
| | Zpracovani emailu                          [Delete]     |  |
| | [K kvalifikaci]  [Projekt: abc12345]                    |  |
| | Klient: def456...                                       |  |
| | Vytvoreno: 2024-01-15 10:30                             |  |
| | Email content preview text here...                      |  |
| | Prilohy: 2                                              |  |
| +----------------------------------------------------------+  |
|                                                               |
| +-- JCard ------------------------------------------------+  |
| | Uzivatelsky vstup                          [Delete]     |  |
| | [Novy]                                                  |  |
| | ...                                                     |  |
| +----------------------------------------------------------+  |
+---------------------------------------------------------------+
```

**Key components:**
- `JDropdown` -- for task type and state filtering
- `PendingTaskCard` -- `JCard` with Czech labels for task types/states via `getTaskTypeLabel()` / `getTaskStateLabel()`
- `SuggestionChip` for state and project badges (consistent with ConnectionsSettings)
- `JSnackbarHost` in Scaffold for delete feedback
- `JConfirmDialog` for delete confirmation

**Data:** `PendingTaskDto` with `id`, `taskType`, `content`, `projectId?`, `clientId`, `createdAt`, `state`, `attachments`

### 5.9) Indexing Queue Screen

Dashboard showing the full indexing pipeline with 4 accordion sections. One section expanded at a time, collapsed sections show as headers with badge counts at the bottom.

```
+---------------------------------------------------------------+
| JTopBar: "Fronta indexace"                        [<- Zpet]   |
+---------------------------------------------------------------+
| [v] Zdroje (35)                       ← expanded section      |
+---------------------------------------------------------------+
| ConnectionGroupCard: GitHub (12)                               |
|   ├─ BUGTRACKER (5)  Za 8m  [Clock] [▶ Spustit]              |
|   │   ├─ Commerzbank (3)                             [v]      |
|   │   │   ├─ [Bug] GH-123 summary  |  GitHub  NEW            |
|   │   │   └─ [Bug] GH-456 login bug  |  GitHub  NEW          |
|   │   └─ ClientX (2)                                 [>]      |
|   └─ REPOSITORY (7)  Za 3m  [Clock] [▶ Spustit]              |
|       ├─ Commerzbank (5)                             [>]      |
|       └─ ClientY (2)                                 [>]      |
| ConnectionGroupCard: IMAP Mail (5)                    [>]      |
+---------------------------------------------------------------+
| [>] KB zpracování (3)                 ← collapsed             |
+---------------------------------------------------------------+
| [>] KB fronta (150)                   ← collapsed             |
+---------------------------------------------------------------+
| [>] Hotovo (2500)                     ← collapsed             |
+---------------------------------------------------------------+
```

**When "KB zpracování" is expanded:**
```
+---------------------------------------------------------------+
| [v] KB zpracování (3)                                          |
+---------------------------------------------------------------+
| [Bug] GH-100 · GitHub · Commerzbank                 Indexuje   |
|       Indexuji do knowledge base...    ← live progress (tertiary) |
| [Mail] Subject · Email · Klient2                    Analyzuje  |
|       Analyzuji obsah...               ← live progress          |
| [Code] fix: auth · Git · ClientX                    Zpracovává |
+---------------------------------------------------------------+
```

**Accordion sections (3):**
1. **KB zpracování** (QUALIFYING) — items currently processed by SimpleQualifierAgent, with **live progress messages** + **audit trail metadata** from `QualificationProgress` events
2. **KB fronta** (READY_FOR_QUALIFICATION) — waiting + retrying items, with pagination + reorder controls. Items show type label (Email/Issue/Wiki/Git) instead of "Čeká"
3. **Hotovo** (DISPATCHED_GPU) — completed tasks from `TaskDocument` collection

**Live Qualification Progress with Audit Trail:**
- `MainViewModel.qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>>` — per-task progress from events
- `QualificationProgress` events broadcast from `TaskQualificationService` via `NotificationRpcImpl`
- Events carry `metadata: Map<String, String>` with structured data for UI display
- Progress steps: start → ingest → analysis → routing/simple_action → done
- **Analysis step metadata**: chunksCount, nodesCreated, entities, actionable, urgency, suggestedActions, isAssignedToMe, hasFutureDeadline, suggestedDeadline, summary
- **Routing step metadata**: route, targetState
- **Simple action step metadata**: actionType
- `ProgressStepRow` displays metadata as compact key-value rows (`MetadataRow` composable)
- Item icon turns tertiary color when actively processing

**Hierarchy: Connection → Capability → Client** (in Sources section)

Three-level expandable tree inside each connection card:
1. **ConnectionGroupCard** -- connection name, provider icon, total item count
2. **CapabilityGroupSection** -- capability label+icon, item count, next check time (clickable → `PollingIntervalDialog`), "Spustit teď" button (triggers source polling)
3. **ClientGroupSection** -- client name, item count, expandable list of `QueueItemRow`

**Key components:**
- `IndexingSectionHeader` -- accordion header with arrow icon, title, badge count
- `ConnectionGroupCard` -- expandable `JCard` with 3-level hierarchy (connection → capability → client)
- `CapabilityGroupSection` -- capability header with next-check time, PlayArrow "Spustit teď" button
- `ClientGroupSection` -- client name header with expandable item list
- `QueueItemRow` -- row with type icon, title, sourceUrn badge, state
- `KbProcessingSectionContent` -- items with live progress overlay from `QualificationProgressInfo`
- `PipelineItemWithProgress` -- row with optional live progress message (tertiary color)
- `PipelineSectionContent` -- simple list of `PipelineItemCompactRow` (used for Hotovo)
- `PipelineSection` -- section with optional pagination and reorder controls (used for KB fronta)
- `PollingIntervalDialog` -- `JFormDialog` to change polling interval per capability
- `IndexingItemType` enum with `.icon()` / `.label()` helpers

**Reorder controls** (on "KB fronta" items):
- Up/Down arrows (KeyboardArrowUp/Down) for position adjustment
- Prioritize button (VerticalAlignTop) moves item to position 1
- Process Now button (PlayArrow) triggers immediate processing
- Calls `reorderKbQueueItem(taskId, newPosition)` or `prioritizeKbQueueItem(taskId)` or `processKbItemNow(taskId)` RPC

**Pipeline state labels (Czech):**
- WAITING → "Ceka", QUALIFYING → "Indexuje", RETRYING → "Opakuje"
- Step labels (live): ingest → "Indexuje", summary → "Analyzuje", routing → "Rozhoduje", user_task → "Úkol", scheduled → "Naplánováno"

**Data:**
- `IndexingDashboardDto` with `connectionGroups`, `kbWaiting`, `kbProcessing`, `kbIndexed` (each with counts + totals), `kbPage`, `kbPageSize`
- `ConnectionIndexingGroupDto` with `connectionId`, `connectionName`, `provider`, `lastPolledAt?`, `capabilityGroups: List<CapabilityGroupDto>`, `totalItemCount`
- `CapabilityGroupDto` with `capability`, `nextCheckAt?`, `intervalMinutes`, `clients: List<ClientItemGroupDto>`, `totalItemCount`
- `ClientItemGroupDto` with `clientId`, `clientName`, `items: List<IndexingQueueItemDto>`, `totalItemCount`
- `PipelineItemDto` with `id`, `type`, `title`, `connectionName`, `clientName`, `sourceUrn?`, `pipelineState`, `retryCount`, `nextRetryAt?`, `errorMessage?`, `createdAt?`, `taskId?`, `queuePosition?`

**RPC:** `IIndexingQueueService.getIndexingDashboard(search, kbPage, kbPageSize)` -- single call returns hierarchy + all pipeline stages
Additional RPCs: `triggerIndexNow(connectionId, capability)`, `reorderKbQueueItem(taskId, newPosition)`, `prioritizeKbQueueItem(taskId)`, `processKbItemNow(taskId)`

---

## 6) Expandable / Collapsible Sections

For complex nested content (e.g., connection capabilities per connection), use an expandable card pattern:

```kotlin
var expanded by remember { mutableStateOf(false) }

JCard(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
) {
    Column(Modifier.padding(12.dp)) {
        // Header row -- always visible, clickable to toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                              else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        }

        // Expanded content
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // ... nested content ...
        }
    }
}
```

### 5.10) Environment Manager Screen (`EnvironmentManagerScreen.kt`)

Full environment management screen accessed from the hamburger menu ("Správa prostředí").
Uses `JListDetailLayout` for list→detail navigation with `TabRow` for detail tabs.

```
Expanded (>=600dp):
+-- JTopBar ("Správa prostředí") -------+
|                                        |
| +-- List ----+  +-- Detail ----------+|
| | [Nové prostředí]  | JDetailScreen    ||
| |             |  | TabRow:             ||
| | JCard       |  | Přehled|Komponenty| ||
| |  name       |  | K8s|Logy           ||
| |  namespace  |  |                     ||
| |  ● Běží     |  | (tab content)      ||
| |             |  |                     ||
| +-------------+  +--------------------+|
+----------------------------------------+
```

**Tabs (EnvironmentManagerTab enum):**
- Přehled — name, namespace, state badge, assignment, component summary, actions (Provision/Stop/Delete)
- Komponenty — expandable JCards with inline editing (ComponentsTab + ComponentEditPanel)
- K8s zdroje — pod/deployment/service inspection (migrated from EnvironmentViewerScreen)
- Logy & Události — pod logs + K8s events

**Key components:**
- `EnvironmentManagerScreen` — `JListDetailLayout` with list header ("Nové prostředí" button)
- `EnvironmentListItem` — `JCard` with name, namespace (monospace), component count, `EnvironmentStateBadge`
- `EnvironmentDetail` — `JDetailScreen` + `TabRow` + tab content dispatch
- `OverviewTab` — `JSection` blocks (basic info, assignment, components summary), action buttons
- `ComponentsTab` — expandable JCards per component (collapsed: type + name + summary; expanded: read-only detail or inline editor)
- `ComponentEditPanel` — inline editor for EnvironmentComponentDto: name, type, image, ports list, ENV vars, resource limits, health check, startup config
- `K8sResourcesTab` — namespace health summary, collapsible pods/deployments/services sections, pod log dialog, deployment detail dialog, restart
- `LogsEventsTab` — pod log viewer (dropdown pod selector, tail lines, monospace text area) + K8s events list (Warning/Normal coloring)

**Navigation:**
- `Screen.EnvironmentManager(initialEnvironmentId: String? = null)` — supports deep-link from Settings
- Menu item: "Správa prostředí" (Icons.Default.Dns)
- Reuses `NewEnvironmentDialog` from `EnvironmentDialogs.kt`
- Reuses `EnvironmentStateBadge` from `EnvironmentTreeComponents.kt`

---

## 7) Dialog Patterns

### 7.1) Selection Dialog (e.g., "Vybrat pripojeni")

```kotlin
AlertDialog(
    onDismissRequest = { showDialog = false },
    title = { Text("Vybrat pripojeni") },
    text = {
        LazyColumn {
            items(availableItems.filter { it.id !in selectedIds }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item); showDialog = false }
                        .padding(12.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) { /* item content */ }
                HorizontalDivider()
            }
        }
    },
    confirmButton = { JTextButton(onClick = { showDialog = false }) { Text("Zavrit") } },
)
```

### 7.2) Multi-Select Dialog (e.g., "Pridat zdroje")

```kotlin
AlertDialog(
    text = {
        Column {
            JTextField(value = filter, onValueChange = { filter = it }, label = "Filtrovat...")
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(filtered) { resource ->
                    Row(modifier = Modifier.clickable { toggle(resource) }.heightIn(min = JervisSpacing.touchTarget)) {
                        Checkbox(checked = resource in selected, ...)
                        Column { /* name, description */ }
                    }
                }
            }
        }
    },
    confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JTextButton(onClick = onDismiss) { Text("Zavrit") }
            if (selected.isNotEmpty()) {
                JPrimaryButton(onClick = { confirm(); onDismiss() }) { Text("Pridat vybrane (${selected.size})") }
            }
        }
    },
)
```

### 7.3) Create Dialog (e.g., "Vytvorit novy projekt")

Use `JFormDialog` for form-based creation:

```kotlin
JFormDialog(
    visible = showCreate,
    title = "Vytvorit novy projekt",
    onConfirm = { create() },
    onDismiss = { showCreate = false },
    confirmEnabled = name.isNotBlank(),
    confirmText = "Vytvorit",
) {
    JTextField(value = name, onValueChange = { name = it }, label = "Nazev")
    Spacer(Modifier.height(JervisSpacing.fieldGap))
    JTextField(value = desc, onValueChange = { desc = it }, label = "Popis (volitelne)", minLines = 2)
}
```

### 7.4) Delete Confirmation

Always use `ConfirmDialog` (from `com.jervis.ui.util.ConfirmDialog`):

```kotlin
JConfirmDialog(
    visible = showDelete,
    title = "Smazat pripojeni",
    message = "Opravdu chcete smazat \"${item.name}\"? Tuto akci nelze vratit.",
    confirmText = "Smazat",
    onConfirm = { showDelete = false; handleDelete() },
    onDismiss = { showDelete = false },
    isDestructive = true,
)
```

### 7.5) Remove-Action Confirmation (Inline Items)

Use `JRemoveIconButton` for inline remove buttons (X icon on list items). It encapsulates
the confirm dialog — no extra state variables needed:

```kotlin
JRemoveIconButton(
    onConfirmed = { removeItem(item) },
    title = "Odebrat zdroj?",
    message = "Zdroj \"${item.displayName}\" bude odebran z projektu.",
)
```

All remove actions across settings screens (resource, link, component, connection removal) use this component.

---

## 8) Typography & Color Conventions

| Context                | Style                              | Color                                     |
|------------------------|------------------------------------|-------------------------------------------|
| Card title             | `titleMedium`                      | default (onSurface)                       |
| Card subtitle / ID     | `bodySmall`                        | `onSurfaceVariant`                        |
| Section title          | `titleMedium` (via JSection)       | `primary`                                 |
| Capability group label | `labelMedium`                      | `primary`                                 |
| Help text / hint       | `bodySmall`                        | `onSurfaceVariant`                        |
| Error text             | `bodySmall`                        | `error`                                   |
| Chip / badge           | `labelSmall`                       | via `SuggestionChip`                      |
| Status indicator       | `labelMedium`                      | green / yellow / red (via `JStatusBadge`) |

### Button Colors

| Button type  | Component |
|-------------|-----------|
| Primary action | `JPrimaryButton` (primary container) |
| Secondary / outlined | `JSecondaryButton` (outlined button) |
| Text-only / cancel | `JTextButton` |
| Destructive | `JDestructiveButton` (error-colored) |
| Icon action | `JIconButton` / `JRefreshButton` / `JDeleteButton` / `JEditButton` / `JAddButton` / `JRemoveIconButton` |

---

## 9) Migration Rules & Checklist

When adding or modifying a settings screen:

1. **Does it need category navigation?** -> Use `JAdaptiveSidebarLayout`
2. **Does it list entities with CRUD?** -> Use `JListDetailLayout` + `JDetailScreen`
3. **Is it a simple flat list?** -> `LazyColumn` + `JActionBar` + state components
4. **Cards** -> Always `JCard` (never raw `Card` with manual `outlinedCardBorder()`)
5. **Touch targets** -> All rows/buttons >= 44 dp (`JervisSpacing.touchTarget`)
6. **Loading/Empty/Error** -> Use `JCenteredLoading` / `JEmptyState` / `JErrorState`
7. **Git config** -> Use shared `GitCommitConfigFields()` from ClientsSharedHelpers
8. **Capability labels** -> Use shared `getCapabilityLabel()` / `getIndexAllLabel()`
9. **Back navigation** -> `JTopBar(onBack = ...)` or `JDetailScreen(onBack = ...)`
10. **Forms** -> `JTextField` with label parameter (never raw `OutlinedTextField`)
11. **Dropdowns** -> `JDropdown` (never raw `ExposedDropdownMenuBox`)
12. **Switches** -> `JSwitch` with label/description
13. **Confirm destructive actions** -> `JConfirmDialog` (or `ConfirmDialog` from util/)
14. **Refresh data** -> `JRefreshButton` in `JActionBar`
15. **Delete actions** -> `JDeleteButton` (error-tinted, 44dp)
16. **Create dialogs** -> `JFormDialog` with `JTextField` fields
17. **Status display** -> `JStatusBadge` (green/yellow/red dot with label)
18. **Key-value display** -> `JKeyValueRow` (primary-colored label)
19. **Theme** -> All screens wrapped in `JervisTheme` (auto light/dark)

### Forbidden Patterns

| Don't | Do instead |
|-------|-----------|
| `Card(elevation = ..., colors = surfaceVariant)` | `JCard()` |
| `Card(border = CardDefaults.outlinedCardBorder())` | `JCard()` |
| `Box { CircularProgressIndicator() }` centered | `JCenteredLoading()` |
| `OutlinedTextField(...)` directly | `JTextField(...)` |
| `ExposedDropdownMenuBox` directly | `JDropdown(...)` |
| `Button(colors = errorColors)` for delete | `JDestructiveButton(...)` or `JDeleteButton(...)` |
| `IconButton` without explicit size | `JIconButton(icon = ..., onClick = ...)` |
| Inline save/cancel below form | `JDetailScreen(onSave = ..., onBack = ...)` |
| Fixed sidebar width without adaptive | `JAdaptiveSidebarLayout` |
| `Row` of buttons without alignment | `JActionBar { ... }` or `Row(Arrangement.spacedBy(8.dp, Alignment.End))` |
| Duplicating `getCapabilityLabel()` | Import from `ClientsSharedHelpers.kt` (internal) |
| `TopAppBar` directly | `JTopBar(title, onBack, actions)` |
| Emoji strings for icons ("...") | Material `ImageVector` icons (`Icons.Default.*`) |
| `StatusIndicator` (deleted) | `JStatusBadge(status)` |
| `SettingCard` (deleted) | `JCard()` |
| `ActionRibbon` (deleted) | `JDetailScreen` (provides save/cancel) |
| `TextButton("< Zpet")` for nav | `JTopBar(onBack = ...)` with ArrowBack icon |

---

## 10) File Structure Reference

```
shared/ui-common/src/commonMain/kotlin/com/jervis/ui/
+-- design/                            <- Design system (split by component category)
|   +-- DesignTheme.kt                <- JervisTheme, JervisSpacing, COMPACT_BREAKPOINT_DP
|   +-- DesignState.kt                <- JCenteredLoading, JErrorState, JEmptyState
|   +-- DesignLayout.kt               <- JTopBar, JSection, JActionBar, JAdaptiveSidebarLayout, JListDetailLayout, JDetailScreen, JNavigationRow, JVerticalSplitLayout, JHorizontalSplitLayout
|   +-- DesignButtons.kt              <- JPrimaryButton, JSecondaryButton, JTextButton, JDestructiveButton, JRunTextButton, JIconButton, JRefreshButton, JDeleteButton, JEditButton, JAddButton, JRemoveIconButton
|   +-- DesignCards.kt                <- JCard, JListItemCard, JTableHeaderRow, JTableHeaderCell, JTableRowCard
|   +-- DesignForms.kt                <- JTextField, JDropdown, JSwitch, JSlider, JCheckboxRow
|   +-- DesignDialogs.kt              <- JConfirmDialog, JFormDialog, JSelectionDialog
|   +-- DesignDataDisplay.kt          <- JKeyValueRow, JStatusBadge, JCodeBlock, JSnackbarHost
|   +-- JervisColors.kt               <- Semantic colors: success, warning, info + light/dark schemes
|   +-- JervisTypography.kt           <- Responsive typography definitions
|   +-- JervisShapes.kt               <- Centralized shape definitions
|   +-- ComponentImportance.kt        <- ESSENTIAL/IMPORTANT/DETAIL enum + JImportance composable
|   +-- JervisBreakpoints.kt          <- WATCH_DP/COMPACT_DP/MEDIUM_DP/EXPANDED_DP + WindowSizeClass + rememberWindowSizeClass()
+-- navigation/
|   +-- AppNavigator.kt               <- Screen sealed class + navigator
+-- meeting/                           <- Meeting feature package
|   +-- MeetingsScreen.kt             <- Public entry point (list + routing)
|   +-- MeetingListItems.kt           <- MeetingListItem, DeletedMeetingListItem (internal)
|   +-- MeetingDetailView.kt          <- Detail view with split layout
|   +-- TranscriptPanel.kt            <- Transcript segments display
|   +-- AgentChatPanel.kt             <- Correction agent chat
|   +-- PipelineProgress.kt           <- Pipeline state display
|   +-- CorrectionQuestionsCard.kt    <- Correction review questions
|   +-- SegmentCorrectionDialog.kt    <- Segment editing dialog
|   +-- MeetingHelpers.kt             <- formatDateTime, stateIcon, stateLabel helpers (internal)
|   +-- MeetingViewModel.kt           <- Meeting state management
|   +-- CorrectionsScreen.kt          <- KB correction rules CRUD
|   +-- CorrectionViewModel.kt        <- Corrections state
|   +-- RecordingSetupDialog.kt       <- Audio device + client/project selection
|   +-- RecordingIndicator.kt         <- Animated recording indicator
+-- screens/
|   +-- settings/
|   |   +-- SettingsScreen.kt         <- JAdaptiveSidebarLayout + categories
|   |   +-- sections/
|   |       +-- ClientsSettings.kt    <- Client list + expandable cards
|   |       +-- ClientEditForm.kt     <- Client edit form (internal)
|   |       +-- ClientEditSections.kt <- ClientConnectionsSection, ClientProjectsSection (internal)
|   |       +-- CapabilityConfiguration.kt <- ConnectionCapabilityCard (internal)
|   |       +-- ProviderResources.kt  <- ProviderResourcesCard (internal)
|   |       +-- ClientsSharedHelpers.kt <- getCapabilityLabel, getIndexAllLabel, GitCommitConfigFields (internal)
|   |       +-- ProjectsSettings.kt   <- Project list
|   |       +-- ProjectEditForm.kt    <- Project edit form (internal)
|   |       +-- ProjectResourceDialogs.kt <- Project resource selection dialogs (internal)
|   |       +-- ProjectGroupsSettings.kt <- Group list
|   |       +-- ProjectGroupEditForm.kt  <- Group edit form (internal)
|   |       +-- ProjectGroupDialogs.kt   <- Group create dialog (internal)
|   |       +-- EnvironmentsSettings.kt  <- Environment list + read-only summary + cross-link to Environment Manager
|   |       +-- EnvironmentDialogs.kt    <- NewEnvironmentDialog, AddComponentDialog, componentTypeLabel()
|   |       +-- ConnectionsSettings.kt <- Connection list + per-card actions
|   |       +-- ConnectionDialogs.kt    <- Connection create/edit dialogs (internal)
|   |       +-- ConnectionFormComponents.kt <- Connection form fields (internal)
|   |       +-- CodingAgentsSettings.kt <- Coding agent config
|   |       +-- IndexingSettings.kt     <- Indexing intervals config
|   |       +-- WhisperSettings.kt      <- Whisper transcription config
|   +-- environment/
|   |   +-- EnvironmentManagerScreen.kt  <- JListDetailLayout + tabbed detail (Správa prostředí)
|   |   +-- EnvironmentManagerTabs.kt    <- EnvironmentManagerTab enum (OVERVIEW, COMPONENTS, K8S_RESOURCES, LOGS_EVENTS)
|   |   +-- OverviewTab.kt              <- Overview tab: info sections + action buttons
|   |   +-- ComponentsTab.kt            <- Components tab: expandable JCards with inline editing
|   |   +-- ComponentEditPanel.kt       <- Inline component editor (name, type, image, ports, ENV, limits, health, startup)
|   |   +-- K8sResourcesTab.kt          <- K8s resources tab: pods, deployments, services (migrated from EnvironmentViewerScreen)
|   |   +-- LogsEventsTab.kt            <- Logs & Events tab: pod log viewer + K8s namespace events
|   +-- IndexingQueueScreen.kt        <- Indexing queue dashboard (4 accordion sections + live qualification progress)
|   +-- IndexingQueueSections.kt      <- ConnectionGroupCard, CapabilityGroupSection, PipelineSection, PollingIntervalDialog (internal)
|   +-- ConnectionsScreen.kt          <- Placeholder (desktop has full UI)
+-- MainScreen.kt                      <- Chat content (no selectors — moved to PersistentTopBar)
+-- PersistentTopBar.kt               <- Global top bar: back, menu, client/project, recording, agent, K8s, connection
+-- MainViewModel.kt                   <- Main ViewModel (user actions, state)
+-- ChatMessageDisplay.kt             <- Chat messages, workflow steps display
+-- AgentStatusRow.kt                 <- Agent status indicator (legacy, replaced by PersistentTopBar icon)
+-- ChatInputArea.kt                  <- Message input + send button
+-- AgentWorkloadScreen.kt            <- Agent workload accordion layout
+-- AgentWorkloadSections.kt          <- Agent/queue/history sections (internal)
+-- SchedulerScreen.kt                <- Task scheduling list
+-- SchedulerComponents.kt            <- ScheduledTaskDetail, ScheduleTaskDialog (internal)
+-- RagSearchScreen.kt                <- RAG search interface
+-- UserTasksScreen.kt                <- Escalated task list + detail
+-- PendingTasksScreen.kt             <- Task queue with filters
+-- ErrorLogsScreen.kt                <- Error logs display
+-- model/
|   +-- AgentActivityEntry.kt         <- Data models for agent activity
+-- viewmodels/
|   +-- MainViewModel.kt              <- ViewModel wiring
+-- audio/
|   +-- AudioPlayer.kt                <- expect class AudioPlayer
|   +-- AudioRecorder.kt              <- expect class AudioRecorder
|   +-- PlatformRecordingService.kt   <- Recording service bridge
|   +-- RecordingServiceBridge.kt
+-- notification/
|   +-- ApprovalNotificationDialog.kt <- Orchestrator approval dialog
|   +-- NotificationActionChannel.kt
|   +-- PlatformNotificationManager.kt
+-- storage/
|   +-- PendingMessageStorage.kt
|   +-- RecordingStateStorage.kt
+-- util/
|   +-- IconButtons.kt                <- RefreshIconButton, DeleteIconButton, EditIconButton
|   +-- ConfirmDialog.kt              <- ConfirmDialog (Czech defaults, keyboard support)
|   +-- CopyableTextCard.kt           <- CopyableTextCard (SelectionContainer + outlinedCardBorder)
|   +-- BrowserHelper.kt              <- expect fun openUrlInBrowser
|   +-- FilePickers.kt                <- expect fun pickTextFileContent
+-- App.kt                            <- Root composable (navigation routing, global dialogs)
+-- JervisApp.kt                      <- App-level setup
```

### 10.1) File Organization Rules

See `docs/guidelines.md` § "UI File Organization" for the complete reference.

**Summary:**
- One feature = one package under `screens/`
- Three-level decomposition: Screen → Content → Sections
- File max ~300 lines, must split at 500+
- Only Screen composable touches ViewModel
- `internal` for feature-scoped, `private` for file-local, `public` for entry points + design system

## 11) Cloud Model Policy Settings

Cloud model auto-escalation toggles in client/project edit forms.

### Client level (defaults)

In `ClientEditForm` (`ClientEditForm.kt`), section "Cloud modely":

```kotlin
JSection(title = "Cloud modely") {
    Text("Automatická eskalace na cloud modely při selhání lokálního modelu.")
    JCheckboxRow(label = "Anthropic (Claude) – reasoning, analýza", checked/onChange)
    JCheckboxRow(label = "OpenAI (GPT-4o) – editace kódu", checked/onChange)
    JCheckboxRow(label = "Google Gemini – extrémní kontext (>49k)", checked/onChange)
}
```

DTO fields: `autoUseAnthropic: Boolean`, `autoUseOpenai: Boolean`, `autoUseGemini: Boolean`

### Project level (override)

In `ProjectEditForm` (`ProjectEditForm.kt`), section "Cloud modely – přepsání":

```kotlin
JSection(title = "Cloud modely – přepsání") {
    Text("Standardně se používá konfigurace z klienta.")
    JCheckboxRow(label = "Přepsat konfiguraci klienta", checked = overrideCloudPolicy)
    if (overrideCloudPolicy) {
        // Same 3 checkboxes as client form
    }
}
```

DTO fields: `autoUseAnthropic: Boolean?`, `autoUseOpenai: Boolean?`, `autoUseGemini: Boolean?`
(null = inherit from client)

When "Přepsat konfiguraci klienta" unchecked → all nulls sent → entity stores `cloudModelPolicy = null`.

**Deleted files** (no longer exist):
- `components/SettingComponents.kt` -- SettingCard, StatusIndicator, ActionRibbon replaced by JCard, JStatusBadge, JDetailScreen
- `screens/settings/sections/BugTrackerSettings.kt` -- dead code, removed
- `screens/settings/sections/GitSettings.kt` -- dead code, removed
- `screens/settings/sections/LogsSettings.kt` -- dead code, replaced by ErrorLogsScreen
- `screens/settings/sections/SchedulerSettings.kt` -- dead code, replaced by SchedulerScreen
