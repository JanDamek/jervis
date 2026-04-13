package com.jervis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.task.CalendarEntryDto
import com.jervis.dto.task.CalendarEntryType
import com.jervis.dto.task.TaskStateEnum
import com.jervis.di.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Calendar screen — weekly grid view.
 *
 * Shows all calendar entries (scheduled tasks, calendar events, deadline tasks)
 * for the displayed week. Overdue items carry forward to today.
 * Tasks without a deadline are shown on today.
 */
@Composable
fun SchedulerScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tz = remember { TimeZone.currentSystemDefault() }
    var currentMonday by remember {
        mutableStateOf(
            run {
                val today = Clock.System.now().toLocalDateTime(tz).date
                today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
            },
        )
    }
    var entries by remember { mutableStateOf<List<CalendarEntryDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val fromMs = currentMonday.atStartOfDayIn(tz).toEpochMilliseconds()
                val sunday = currentMonday.plus(7, DateTimeUnit.DAY)
                val toMs = sunday.atStartOfDayIn(tz).toEpochMilliseconds()
                entries = repository.scheduledTasks.calendarEntries(fromMs, toMs)
            } catch (e: Exception) {
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentMonday) { load() }

    val today = remember(currentMonday) {
        Clock.System.now().toLocalDateTime(tz).date
    }

    // Group entries by day
    val entriesByDay = remember(entries, currentMonday, tz) {
        val days = (0..6).map { currentMonday.plus(it, DateTimeUnit.DAY) }
        val map = days.associateWith { mutableListOf<CalendarEntryDto>() }
        for (entry in entries) {
            val entryDate = kotlinx.datetime.Instant.fromEpochMilliseconds(entry.startEpochMs)
                .toLocalDateTime(tz).date
            // Overdue items show on today
            val displayDate = if (entry.isOverdue && today in days) today else entryDate
            map[displayDate]?.add(entry) ?: map[days.last()]?.add(entry)
        }
        map
    }

    val weekLabel = remember(currentMonday) {
        val sunday = currentMonday.plus(6, DateTimeUnit.DAY)
        "${currentMonday.dayOfMonth}.${currentMonday.monthNumber}. – ${sunday.dayOfMonth}.${sunday.monthNumber}.${sunday.year}"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with week navigation
        JTopBar(
            title = "Kalendář",
            onBack = onBack,
            actions = { RefreshIconButton(onClick = { load() }) },
        )

        // Week navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = JervisSpacing.outerPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { currentMonday = currentMonday.minus(7, DateTimeUnit.DAY) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Předchozí týden")
            }

            TextButton(onClick = {
                currentMonday = run {
                    val t = Clock.System.now().toLocalDateTime(tz).date
                    t.minus(t.dayOfWeek.ordinal, DateTimeUnit.DAY)
                }
            }) {
                Text(weekLabel, style = MaterialTheme.typography.titleMedium)
            }

            IconButton(
                onClick = { currentMonday = currentMonday.plus(7, DateTimeUnit.DAY) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Další týden")
            }
        }

        // Content
        when {
            isLoading && entries.isEmpty() -> JCenteredLoading()
            error != null -> JErrorState(message = error!!, onRetry = { load() })
            else -> {
                // Calendar grid — scrollable column of day rows
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = JervisSpacing.outerPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val days = (0..6).map { currentMonday.plus(it, DateTimeUnit.DAY) }
                    items(days) { day ->
                        val dayEntries = entriesByDay[day] ?: emptyList()
                        val isToday = day == today
                        CalendarDayRow(
                            date = day,
                            entries = dayEntries,
                            isToday = isToday,
                        )
                    }
                }
            }
        }
    }
}

private val DAY_NAMES = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

@Composable
private fun CalendarDayRow(
    date: LocalDate,
    entries: List<CalendarEntryDto>,
    isToday: Boolean,
) {
    val dayName = DAY_NAMES.getOrElse(date.dayOfWeek.ordinal) { "?" }
    val dateLabel = "${date.dayOfMonth}.${date.monthNumber}."
    val bgColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(8.dp))
            .border(
                width = if (isToday) 1.dp else 0.5.dp,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        // Day header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$dayName $dateLabel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (entries.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "(${entries.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Entries
        if (entries.isEmpty()) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        } else {
            Spacer(Modifier.height(4.dp))
            entries.forEach { entry ->
                CalendarEntryCard(entry)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CalendarEntryCard(entry: CalendarEntryDto) {
    val tz = remember { TimeZone.currentSystemDefault() }
    val timeStr = remember(entry.startEpochMs) {
        val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(entry.startEpochMs)
            .toLocalDateTime(tz)
        "%02d:%02d".format(dt.hour, dt.minute)
    }

    val (typeLabel, typeColor) = when (entry.entryType) {
        CalendarEntryType.CALENDAR_EVENT -> "Meeting" to Color(0xFF1976D2)
        CalendarEntryType.SCHEDULED_TASK -> "Plan" to Color(0xFF7B1FA2)
        CalendarEntryType.DEADLINE_TASK -> "Deadline" to Color(0xFFF57C00)
    }

    val stateColor = when (entry.state) {
        TaskStateEnum.DONE -> Color(0xFF388E3C)
        TaskStateEnum.ERROR -> Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Time
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(40.dp),
        )

        // Overdue indicator
        if (entry.isOverdue) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Zpožděno",
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }

        // Type badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(typeColor.copy(alpha = 0.15f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = typeColor,
            )
        }

        Spacer(Modifier.width(6.dp))

        // Title
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Client/project
        if (entry.clientName != null) {
            Text(
                text = entry.clientName!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
