Jervis UI Design System – Guidance for AI Assistants

Purpose
Provide a single source of truth for UI components to be used and modified by AI assistants when editing Compose screens.

Mandatory Rules
1) Do not invent new components or parameters. Follow existing architecture and shared components.
2) Replace direct usages of TopAppBar with com.jervis.ui.design.JTopBar.
3) Use standardized utility components from com.jervis.ui.util:
   - ConfirmDialog for confirmations
   - RefreshIconButton/DeleteIconButton/EditIconButton for icon-only actions
   - CopyableTextCard for long, copyable text
4) Use standardized view states from com.jervis.ui.design:
   - JCenteredLoading for loading states covering a panel/screen
   - JErrorState(message, onRetry) for error displays (no manual columns/buttons)
   - JEmptyState(message) for empty placeholders
   - JRunTextButton for "Run" actions in Indexing screens
4) Keep logic fail-fast; never hide errors.
5) English-only in code, logs, and comments.

Available Shared Components
- JTopBar(title: String, onBack: (() -> Unit)? = null, actions: RowScope.() -> Unit = {})
- JSection(title: String? = null, modifier: Modifier = Modifier, content: ColumnScope.() -> Unit)
- JActionBar(modifier: Modifier = Modifier, content: RowScope.() -> Unit)
- JTableHeaderRow(content: RowScope.() -> Unit)
- JTableHeaderCell(text: String, modifier: Modifier = Modifier)
- JTableRowCard(selected: Boolean, modifier: Modifier = Modifier, content: ColumnScope.() -> Unit)
- JCenteredLoading()
- JErrorState(message: String, onRetry: (() -> Unit)? = null)
- JEmptyState(message: String, icon: String = "✓")
- JRunTextButton(onClick: () -> Unit, enabled: Boolean = true, text: String = "Run")

Usage Patterns
Top bar:
  JTopBar(
      title = "Screen Title",
      onBack = onBack,
      actions = {
          com.jervis.ui.util.RefreshIconButton(onClick = ::reload)
      }
  )

Delete flow:
  com.jervis.ui.util.DeleteIconButton(onClick = { showDelete = true })
  com.jervis.ui.util.ConfirmDialog(
      visible = showDelete,
      title = "Delete {ItemType}",
      message = "Are you sure you want to delete this {item}? This action cannot be undone.",
      confirmText = "Delete",
      onConfirm = ::handleDelete,
      onDismiss = { showDelete = false }
  )

Copyable text:
  com.jervis.ui.util.CopyableTextCard(
      title = "System Prompt",
      content = content,
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      useMonospace = true
  )

View states:
  // Loading
  com.jervis.ui.design.JCenteredLoading()

  // Error with retry
  com.jervis.ui.design.JErrorState(
      message = "Failed to load data",
      onRetry = ::reload
  )

  // Empty
  com.jervis.ui.design.JEmptyState(message = "No items found")

Indexing Run action:
  com.jervis.ui.design.JRunTextButton(onClick = ::runNow)

Do/Don’t
Do:
- Keep functions small and single-purpose.
- Prefer val over var; avoid !!.
Don’t:
- Add new configuration or cross-layer calls.
- Add comments explaining “what” the code does.
