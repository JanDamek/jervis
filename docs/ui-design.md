# Jervis – UI Design System (Compose Multiplatform) – SSOT

**Last updated:** 2026-02-07
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

## 1) Adaptive Layout Architecture

### 1.1) Breakpoint

```
COMPACT_BREAKPOINT_DP = 600
```

| Width         | Mode       | Devices                        |
|---------------|------------|--------------------------------|
| < 600 dp      | **Compact**  | iPhone, Android phone          |
| ≥ 600 dp      | **Expanded** | iPad, Android tablet, Desktop  |

Detection uses `BoxWithConstraints` inside the layout composables. **Never add platform
expect/actual for layout decisions** – width-based detection works everywhere.

### 1.2) Navigation Patterns by Mode

| Mode       | Category nav                          | Entity list → detail            |
|------------|---------------------------------------|---------------------------------|
| Compact    | Full-screen list; tap → full-screen section | List replaces with full-screen detail form |
| Expanded   | 240 dp sidebar + content side-by-side   | Same (list replaces with detail form)       |

On compact a JTopBar with back arrow is **always** visible at the top so the user can go back.
On expanded the sidebar has a "Zpět" text button and the content area has a heading.

### 1.3) Decision Tree – Which Layout Composable to Use

```
Need category-based navigation (settings, admin panels)?
  → JAdaptiveSidebarLayout

Need entity list with create/edit/detail (clients, projects)?
  → JListDetailLayout + JDetailScreen for the edit form

Need a simple scrollable form (general settings)?
  → Column with verticalScroll inside a JSection

Need a flat list with per-row actions (connections, logs)?
  → LazyColumn with Card items + JActionBar at top
```

---

## 2) Design Principles

### 2.1) Core Rules

| Rule                          | Details                                                                       |
|-------------------------------|-------------------------------------------------------------------------------|
| **Consistency**               | Use shared components from `com.jervis.ui.design`, don't invent new wrappers |
| **Fail-fast in UI**           | Show errors via `JErrorState` with retry, never silently hide                 |
| **Unified screen states**     | Every data-loading screen uses `JCenteredLoading` / `JErrorState` / `JEmptyState` |
| **Touch targets ≥ 44 dp**    | `JervisSpacing.touchTarget` – all clickable rows, icon buttons, checkboxes    |
| **No fixed widths**           | Use `fillMaxWidth()`, `weight()`, scrolling. The only fixed width is the sidebar (240 dp on expanded) |
| **Czech UI labels**           | All user-facing text in Czech, code/comments/logs in English                 |
| **No secrets masking**        | Passwords, tokens, keys always visible (private app)                         |
| **No over-engineering**       | Solve the current screen, don't generalize prematurely                       |

### 2.2) Card Style

All list items, resource rows, log entries, connection cards use:

```kotlin
Card(
    modifier = Modifier.fillMaxWidth(),
    border = CardDefaults.outlinedCardBorder(),
)
```

**Never** use `elevation`, `surfaceVariant`, or custom borders for list items.
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

// IconButton – size explicitly set
IconButton(
    onClick = { ... },
    modifier = Modifier.size(JervisSpacing.touchTarget),
)

// Checkbox/RadioButton rows
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
)
```

### 2.4) Action Buttons Placement

```
Top of a list screen    → JActionBar with refresh + add button
Detail form bottom      → JDetailScreen provides save/cancel automatically
Inline per-card actions → Row with Arrangement.spacedBy(8.dp, Alignment.End)
Delete with confirm     → ConfirmDialog triggered by DeleteIconButton
```

---

## 3) Shared Components Reference

All components live in `com.jervis.ui.design.DesignSystem.kt` unless noted otherwise.

### 3.1) Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTopBar` | Navigation bar at top of screen | `title`, `onBack?`, `actions` |
| `JSection` | Visual grouping with title and padding | `title`, `content` |
| `JActionBar` | Right-aligned action buttons bar | `modifier`, `content: RowScope` |

