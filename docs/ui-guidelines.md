# UI Guidelines

This document defines mandatory standards for UI behaviors across Jervis. It includes delete operations, text display,
notifications, secrets visibility, keyboard navigation, and connections management.

## Table of Contents

- [Delete Button Standard](#delete-button-standard)
- [CopyableTextCard Standard](#copyabletextcard-standard)
- [Confirmation Dialog Standard](#confirmation-dialog-standard)
- [Implementation Patterns](#implementation-patterns)
- [Examples](#examples)
- [Anti-Patterns](#anti-patterns)

---

## Global UI Policies

- Secrets are visible: UI must display full values (passwords/tokens/keys) without masking. This app is not public.
- Notifications: Use snackbars at the top-right corner of the window (non-modal). Prefer brief, actionable messages.
- Tab behavior (Desktop): Tab moves focus (Next/Previous with Shift). It must NOT insert whitespace in text fields. Use
  onPreviewKeyEvent at the top layout.

---

## Delete Button Standard

### Component to Use

**ALWAYS** use: `com.jervis.ui.util.DeleteIconButton`

```kotlin
DeleteIconButton(
    onClick = { showDeleteDialog = true }
)
```

### Placement Rules

1. **Location**: Place delete button **at the end of each row** in the list/table
2. **No header button**: Do NOT place delete button in list/table header
3. **Visual**: Use the standardized icon button (trash can emoji 🗑️)
4. **Size**: Standard IconButton size (no custom sizing)
5. **Always enabled**: No enabled/disabled state - button is always clickable

### When to Use

- Deleting entities (tasks, logs, schedules, etc.)
- Removing items from lists
- Canceling scheduled operations

### Action Naming

- **Primary action name**: "Delete" (not "Cancel", "Revoke", "Remove", "Discard")
- **Exception**: Only use different terminology if domain-specific (e.g., financial "Void", legal "Revoke")
- **Button label**: No text label (icon-only button)

---

## Settings UI Design Standard (Concept)

### Core Philosophy
The Settings window is a central hub for system configuration. It must remain **clean**, **discoverable**, and **operationally efficient**. We avoid deep nesting of navigation and prefer a "Sidebar + Content" layout for desktop or a "Flat Navigation" layout for mobile.

### Layout Structure
1.  **Navigation Sidebar (Left)**: Icons + Labels for major categories.
2.  **Breadcrumbs/Title (Top)**: Indication of where the user is (e.g., `Settings > Clients > Edit Client`).
3.  **Action Bar (Top Right)**: Contextual actions like "Save", "Refresh", or "Add New".
4.  **Main Content (Center)**: Scrollable area with cards grouping related settings.

### Navigation Categories
Settings are organized into 4 logical tiers:
-   **General**: Application-wide settings (Language, UI theme, Global Defaults).
-   **Structure**: Hierarchical entities.
    -   **Clients**: High-level organizational units.
    -   **Projects**: Specific units belonging to clients.
-   **Integrations**: External tool connections.
    -   **Connections**: Technical connectivity (HTTP, SMTP, IMAP, OAuth2).
    -   **Atlassian**: Jira/Confluence integration states.
    -   **Git**: Provider configurations.
-   **System**: Operational status.
    -   **Indexing**: Status of RAG and background processing.
    -   **Scheduler**: Cron tasks and background jobs.
    -   **Logs**: UI and System error logs.

### Settings Window: Detailed Concept

#### 1. Sidebar Navigation (Left)
- **Compact View**: Icons only.
- **Expanded View**: Icon + Title.
- **Visuals**: Use Material Icons or Emoji symbols consistently.
- **Selection**: High contrast highlight on the active category.

#### 2. Settings Content Area (Right)
##### A. List View (e.g., Clients, Projects)
- **Top Row**: Search bar + "Add New" button.
- **Items**: Horizontal cards with:
    - Left: Primary name + subtitle (ID/Status).
    - Center: Tags for active integrations (e.g., [Git][Jira]).
    - Right: Edit (✏️) and Delete (🗑️) icons.

##### B. Edit View (Form)
- **Sections**: Use vertical spacing or `Divider` between logical groups.
- **Headers**: Small bold text for group titles (e.g., "DATABASE SETTINGS", "AUTH").
- **Fields**: Outlined text fields with clear labels and helper text for validation.
- **In-place Actions**: "Test Connection" or "Verify Token" buttons placed directly next to the relevant fields.

#### 3. Category Definitions

| Category | Content |
| :--- | :--- |
| **General** | Language, Timezone, UI Theme (Light/Dark/Auto), Default Client/Project. |
| **Clients** | List of organizational units. Edit form includes Git Credentials and Confluence defaults. |
| **Projects** | List of projects. Edit form includes Jira/Confluence overrides and Project-specific Git settings. |
| **Connections** | Inventory of technical connections (HTTP, IMAP, etc.). Used as a pool for Clients/Projects. |
| **Atlassian** | Status of Jira/Confluence links, OAuth status, and Global Tenant settings. |
| **Git** | Provider configuration (GitHub, GitLab, Bitbucket). |
| **Indexing** | Status dashboard for background indexers (RAG). Start/Stop controls and error viewing. |
| **Scheduler** | Management of periodic tasks. View last execution and next scheduled run. |
| **Logs** | Scrollable table of error logs with filtering by severity and source. |

### UI Components for Settings
1.  **Setting Card**: Use `Card` with an outline or subtle elevation to group related fields (e.g., "Authentication", "API Configuration").
2.  **Status Indicator**: Use colored dots or badges:
    -   🟢 **Connected/Idle**
    -   🔵 **Running/Syncing**
    -   🔴 **Error/Disconnected**
3.  **Action Ribbons**: Floating or pinned bottom bar for "Save/Cancel" in complex edit forms to ensure they are always reachable.

### Interactions & Behavior
-   **Real-time Validation**: Validate inputs as the user types (e.g., URL format, Port range).
-   **Direct Test Actions**: "Test Connection" buttons must be adjacent to the credentials they test.
-   **Deep Linking**: Every settings page/tab should be reachable via a direct navigation state.
-   **Secrets Visibility**: Per global policy, secrets are visible. Use clear warnings for destructive actions.

---

## CopyableTextCard Standard

### Component to Use

**ALWAYS** use: `com.jervis.ui.util.CopyableTextCard` for displaying text content with copy functionality

```kotlin
CopyableTextCard(
    title = "System Prompt",
    content = systemPromptText,
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    useMonospace = true
)
```

### Features

1. **Copy Icon**: 📋 emoji in top-right corner (NO text label like "Copy")
2. **Selectable Text**: Content is automatically selectable for manual copying
3. **Consistent Layout**: Title at top, content below
4. **Configurable Colors**: Pass containerColor and contentColor
5. **Monospace Option**: Set `useMonospace = true` for code/technical content

### When to Use

- Displaying prompts (system, user)
- Showing API responses
- Technical content that users might want to copy
- Any text content where copy functionality is useful

### Do NOT Use

- TextButton with "Copy" text label
- Custom copy implementations
- Manual clipboard management without the template

---

## Confirmation Dialog Standard

### Component to Use

**ALWAYS** use: `com.jervis.ui.util.ConfirmDialog`

```kotlin
ConfirmDialog(
    visible = showDeleteDialog && itemToDelete != null,
    title = "Delete {ItemType}",
    message = "Are you sure you want to delete this {item}? This action cannot be undone.",
    confirmText = "Delete",
    onConfirm = { handleDelete() },
    onDismiss = { showDeleteDialog = false }
)
```

### Dialog Configuration

#### Title Format

```
"Delete {ItemType}"
```

Examples:

- "Delete User Task"
- "Delete Error Log"
- "Delete Scheduled Task"
- "Delete Pending Task"

#### Message Format

```
"Are you sure you want to delete this {item}? This action cannot be undone."
```

#### Button Configuration

- **Confirm button text**: "Delete"
- **Confirm button color**: Error color scheme (red)
- **Dismiss button text**: "Cancel"
- **Dismiss button style**: Outlined

### When Dialog Appears

- Dialog shows when: `showDeleteDialog = true AND itemToDelete != null`
- Dialog dismisses when:
    - User clicks "Cancel"
    - User clicks outside dialog (onDismissRequest)
    - After successful delete operation

---

## Implementation Patterns

### Pattern: Per-Row Delete (STANDARD)

Use this pattern for **all list/table screens**.

```kotlin
@Composable
fun ExampleScreen(
    repository: Repository,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var itemToDelete by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()

    fun loadItems() {
        scope.launch {
            items = repository.listItems()
        }
    }

    fun handleDelete() {
        val item = itemToDelete ?: return
        scope.launch {
            try {
                repository.delete(item.id)
                itemToDelete = null
                loadItems()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    LaunchedEffect(Unit) { loadItems() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { loadItems() })
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)
        ) {
            items(items) { item ->
                ItemCard(
                    item = item,
                    onDelete = { itemToDelete = item }
                )
            }
        }
    }

    // Confirmation dialog
    com.jervis.ui.util.ConfirmDialog(
        visible = itemToDelete != null,
        title = "Delete Item",
        message = "Are you sure you want to delete this item? This action cannot be undone.",
        confirmText = "Delete",
        onConfirm = { handleDelete() },
        onDismiss = { itemToDelete = null }
    )
}

@Composable
private fun ItemCard(
    item: Item,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
            com.jervis.ui.util.DeleteIconButton(
                onClick = onDelete
            )
        }
    }
}
```

### Pattern: CopyableTextCard Usage

```kotlin
@Composable
fun ContentDisplayScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // System Prompt
        CopyableTextCard(
            title = "System Prompt",
            content = systemPromptText,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            useMonospace = true
        )

        // User Prompt
        CopyableTextCard(
            title = "User Prompt",
            content = userPromptText,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            useMonospace = true
        )

        // Response
        CopyableTextCard(
            title = "Response",
            content = responseText,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            useMonospace = false
        )
    }
}
```

---

## Examples

### Correct Implementation

✅ **UserTasksScreen**

```kotlin
// Per-row delete button
Row {
    Column(modifier = Modifier.weight(1f)) {
        Text(task.title)
        // ... task details
    }
    com.jervis.ui.util.DeleteIconButton(
        onClick = {
            selectedTask = task
            showDeleteDialog = true
        }
    )
}

// Confirmation dialog
com.jervis.ui.util.ConfirmDialog(
    visible = showDeleteDialog && selectedTask != null,
    title = "Delete User Task",
    message = "Are you sure you want to delete this task? This action cannot be undone.",
    confirmText = "Delete",
    onConfirm = { handleDelete() },
    onDismiss = { showDeleteDialog = false }
)
```

✅ **DebugWindow with CopyableTextCard**

```kotlin
// System Prompt with copy icon
CopyableTextCard(
    title = "System Prompt",
    content = session.systemPrompt,
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    useMonospace = true
)
```

---

## Anti-Patterns

### ❌ Don't: Delete Button in Header

```kotlin
// WRONG - Don't use header button
Row(header) {
    Text("Items")
    DeleteIconButton(onClick = { /* ... */ }, enabled = selectedItem != null)
}
```

**Use instead**: Per-row delete button at the end of each row

### ❌ Don't: Use enabled/disabled State

```kotlin
// WRONG - Don't disable button
DeleteIconButton(
    onClick = { /* ... */ },
    enabled = selectedItem != null
)
```

**Use instead**: Always-enabled button that shows confirmation dialog

### ❌ Don't: Custom Copy Implementation with Text

```kotlin
// WRONG - Don't use TextButton with "Copy" text
TextButton(onClick = { /* clipboard.setText(...) */ }) {
    Text("Copy")
}
```

**Use instead**: `CopyableTextCard` with 📋 icon

### ❌ Don't: Manual Copy UI

```kotlin
// WRONG - Don't build custom copy UI
Row {
    Text(title)
    IconButton(onClick = { /* clipboard.setText(...) */ }) {
        Icon(Icons.Default.ContentCopy, contentDescription = null)
    }
}
Text(content)
```

**Use instead**: `CopyableTextCard` template

### ❌ Don't: Use Custom AlertDialog

```kotlin
// WRONG - Don't create custom AlertDialog
if (showDeleteDialog) {
    AlertDialog(
        onDismissRequest = { showDeleteDialog = false },
        title = { Text("Delete") },
        text = { Text("Are you sure?") },
        confirmButton = {
            Button(onClick = { delete() }) { Text("OK") }
        }
    )
}
```

**Use instead**: `com.jervis.ui.util.ConfirmDialog`

---

## Connections Management (Settings)

### Overview

- Provide a dedicated Connections tab in Settings showing all connections.
- For each connectionDocument, display: name, type, state (NEW/VALID/INVALID), and ownership label: "Client: …", "
  Project: …", or "Unattached".
- Actions: Create, Edit, Test, Duplicate, Delete. Use snackbars (top-right) for feedback.

### Ownership Rules

- A connectionDocument can belong to only one owner at a time: either a Client or a Project.
- To reuse a connectionDocument elsewhere, use Duplicate to create a copy and attach it to the other owner.

### Client/Project Editors

- Inside Client and Project edit dialogs:
    - List all connections with owner labels.
    - Allow attach/detach via checkbox when unattached or owned by the current entity.
    - If owned elsewhere, show Duplicate to create a copy and auto-attach it.
    - Provide Test and Edit actions inline;
    - Provide Create Connection (quick-create dialog).

### Authorization (HTTP)

- Edit authorization via typed fields only (NONE/BASIC/BEARER). Do not use generic `credentials` fields.
- BASIC: username + password; BEARER: token; NONE: no credentials.

### Git Credentials & Overrides

- Secrets are visible in UI; provide "Load from file" buttons for SSH and GPG keys (Desktop only).
- Client-level Git settings are defaults. Project-level overrides take precedence when provided.

### Keyboard & Notifications

- Desktop: implement Tab focus traversal at the top layout of Settings.
- Notifications: snackbars in top-right after create/update/delete/test.

---

## Checklist for New Screens

When implementing delete functionality in a new screen:

- [ ] Import `com.jervis.ui.util.DeleteIconButton`
- [ ] Import `com.jervis.ui.util.ConfirmDialog`
- [ ] Add state: `var itemToDelete by remember { mutableStateOf<ItemType?>(null) }`
- [ ] Add state: `var showDeleteDialog by remember { mutableStateOf(false) }`
- [ ] Place `DeleteIconButton` at the end of each row
- [ ] Button always enabled (no enabled parameter)
- [ ] Use `ConfirmDialog` with proper title and message
- [ ] Handle error states appropriately
- [ ] Test: Verify confirmation appears before deletion
- [ ] Test: Verify button placement and UI layout

When displaying text content with copy functionality:

- [ ] Import `com.jervis.ui.util.CopyableTextCard`
- [ ] Use `CopyableTextCard` instead of custom copy UI
- [ ] Set `useMonospace = true` for code/technical content
- [ ] Configure appropriate container and content colors
- [ ] NO text label like "Copy" - icon only (📋)

---

## Summary

### Key Principles

1. **Consistency**: All delete operations and text display use the same components
2. **Safety**: Always require confirmation before deletion
3. **Clarity**: Use clear, descriptive language ("Delete", not abbreviations)
4. **Simplicity**: Use per-row delete buttons, always enabled
5. **No Text Labels**: Icons only (🗑️ for delete, 📋 for copy)

### Required Components

- `com.jervis.ui.util.DeleteIconButton` - For all delete buttons (per-row)
- `com.jervis.ui.util.ConfirmDialog` - For all delete confirmations
- `com.jervis.ui.util.CopyableTextCard` - For all text content with copy functionality

### Icon Usage Guidelines (Mandatory)

1.  **Always use standard Compose Material Icons** from the `androidx.compose.material.icons` package.
2.  **Avoid using generic `icons` package** or custom icon sub-packages.
3.  **Use fully qualified names** (e.g., `androidx.compose.material.icons.Icons.Default.Add`) to ensure compilation stability and avoid `Unresolved reference 'icons'` errors.
4.  **Common Icons**:
    *   `Icons.Default.Add` - Přidání položky.
    *   `Icons.Default.Edit` - Editace.
    *   `Icons.Default.Delete` - Mazání.
    *   `Icons.AutoMirrored.Filled.Refresh` - Refresh.
    *   `Icons.AutoMirrored.Filled.ArrowBack` - Zpět.

### Mandatory Pattern

1. Per-row delete button at end of each row
2. Always enabled (no selection state)
3. Confirmation dialog before deletion
4. CopyableTextCard for all copyable text content
5. Icon-only buttons (no text labels)

---

**Document Version**: 2.0
**Last Updated**: 2025-01-13
**Applies To**: All UI screens in Jervis application
