# UI Guidelines - Delete Operations & Text Display

This document defines the mandatory standards for implementing delete operations and text display across all UI screens in the Jervis application.

## Table of Contents
- [Delete Button Standard](#delete-button-standard)
- [CopyableTextCard Standard](#copyabletextcard-standard)
- [Confirmation Dialog Standard](#confirmation-dialog-standard)
- [Implementation Patterns](#implementation-patterns)
- [Examples](#examples)
- [Anti-Patterns](#anti-patterns)

---

## Delete Button Standard

### Component to Use
**ALWAYS** use: `com.jervis.ui.util.DeleteIconButton`

```kotlin
import com.jervis.ui.util.DeleteIconButton

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

## CopyableTextCard Standard

### Component to Use
**ALWAYS** use: `com.jervis.ui.util.CopyableTextCard` for displaying text content with copy functionality

```kotlin
import com.jervis.ui.util.CopyableTextCard

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
import com.jervis.ui.util.ConfirmDialog

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
    DeleteIconButton(onClick = { ... }, enabled = selectedItem != null)
}
```

**Use instead**: Per-row delete button at the end of each row

### ❌ Don't: Use enabled/disabled State
```kotlin
// WRONG - Don't disable button
DeleteIconButton(
    onClick = { ... },
    enabled = selectedItem != null
)
```

**Use instead**: Always-enabled button that shows confirmation dialog

### ❌ Don't: Custom Copy Implementation with Text
```kotlin
// WRONG - Don't use TextButton with "Copy" text
TextButton(onClick = { clipboard.setText(...) }) {
    Text("Copy")
}
```

**Use instead**: `CopyableTextCard` with 📋 icon

### ❌ Don't: Manual Copy UI
```kotlin
// WRONG - Don't build custom copy UI
Row {
    Text(title)
    IconButton(onClick = { clipboard.setText(...) }) {
        Icon(...)
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
