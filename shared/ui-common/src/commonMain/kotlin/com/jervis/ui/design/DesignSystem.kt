package com.jervis.ui.design

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Jervis Design System – minimal common components to unify look & feel across all UI screens.
 *
 * Keep these components lightweight wrappers around Material 3 and reuse them everywhere.
 */
object JervisSpacing {
    val outerPadding: Dp = 10.dp
    val sectionPadding: Dp = 12.dp
    val itemGap: Dp = 8.dp
    val touchTarget: Dp = 44.dp
}

/**
 * Breakpoint for compact (phone) vs expanded (tablet/desktop) layouts.
 * Phone: < 600dp, Tablet/Desktop: >= 600dp
 */
const val COMPACT_BREAKPOINT_DP = 600

@Composable
fun JervisTheme(content: @Composable () -> Unit) {
    // Placeholder for potential custom theming hooks later. For now we rely on MaterialTheme.
    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navIcon: @Composable () -> Unit =
        if (onBack != null) {
            { TextButton(onClick = onBack) { Text("← Back") } }
        } else {
            {}
        }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = navIcon,
        actions = actions,
    )
}

@Composable
fun JCenteredLoading() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().height(120.dp).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
fun JErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Box(
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
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
fun JEmptyState(
    message: String,
    icon: String = "✓",
) {
    androidx.compose.foundation.layout.Box(
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

@Composable
fun JRunTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    text: String = "Run",
) {
    TextButton(onClick = onClick, enabled = enabled) { Text("▶ $text") }
}

@Composable
fun JSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier =
            modifier
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

@Composable
fun JTableHeaderRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
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

@Composable
fun JTableRowCard(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val elevation = if (selected) 4.dp else 1.dp
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), content = content)
    }
}

/**
 * Primary button with consistent Material3 primary color styling.
 */
@Composable
fun JPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.medium,
    ) {
        content()
    }
}

// ── Adaptive Layout Components ──────────────────────────────────────────────────

/**
 * Adaptive sidebar/list navigation for settings-like screens.
 *
 * - Compact (phone): shows category list as full-width clickable items.
 *   When a category is selected, shows content full-screen with back navigation.
 * - Expanded (tablet/desktop): shows sidebar + content side by side.
 *
 * @param categories list of items to display
 * @param selectedIndex currently selected category index
 * @param onSelect callback when a category is tapped
 * @param onBack callback for back navigation (compact mode goes to list, expanded goes to parent)
 * @param categoryItem composable for rendering a category row
 * @param content composable for the selected category's content
 */
@Composable
fun <T> JAdaptiveSidebarLayout(
    categories: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    title: String,
    categoryIcon: (T) -> String,
    categoryTitle: (T) -> String,
    categoryDescription: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < COMPACT_BREAKPOINT_DP.dp
        val showContent = selectedIndex >= 0 && selectedIndex < categories.size

        if (isCompact) {
            // Phone: either show category list or content
            if (!showContent) {
                // Category list
                Column(modifier = Modifier.fillMaxSize()) {
                    JTopBar(title = title, onBack = onBack)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(categories.size) { index ->
                            val category = categories[index]
                            JNavigationRow(
                                icon = categoryIcon(category),
                                title = categoryTitle(category),
                                subtitle = categoryDescription(category),
                                onClick = { onSelect(index) },
                            )
                        }
                    }
                }
            } else {
                // Content full-screen
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
            // Tablet/Desktop: sidebar + content
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zpět",
                        )
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
                                Text(
                                    categoryIcon(category),
                                    modifier = Modifier.size(24.dp),
                                )
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

                // Content
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

/**
 * Navigation row for compact mode category lists and list→detail patterns.
 * Ensures minimum 44dp touch target height.
 */
@Composable
fun JNavigationRow(
    icon: String,
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
            Text(icon, modifier = Modifier.size(28.dp), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
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

/**
 * Adaptive list-detail layout for entity management screens (clients, projects, etc.)
 *
 * Shows list on both compact and expanded. When an item is selected:
 * - Compact: navigates to full-screen detail with back navigation
 * - Expanded: same behavior (replace list with detail)
 *
 * Provides consistent header with title, action buttons, and back navigation.
 */
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

/**
 * Detail screen wrapper with consistent back navigation and save/cancel action bar.
 */
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
                TextButton(onClick = onBack) {
                    Text("Zrušit")
                }
                Button(
                    onClick = onSave,
                    enabled = saveEnabled,
                ) {
                    Text("Uložit")
                }
            }
        }
    }
}
