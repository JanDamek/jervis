Jervis UI Design System – Shared Components (Compose Multiplatform)

Overview
This document standardizes UI building blocks for all platforms using Compose. Always reuse these components to keep a consistent look and behavior across screens. Keep screens simple and fail-fast as per platform rules.

Core Components
1) JTopBar
• Package: com.jervis.ui.design
• Purpose: Unified top application bar (title + optional back + actions)
• Usage:
  JTopBar(
      title = "Screen Title",
      onBack = { /* navigate back */ },   // omit if no back action
      actions = {
          // RowScope: put icon/text buttons here
          com.jervis.ui.util.RefreshIconButton(onClick = ::reload)
      }
  )

2) JSection
• Purpose: Section container with consistent padding and background.
• Usage:
  JSection(title = "Settings") {
      // content
  }

3) JActionBar
• Purpose: Right-aligned action bar for section-level actions.
• Usage:
  JActionBar {
      Button(onClick = ::save) { Text("Save") }
      OutlinedButton(onClick = ::reset) { Text("Reset") }
  }

4) JTableHeaderRow + JTableHeaderCell
• Purpose: Lightweight table header helpers.
• Usage:
  JTableHeaderRow {
      JTableHeaderCell("Column A", modifier = Modifier.weight(0.3f))
      JTableHeaderCell("Column B", modifier = Modifier.weight(0.7f))
  }

5) JTableRowCard
• Purpose: Standardized row card with selected style.
• Usage:
  JTableRowCard(selected = isSelected, modifier = Modifier.fillMaxWidth()) {
      // row content
  }

6) JCenteredLoading
• Purpose: Full‑width centered loading indicator for screen or panel states.
• Usage:
  JCenteredLoading()

7) JErrorState
• Purpose: Standard error presentation with optional Retry action.
• Usage:
  JErrorState(
      message = "Failed to load data",
      onRetry = ::reload // pass null to hide the button
  )

8) JEmptyState
• Purpose: Consistent empty‑state placeholder.
• Usage:
  JEmptyState(message = "No items found")
  // Optional: icon parameter (default ✓)

9) JRunTextButton
• Purpose: Standardized "Run" action used by Indexing screens.
• Usage:
  JRunTextButton(onClick = ::runNow)
  // Text param defaults to "Run"; use only if a different label is domain‑required.

Spacing
Use JervisSpacing for consistent spacing: outerPadding, sectionPadding, itemGap.

Utility Components (existing shared)
1) ConfirmDialog
• Package: com.jervis.ui.util
• Always use for destructive actions.
• Title format: "Delete {ItemType}"
• Confirm button text: "Delete"

2) Icon buttons
• RefreshIconButton: 🔄
• DeleteIconButton: 🗑️
• EditIconButton: ✏️
Place delete buttons at the end of each row (never in the header).

3) CopyableTextCard
• Package: com.jervis.ui.util
• Display longer text with copy functionality.
• Options: containerColor, contentColor, useMonospace.

Migration Rules
• Replace direct TopAppBar usages in screens with JTopBar.
• Replace ad‑hoc loading/error/empty state UIs with JCenteredLoading/JErrorState/JEmptyState.
• Replace inline "▶ Run" TextButtons in Indexing screens with JRunTextButton.
• Keep business logic intact; only change UI wrapper.
• Do not add new parameters or side-effects.

Examples
See: shared/ui-common/src/commonMain/kotlin/com/jervis/ui/*Screen.kt for refactored screens using JTopBar.
Additional examples:
• ErrorLogsScreen – JErrorState, JEmptyState
• PendingTasksScreen – JCenteredLoading, JErrorState, JEmptyState
• IndexingStatus/Detail – JRunTextButton

Notes
Code must stay Kotlin-first and idiomatic.
No fallback logic—fail fast and show explicit error messages.
Ensure all strings, comments and logs are in English.