### 3.2) State Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JCenteredLoading` | Centered circular progress | – |
| `JErrorState` | Error message + retry button | `message`, `onRetry?` |
| `JEmptyState` | Empty data state with icon | `message`, `icon` |

### 3.3) Adaptive Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JAdaptiveSidebarLayout<T>` | Sidebar (expanded) / category list (compact) | `categories`, `selectedIndex`, `onSelect`, `onBack`, `title`, `categoryIcon`, `categoryTitle`, `categoryDescription`, `content` |
| `JListDetailLayout<T>` | List with detail navigation | `items`, `selectedItem`, `isLoading`, `onItemSelected`, `emptyMessage`, `emptyIcon`, `listHeader`, `listItem`, `detailContent` |
| `JDetailScreen` | Full-screen edit form with back + save/cancel | `title`, `onBack`, `onSave?`, `saveEnabled`, `actions`, `content: ColumnScope` |
| `JNavigationRow` | Touch-friendly nav row (compact mode) | `icon`, `title`, `subtitle?`, `onClick`, `trailing` |

### 3.4) Data Display Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTableHeaderRow` | Table header row | `content` |
| `JTableHeaderCell` | Single header cell | `text`, `weight` |
| `JTableRowCard` | Selectable row card | `selected`, `onClick`, `content` |
| `JPrimaryButton` | Primary-color Material3 button | `onClick`, `enabled`, `content` |

### 3.5) Utility Components (`com.jervis.ui.util`)

| Component | Purpose |
|-----------|---------|
| `RefreshIconButton` | Refresh action (emoji "🔄") |
| `DeleteIconButton` | Delete action (emoji "🗑️") |
| `EditIconButton` | Edit action (emoji "✏️") |
| `ConfirmDialog` | Confirmation dialog with keyboard support |
| `CopyableTextCard` | Text card with click-to-copy |

### 3.6) Setting Components (`com.jervis.ui.components`)

| Component | Purpose |
|-----------|---------|
| `SettingCard` | Card for setting groups (used in BugTrackerSettings) |
| `StatusIndicator` | Connection status dot (green/yellow/red) |
| `ActionRibbon` | Save/Cancel ribbon (legacy – prefer `JDetailScreen`) |
| `AgentStatusRow` | Clickable agent status bar in MainScreen (idle/running + queue badge + chevron) |

### 3.7) Shared Form Helpers (`com.jervis.ui.screens.settings.sections.ClientsSettings.kt`)

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
    val outerPadding = 10.dp   // Outer margin around screens
    val sectionPadding = 12.dp // Inner padding of JSection
    val itemGap = 8.dp         // Gap between list items
    val touchTarget = 44.dp    // Minimum touch target size
}

const val COMPACT_BREAKPOINT_DP = 600
```

### Usage Guidelines

| Context | Spacing |
|---------|---------|
| Between sections in a form | `Arrangement.spacedBy(16.dp)` |
| Between items in a LazyColumn | `Arrangement.spacedBy(JervisSpacing.itemGap)` |
| JSection internal spacing | `JervisSpacing.sectionPadding` (automatic) |
| Screen outer padding | `JervisSpacing.outerPadding` (automatic in JDetailScreen/JAdaptiveSidebarLayout) |
| Between form fields in a section | `Spacer(Modifier.height(JervisSpacing.itemGap))` |
| Between label and field group | `Spacer(Modifier.height(12.dp))` |

---

## 5) Screen Anatomy Patterns

### 5.1) Category-Based Settings Screen

```
┌─────────────────────────────────────────────┐
│ JAdaptiveSidebarLayout                      │
│                                             │
│ EXPANDED (≥600dp):                          │
│ ┌──────────┬────────────────────────────┐   │
│ │ Sidebar  │ Content                    │   │
│ │ 240dp    │ (remaining width)          │   │
│ │          │                            │   │
│ │ [← Zpět] │ Category Title (h2)       │   │
│ │          │ Category description       │   │
│ │ ● Cat 1  │                            │   │
│ │   Cat 2  │ ┌─ JSection ────────────┐  │   │
│ │   Cat 3  │ │ Section content...    │  │   │
│ │   Cat 4  │ └───────────────────────┘  │   │
│ │   Cat 5  │                            │   │
│ └──────────┴────────────────────────────┘   │
│                                             │
│ COMPACT (<600dp):                           │
│ ┌───────────────────────────────────────┐   │
│ │ JTopBar: "Nastavení" [← back]        │   │
│ │                                       │   │
│ │ ┌─ JNavigationRow ─────────────────┐  │   │
│ │ │ ⚙️ Obecné                    [>] │  │   │
│ │ │    Základní nastavení aplikace   │  │   │
│ │ └──────────────────────────────────┘  │   │
│ │ ┌─ JNavigationRow ─────────────────┐  │   │
│ │ │ 🏢 Klienti a projekty         [>] │  │   │
│ │ │    Správa klientů, projektů ...  │  │   │
│ │ └──────────────────────────────────┘  │   │
│ │ ...                                   │   │
│ └───────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

