package com.jervis.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jervis.ui.util.ConfirmDialog

/**
 * Jervis Design System – centralized components for a unified look & feel.
 *
 * ALL screens must use these J-prefixed components. Changing a component here
 * re-themes the entire app.
 */

// ══════════════════════════════════════════════════════════════════════════════
// SPACING
// ══════════════════════════════════════════════════════════════════════════════

object JervisSpacing {
    val outerPadding: Dp = 10.dp
    val sectionPadding: Dp = 12.dp
    val itemGap: Dp = 8.dp
    val touchTarget: Dp = 44.dp
    val sectionGap: Dp = 16.dp
    val fieldGap: Dp = 8.dp
    val watchTouchTarget: Dp = 56.dp
}

/** Backward-compat alias — use [JervisBreakpoints.COMPACT_DP] for new code. */
const val COMPACT_BREAKPOINT_DP = 600

// ══════════════════════════════════════════════════════════════════════════════
// THEME
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JervisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) JervisDarkColorScheme else JervisLightColorScheme
    val typography = JervisTypography.create()
    val semanticColors = if (darkTheme) {
        JervisSemanticColors(
            success = JervisColors.successDark,
            warning = JervisColors.warningDark,
            info = JervisColors.infoDark,
        )
    } else {
        JervisSemanticColors(
            success = JervisColors.success,
            warning = JervisColors.warning,
            info = JervisColors.info,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = JervisShapes,
    ) {
        CompositionLocalProvider(
            LocalJervisSemanticColors provides semanticColors,
        ) {
            content()
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// NAVIGATION
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = if (onBack != null) {
            {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(JervisSpacing.touchTarget),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Zpět",
                    )
                }
            }
        } else {
            {}
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// STATE COMPONENTS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JCenteredLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().height(120.dp).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun JErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(JervisSpacing.itemGap))
                JTextButton(onClick = onRetry) { Text("Znovu") }
            }
        }
    }
}

@Composable
fun JEmptyState(
    message: String,
    icon: ImageVector? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(JervisSpacing.itemGap))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Backward-compat overload with emoji icon string. */
@Composable
fun JEmptyState(
    message: String,
    icon: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = icon,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(JervisSpacing.itemGap))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// GROUPING & SECTIONS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .padding(JervisSpacing.outerPadding)
            .clip(shape)
            .background(bg)
            .padding(JervisSpacing.sectionPadding),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(JervisSpacing.itemGap))
        }
        content()
    }
}

@Composable
fun JActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap)) {
                content()
            }
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// BUTTONS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}


@Composable
fun JSecondaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

@Composable
fun JTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        content = content,
    )
}

@Composable
fun JDestructiveButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        shape = MaterialTheme.shapes.medium,
        content = content,
    )
}

@Composable
fun JRunTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    text: String = "Spustit",
) {
    JTextButton(onClick = onClick, enabled = enabled) { Text("▶ $text") }
}

// ── Icon Buttons ────────────────────────────────────────────────────────────

@Composable
fun JIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(JervisSpacing.touchTarget),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
fun JRefreshButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Refresh, contentDescription = "Obnovit", enabled = enabled)
}

@Composable
fun JDeleteButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(
        onClick = onClick,
        icon = Icons.Default.Delete,
        contentDescription = "Smazat",
        enabled = enabled,
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
fun JEditButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Edit, contentDescription = "Upravit", enabled = enabled)
}

@Composable
fun JAddButton(onClick: () -> Unit, enabled: Boolean = true) {
    JIconButton(onClick = onClick, icon = Icons.Default.Add, contentDescription = "Přidat", enabled = enabled)
}

/**
 * Remove icon button with built-in confirmation dialog.
 * Click shows ConfirmDialog; onConfirmed fires only after user confirms.
 */
