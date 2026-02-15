package com.jervis.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
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
    modifier: Modifier = Modifier,
    topContent: @Composable (Modifier) -> Unit,
    bottomContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightPx = constraints.maxHeight.toFloat()
        val totalHeight = maxHeight
        val dividerHeight = 6.dp
        val topHeight = totalHeight * splitFraction
        val bottomHeight = totalHeight * (1f - splitFraction)

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(topHeight)) {
                topContent(Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dividerHeight)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            val delta = dragAmount.y / totalHeightPx
                            val newFraction = (splitFraction + delta).coerceIn(0.1f, 0.9f)
                            onSplitChange(newFraction)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(3.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                        ),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(bottomHeight)) {
                bottomContent(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Horizontal split layout with draggable vertical divider.
 * Left and right content areas separated by a 6dp draggable divider.
 */
@Composable
fun JHorizontalSplitLayout(
    splitFraction: Float,
    onSplitChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minFraction: Float = 0.2f,
    maxFraction: Float = 0.8f,
    leftContent: @Composable (Modifier) -> Unit,
    rightContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val totalWidth = maxWidth
        val dividerWidth = 6.dp
        val leftWidth = totalWidth * splitFraction
        val rightWidth = totalWidth * (1f - splitFraction)

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxHeight().width(leftWidth)) {
                leftContent(Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(dividerWidth)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            val delta = dragAmount.x / totalWidthPx
                            val newFraction = (splitFraction + delta).coerceIn(minFraction, maxFraction)
                            onSplitChange(newFraction)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .width(3.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                        ),
                )
            }
            Box(modifier = Modifier.fillMaxHeight().width(rightWidth)) {
                rightContent(Modifier.fillMaxSize())
            }
        }
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