**Implementation:**

```kotlin
enum class SettingsCategory(
    val title: String,
    val icon: String,
    val description: String,
) {
    GENERAL("Obecné", "⚙️", "Základní nastavení aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", "🏢", "Správa klientů, projektů a jejich konfigurace."),
    PROJECT_GROUPS("Skupiny projektů", "📂", "Logické seskupení projektů se sdílenou KB."),
    CONNECTIONS("Připojení", "🔌", "Technické parametry připojení."),
    CODING_AGENTS("Coding Agenti", "🤖", "Konfigurace coding agentů."),
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
        title = "Nastavení",
        categoryIcon = { it.icon },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category -> SettingsContent(category, repository) },
    )
}
```

### 5.2) Entity List → Detail Screen

```
LIST VIEW:
┌───────────────────────────────┐
│ JActionBar: [🔄] [+ Přidat]  │
│                               │
│ ┌─ Card (outlinedBorder) ──┐  │
│ │ Entity Name          [>] │  │
│ │ subtitle / metadata      │  │
│ └──────────────────────────┘  │
│ ┌─ Card ───────────────────┐  │
│ │ Entity Name 2        [>] │  │
│ │ subtitle / metadata      │  │
│ └──────────────────────────┘  │
│ ...                           │
└───────────────────────────────┘

DETAIL VIEW (replaces list):
┌───────────────────────────────┐
│ JTopBar: "Entity Name" [← ←] │
│                               │
│ ┌─ JSection: Základní ─────┐  │
│ │ [OutlinedTextField: Název]│  │
│ │ [OutlinedTextField: Popis]│  │
│ └───────────────────────────┘  │
│ ┌─ JSection: Připojení ────┐  │
│ │ Connection cards...       │  │
│ └───────────────────────────┘  │
│ ...                           │
│                               │
│ ┌─ JActionBar ──────────────┐ │
│ │          [Zrušit] [Uložit]│ │
│ └───────────────────────────┘ │
└───────────────────────────────┘
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
        emptyMessage = "Žádní klienti nenalezeni",
        emptyIcon = "🏢",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadClients() })
                JPrimaryButton(onClick = { /* new */ }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat klienta")
                }
            }
        },
        listItem = { client ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { selectedClient = client },
                border = CardDefaults.outlinedCardBorder(),
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JSection(title = "Základní údaje") {
                OutlinedTextField(value = name, onValueChange = { name = it }, ...)
            }
            JSection(title = "Připojení klienta") { ... }
            JSection(title = "Git Commit Konfigurace") {
                GitCommitConfigFields(...)  // Shared helper
            }
            Spacer(Modifier.height(16.dp))  // Bottom breathing room
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
            Text("Přidat připojení")
        }
    }

    Spacer(Modifier.height(JervisSpacing.itemGap))

    if (isLoading && items.isEmpty()) {
        JCenteredLoading()
    } else if (items.isEmpty()) {
        JEmptyState(message = "Žádná připojení nenalezena", icon = "🔌")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(connections) { connection ->
                Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder()) {
                    Column(Modifier.padding(16.dp)) {
                        // ... content ...
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            JPrimaryButton(onClick = { ... }) { Text("Test") }
                            JPrimaryButton(onClick = { ... }) { Icon(Icons.Default.Edit, ...) }
                            Button(onClick = { ... }, colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )) { Icon(Icons.Default.Delete, ...) }
                        }
                    }
                }
            }
        }
    }
}
```