@Composable
fun JRemoveIconButton(
    onConfirmed: () -> Unit,
    title: String = "Odebrat?",
    message: String = "Položka bude odebrána.",
    confirmText: String = "Odebrat",
    contentDescription: String = "Odebrat",
) {
    var showConfirm by remember { mutableStateOf(false) }

    JIconButton(
        onClick = { showConfirm = true },
        icon = Icons.Default.Close,
        contentDescription = contentDescription,
    )

    ConfirmDialog(
        visible = showConfirm,
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = { showConfirm = false; onConfirmed() },
        onDismiss = { showConfirm = false },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// CARDS
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Universal card component. Always uses outlined border (no elevation).
 * Supports selection state with secondaryContainer background.
 */
@Composable
fun JCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * List item card with title, subtitle, leading/trailing content, and optional action row.
 * Minimum touch target height enforced.
 */
@Composable
fun JListItemCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = {
        Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    JCard(onClick = onClick, modifier = modifier, selected = selected) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                leadingContent()
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

// ── Backward-compat table components ────────────────────────────────────────

@Composable
fun JTableHeaderRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun JTableHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** @deprecated Use [JCard] with selected parameter instead. */
@Composable
fun JTableRowCard(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    JCard(selected = selected, modifier = modifier, content = content)
}

// ══════════════════════════════════════════════════════════════════════════════
// DATA DISPLAY
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Key-value display row. Label in primary color, value as selectable text.
 * Replaces duplicated TaskDetailField / DetailField / RagDetailField patterns.
 */
@Composable
fun JKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Status badge with colored dot and label text.
 * Replaces StatusIndicator from SettingComponents.kt.
 */
@Composable
fun JStatusBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalJervisSemanticColors.current
    val color = when (status.uppercase()) {
        "CONNECTED", "RUNNING", "OK", "ACTIVE" -> semanticColors.success
        "CONNECTING", "PENDING", "STARTING" -> semanticColors.warning
        "ERROR", "DISCONNECTED", "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * Monospace code block with selectable text.
 */
@Composable
fun JCodeBlock(
    content: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        SelectionContainer(modifier = Modifier.padding(12.dp)) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// FORM COMPONENTS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    readOnly: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = isError,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> JDropdown(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    label: String,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedItem?.let { itemLabel(it) } ?: placeholder,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun JSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun JSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueLabel: (Float) -> String = { "%.1f".format(it) },
    description: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                valueLabel(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
fun JCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// DIALOGS
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Confirm dialog with Czech defaults. Supports keyboard shortcuts (Enter/Escape).
 */
@Composable
fun JConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String = "Potvrdit",
    dismissText: String = "Zrušit",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Box(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.Enter, Key.Spacebar -> { onConfirm(); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        } else false
                    },
            ) {
                if (isDestructive) {
                    JDestructiveButton(onClick = onConfirm) { Text(confirmText) }
                } else {
                    JPrimaryButton(onClick = onConfirm) { Text(confirmText) }
                }
            }
        },
        dismissButton = {
            JSecondaryButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

/**
 * Form dialog with scrollable content, confirm/dismiss buttons.
 */
@Composable
fun JFormDialog(
    visible: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Potvrdit",
    dismissText: String = "Zrušit",
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                content()
            }
        },
        confirmButton = {
            JPrimaryButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) }
        },
        dismissButton = {
            JTextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

/**
 * Selection dialog for choosing from a list.
 */
@Composable
fun <T> JSelectionDialog(
    visible: Boolean,
    title: String,
    items: List<T>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(items) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) },
                    ) {
                        itemContent(item)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            JTextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// FEEDBACK
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(16.dp),
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// WATCH COMPONENTS (prepared for future use)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun JWatchActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = JervisSpacing.watchTouchTarget),
        colors = if (isDestructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun JWatchApprovalCard(
    title: String,
    description: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        JWatchActionButton(text = "Schválit", onClick = onApprove)
        JWatchActionButton(text = "Zamítnout", onClick = onDeny, isDestructive = true)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ADAPTIVE LAYOUT COMPONENTS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun <T> JAdaptiveSidebarLayout(
    categories: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    title: String,
    categoryIcon: @Composable (T) -> Unit,
    categoryTitle: (T) -> String,
    categoryDescription: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < COMPACT_BREAKPOINT_DP.dp
        val showContent = selectedIndex >= 0 && selectedIndex < categories.size

        if (isCompact) {
            if (!showContent) {
                Column(modifier = Modifier.fillMaxSize()) {
                    JTopBar(title = title, onBack = onBack)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(categories.size) { index ->
                            val category = categories[index]
                            JNavigationRow(
                                icon = { categoryIcon(category) },
                                title = categoryTitle(category),
                                subtitle = categoryDescription(category),
                                onClick = { onSelect(index) },
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    JTopBar(
                        title = categoryTitle(categories[selectedIndex]),
                        onBack = { onSelect(-1) },
                    )
                    Box(modifier = Modifier.fillMaxSize().padding(JervisSpacing.outerPadding)) {
                        content(categories[selectedIndex])
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .padding(vertical = 16.dp),
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(JervisSpacing.touchTarget),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Zpět")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    categories.forEachIndexed { index, category ->
                        val isSelected = index == selectedIndex
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .height(JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                    categoryIcon(category)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = categoryTitle(category),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }

                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (showContent) {
                        val category = categories[selectedIndex]
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = categoryTitle(category),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                text = categoryDescription(category),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 24.dp),
                        ) {
                            content(category)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JNavigationRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
    }
}

@Composable
fun <T> JListDetailLayout(
    items: List<T>,
    selectedItem: T?,
    isLoading: Boolean,
    onItemSelected: (T?) -> Unit,
    emptyMessage: String,
    emptyIcon: String = "📋",
    listHeader: @Composable () -> Unit = {},
    listItem: @Composable (T) -> Unit,
    detailContent: @Composable (T) -> Unit,
) {
    if (selectedItem != null) {
        detailContent(selectedItem)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            listHeader()
            Spacer(Modifier.height(JervisSpacing.itemGap))
            if (isLoading && items.isEmpty()) {
                JCenteredLoading()
            } else if (items.isEmpty()) {
                JEmptyState(message = emptyMessage, icon = emptyIcon)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items.size) { index ->
                        listItem(items[index])
                    }
                }
            }
        }
    }
}

@Composable
fun JDetailScreen(
    title: String,
    onBack: () -> Unit,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        JTopBar(
            title = title,
            onBack = onBack,
            actions = actions,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = JervisSpacing.outerPadding),
        ) {
            content()
        }

        if (onSave != null) {
            JActionBar(modifier = Modifier.padding(JervisSpacing.outerPadding)) {
                JTextButton(onClick = onBack) { Text("Zrušit") }
                JPrimaryButton(onClick = onSave, enabled = saveEnabled) { Text("Uložit") }
            }
        }
    }
}

/**
 * Vertical split layout with draggable divider.
 */
@Composable
fun JVerticalSplitLayout(
    splitFraction: Float,
    onSplitChange: (Float) -> Unit,
    topContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val topHeight = totalHeight * splitFraction
        val bottomHeight = totalHeight * (1f - splitFraction)

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(topHeight)) {
                topContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(modifier = Modifier.fillMaxWidth().height(bottomHeight)) {
                bottomContent()
            }
        }
    }
}
