package com.jervis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jervis.dto.deadline.DeadlineDashboard
import com.jervis.dto.deadline.DeadlineItem
import com.jervis.dto.deadline.DeadlineUrgency
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JervisSpacing

/**
 * EPIC 8-S4: Deadline Dashboard Widget.
 *
 * Displays color-coded urgency overview and countdown for deadline items.
 * Designed as an embeddable widget — parent screen provides the data.
 */
@Composable
fun DeadlineDashboardWidget(
    dashboard: DeadlineDashboard,
    modifier: Modifier = Modifier,
) {
    if (dashboard.items.isEmpty()) {
        JEmptyState(message = "Žádné sledované termíny")
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
    ) {
        // Summary card
        item {
            DeadlineSummaryCard(dashboard)
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("Termíny", style = MaterialTheme.typography.titleMedium)
        }

        // Individual deadline items
        items(dashboard.items) { item ->
            DeadlineItemCard(item)
        }
    }
}

@Composable
private fun DeadlineSummaryCard(dashboard: DeadlineDashboard) {
    JCard {
        Column(modifier = Modifier.padding(JervisSpacing.sectionPadding)) {
            Text("Přehled termínů", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                UrgencyCounter("Po termínu", dashboard.overdueCount, urgencyColor(DeadlineUrgency.OVERDUE))
                UrgencyCounter("Urgentní", dashboard.urgentCount, urgencyColor(DeadlineUrgency.RED))
                UrgencyCounter("Blížící se", dashboard.upcomingCount, urgencyColor(DeadlineUrgency.YELLOW))
            }
        }
    }
}

@Composable
private fun UrgencyCounter(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeadlineItemCard(item: DeadlineItem) {
    val color = urgencyColor(item.urgency)

    JCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Title with urgency dot
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(10.dp),
                        shape = CircleShape,
                        color = color,
                    ) {}
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Countdown
                Text(
                    text = formatRemainingDays(item.remainingDays),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }

            Spacer(Modifier.height(4.dp))
            JKeyValueRow("Zdroj", item.source)
            JKeyValueRow("Stav", item.status)
        }
    }
}

@Composable
private fun urgencyColor(urgency: DeadlineUrgency): Color = when (urgency) {
    DeadlineUrgency.GREEN -> Color(0xFF4CAF50)
    DeadlineUrgency.YELLOW -> Color(0xFFFFC107)
    DeadlineUrgency.ORANGE -> Color(0xFFFF9800)
    DeadlineUrgency.RED -> Color(0xFFF44336)
    DeadlineUrgency.OVERDUE -> Color(0xFF9C27B0)
}

private fun formatRemainingDays(days: Int): String = when {
    days < -1 -> "${-days} dní po termínu"
    days == -1 -> "1 den po termínu"
    days == 0 -> "Dnes!"
    days == 1 -> "Zítra"
    else -> "Za $days dní"
}