### 5.5) Agent Workload Screen (`AgentWorkloadScreen.kt`)

Full-screen view accessed by clicking the `AgentStatusRow` on the main screen.
Shows live agent status card + in-memory activity log (max 200 entries, since restart, no persistence).

```
┌─ JTopBar ("Aktivita agenta", onBack) ─────────────────┐
│                                                         │
│ ┌─ CurrentStatusCard ─────────────────────────────────┐ │
│ │ [spinner/dot]  Zpracovává se / Agent je nečinný     │ │
│ │                Chat | ProjectName                    │ │
│ │                task preview text...        Fronta: 1 │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ Historie aktivity                                       │
│ ┌─ LazyColumn (newest first) ────────────────────────┐  │
│ │ 14:23:05  ▶  Chat  ProjectX   Zpracování úlohy    │  │
│ │ 14:22:58  ✓  Wiki  ProjectY   Úloha dokončena     │  │
│ │ 14:20:11  ▶  Wiki  ProjectY   Wiki indexing...     │  │
│ └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Data model** (`com.jervis.ui.model.AgentActivityEntry`):
- `id`, `time` (HH:mm:ss), `type` (TASK_STARTED/TASK_COMPLETED/AGENT_IDLE/QUEUE_CHANGED)
- `description`, `projectName?`, `taskType?`, `clientId?`
- Stored in `AgentActivityLog` ring buffer (max 200), held by `MainViewModel`

---

## 6) Expandable / Collapsible Sections

For complex nested content (e.g., connection capabilities per connection), use an expandable card pattern:

```kotlin
var expanded by remember { mutableStateOf(false) }

