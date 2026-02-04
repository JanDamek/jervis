package com.jervis.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
}

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
        title = { Text(title) },
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