Card(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    border = CardDefaults.outlinedCardBorder(),
) {
    Column(Modifier.padding(12.dp)) {
        // Header row – always visible, clickable to toggle
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

---

## 7) Dialog Patterns

### 7.1) Selection Dialog (e.g., "Vybrat připojení")

```kotlin
AlertDialog(
    onDismissRequest = { showDialog = false },
    title = { Text("Vybrat připojení") },
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
    confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Zavřít") } },
)
```

### 7.2) Multi-Select Dialog (e.g., "Přidat zdroje")

```kotlin
AlertDialog(
    text = {
        Column {
            OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Filtrovat...") })
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
            TextButton(onClick = onDismiss) { Text("Zavřít") }
            if (selected.isNotEmpty()) {
                Button(onClick = { confirm(); onDismiss() }) { Text("Přidat vybrané (${selected.size})") }
            }
        }
    },
)
```

### 7.3) Create Dialog (e.g., "Vytvořit nový projekt")

```kotlin
AlertDialog(
    title = { Text("Vytvořit nový projekt") },
    text = {
        Column {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Název") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Popis (volitelné)") }, minLines = 2)
        }
    },
    confirmButton = { Button(onClick = { create() }, enabled = name.isNotBlank()) { Text("Vytvořit") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
)
```

### 7.4) Delete Confirmation

Always use `ConfirmDialog` from `com.jervis.ui.util`:

```kotlin
ConfirmDialog(
    visible = showDelete,
    title = "Smazat připojení",
    message = "Opravdu chcete smazat \"${item.name}\"? Tuto akci nelze vrátit.",
    confirmText = "Smazat",
    onConfirm = { showDelete = false; handleDelete() },
    onDismiss = { showDelete = false },
    isDestructive = true,
)
```

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
| Status indicator       | `labelMedium`                      | green / yellow / red (via `StatusIndicator`) |

### Button Colors

| Button type  | Colors |
|-------------|--------|
| Primary action | `JPrimaryButton` (primary container) |
| Secondary / cancel | `TextButton` |
| Destructive | `Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error))` |

---

## 9) Migration Rules & Checklist

When adding or modifying a settings screen:

1. **Does it need category navigation?** → Use `JAdaptiveSidebarLayout`
2. **Does it list entities with CRUD?** → Use `JListDetailLayout` + `JDetailScreen`
3. **Is it a simple flat list?** → `LazyColumn` + `JActionBar` + state components
4. **Cards** → Always `CardDefaults.outlinedCardBorder()`
5. **Touch targets** → All rows/buttons ≥ 44 dp (`JervisSpacing.touchTarget`)
6. **Loading/Empty/Error** → Use `JCenteredLoading` / `JEmptyState` / `JErrorState`
7. **Git config** → Use shared `GitCommitConfigFields()` from ClientsSettings
8. **Capability labels** → Use shared `getCapabilityLabel()` / `getIndexAllLabel()`
9. **Back navigation** → `JTopBar(onBack = ...)` or `JDetailScreen(onBack = ...)`
10. **Forms** → `OutlinedTextField` with `Modifier.fillMaxWidth()`, `label = { Text("...") }`
11. **Confirm destructive actions** → `ConfirmDialog`
12. **Refresh data** → `RefreshIconButton` in `JActionBar`

### Forbidden Patterns

| Don't | Do instead |
|-------|-----------|
| `Card(elevation = ..., colors = surfaceVariant)` | `Card(border = CardDefaults.outlinedCardBorder())` |
| `Box { CircularProgressIndicator() }` centered | `JCenteredLoading()` |
| Inline save/cancel below form | `JDetailScreen(onSave = ..., onBack = ...)` |
| Fixed sidebar width without adaptive | `JAdaptiveSidebarLayout` |
| `Row` of buttons without alignment | `JActionBar { ... }` or `Row(Arrangement.spacedBy(8.dp, Alignment.End))` |
| `IconButton` without explicit size | `IconButton(modifier = Modifier.size(JervisSpacing.touchTarget))` |
| Duplicating `getCapabilityLabel()` | Import from `ClientsSettings.kt` (internal) |
| `TopAppBar` directly | `JTopBar(title, onBack, actions)` |

---

## 10) File Structure Reference

```
shared/ui-common/src/commonMain/kotlin/com/jervis/ui/
├── design/
│   └── DesignSystem.kt              ← All J* components + adaptive layouts
├── components/
│   └── SettingComponents.kt         ← SettingCard, StatusIndicator, ActionRibbon
├── navigation/
│   └── AppNavigator.kt              ← Screen enum + navigator
├── screens/
│   ├── settings/
│   │   ├── SettingsScreen.kt        ← JAdaptiveSidebarLayout + categories
│   │   └── sections/
│   │       ├── ClientsSettings.kt   ← Expandable cards (clients + nested projects) + shared helpers
│   │       ├── ProjectsSettings.kt  ← ProjectEditForm (internal, reused by ClientsSettings)
│   │       ├── ProjectGroupsSettings.kt ← JListDetailLayout (group CRUD + shared resources)
│   │       ├── EnvironmentsSettings.kt  ← JListDetailLayout (environment CRUD + components)
│   │       ├── ConnectionsSettings.kt ← Flat list + per-card actions
│   │       ├── GitSettings.kt       ← (standalone git config)
│   │       ├── BugTrackerSettings.kt ← (standalone bug tracker config)
│   │       └── SchedulerSettings.kt  ← (standalone scheduler config)
│   ├── MainScreen.kt
│   ├── AgentWorkloadScreen.kt  ← Agent activity log (in-memory, click from AgentStatusRow)
│   └── ConnectionsScreen.kt
├── util/
│   ├── IconButtons.kt               ← RefreshIconButton, DeleteIconButton, EditIconButton
│   ├── ConfirmDialog.kt             ← ConfirmDialog
│   ├── CopyableTextCard.kt          ← CopyableTextCard + clipboard handler
│   ├── BrowserHelper.kt             ← expect fun openUrlInBrowser
│   └── FilePickers.kt               ← expect fun pickTextFileContent
└── App.kt                           ← Root composable
```
