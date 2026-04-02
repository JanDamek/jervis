package com.jervis.proactive

import com.jervis.chat.ChatRpcImpl
import com.jervis.finance.FinancialService
import com.jervis.finance.FinancialStatus
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.timetracking.TimeTrackingService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val logger = KotlinLogging.logger {}

/**
 * Proactive communication service — generates briefings, alerts, and summaries.
 *
 * Called by internal REST API from Python orchestrator's scheduled tasks:
 * - Morning briefing (daily 7:00)
 * - Invoice overdue check (daily 9:00)
 * - Weekly summary (Monday 8:00)
 *
 * Results are pushed as BACKGROUND chat messages + push notifications for urgent items.
 */
@Service
class ProactiveScheduler(
    private val chatRpcImpl: ChatRpcImpl,
    private val financialService: FinancialService,
    private val timeTrackingService: TimeTrackingService,
    private val apnsPushService: ApnsPushService,
    private val fcmPushService: FcmPushService,
) {

    /**
     * Morning briefing — summary of today's priorities.
     * Called daily at 7:00 by scheduled task.
     */
    suspend fun generateMorningBriefing(clientId: String): String {
        val lines = mutableListOf<String>()
        lines.add("# Ranní přehled")
        lines.add("")

        // Overdue invoices
        try {
            val summary = financialService.getClientSummary(clientId, null, null)
            if (summary.overdueInvoices > 0) {
                lines.add("**Po splatnosti:** ${summary.overdueInvoices} faktur (${formatCzk(summary.overdueAmount)})")
            }
            if (summary.outstandingInvoices > 0) {
                lines.add("**Neuhrazeno:** ${summary.outstandingInvoices} faktur")
            }
        } catch (e: Exception) {
            logger.debug { "Finance data not available for briefing: ${e.message}" }
        }

        // This week's time
        try {
            val now = LocalDate.now()
            val weekStart = now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val timeSummary = timeTrackingService.getTimeSummary("jan", weekStart, now)
            lines.add("**Tento týden:** ${String.format("%.1f", timeSummary.totalHours)}h odpracováno")
        } catch (e: Exception) {
            logger.debug { "Time data not available for briefing: ${e.message}" }
        }

        // Capacity
        try {
            val capacity = timeTrackingService.getCapacitySnapshot()
            lines.add("**Volná kapacita:** ${String.format("%.0f", capacity.availableHours)}h/týden")
        } catch (e: Exception) {
            logger.debug { "Capacity data not available: ${e.message}" }
        }

        val briefing = lines.joinToString("\n")

        // Push as background chat message
        chatRpcImpl.pushBackgroundResult(
            taskTitle = "Ranní přehled",
            summary = briefing,
            success = true,
            clientId = clientId,
        )

        return briefing
    }

    /**
     * Check for overdue invoices and send alerts.
     */
    suspend fun checkOverdueInvoices(): Int {
        val overdue = financialService.detectOverdueInvoices()
        if (overdue.isNotEmpty()) {
            val alertText = "**${overdue.size} faktur po splatnosti!** Celkem: ${formatCzk(overdue.sumOf { it.amountCzk })}"

            // Push notification for urgent alerts
            overdue.groupBy { it.clientId }.forEach { (clientId, invoices) ->
                val title = "Faktury po splatnosti"
                val body = "${invoices.size} faktur, celkem ${formatCzk(invoices.sumOf { it.amountCzk })}"
                try {
                    apnsPushService.sendPushNotification(clientId, title, body)
                    fcmPushService.sendPushNotification(clientId, title, body)
                } catch (e: Exception) {
                    logger.debug { "Push notification failed: ${e.message}" }
                }
            }

            // Background chat message
            chatRpcImpl.pushBackgroundResult(
                taskTitle = "Kontrola faktur",
                summary = alertText,
                success = true,
            )
        }

        return overdue.size
    }

    /**
     * Weekly summary — time, finance, task completion.
     * Called Monday at 8:00.
     */
    suspend fun generateWeeklySummary(clientId: String): String {
        val lines = mutableListOf<String>()
        lines.add("# Týdenní souhrn")
        lines.add("")

        val now = LocalDate.now()
        val weekStart = now.minusDays(7)

        // Time summary
        try {
            val timeSummary = timeTrackingService.getTimeSummary("jan", weekStart, now)
            lines.add("## Odpracovaný čas")
            lines.add("- Celkem: ${String.format("%.1f", timeSummary.totalHours)}h")
            lines.add("- Fakturovatelný: ${String.format("%.1f", timeSummary.billableHours)}h")
            timeSummary.byClient.forEach { (cid, hours) ->
                lines.add("  - $cid: ${String.format("%.1f", hours)}h")
            }
        } catch (e: Exception) {
            logger.debug { "Time data not available: ${e.message}" }
        }

        // Financial summary
        try {
            val finSummary = financialService.getClientSummary(clientId, weekStart, now)
            lines.add("")
            lines.add("## Finance")
            lines.add("- Příjmy: ${formatCzk(finSummary.totalIncome)}")
            lines.add("- Výdaje: ${formatCzk(finSummary.totalExpenses)}")
            if (finSummary.overdueInvoices > 0) {
                lines.add("- **Po splatnosti: ${finSummary.overdueInvoices} (${formatCzk(finSummary.overdueAmount)})**")
            }
        } catch (e: Exception) {
            logger.debug { "Finance data not available: ${e.message}" }
        }

        val summary = lines.joinToString("\n")

        chatRpcImpl.pushBackgroundResult(
            taskTitle = "Týdenní souhrn",
            summary = summary,
            success = true,
            clientId = clientId,
        )

        return summary
    }

    /**
     * Send VIP email alert — immediate push notification for important senders.
     */
    suspend fun sendVipEmailAlert(
        clientId: String,
        senderName: String,
        subject: String,
    ) {
        val title = "VIP email: $senderName"
        val body = subject.take(100)

        try {
            apnsPushService.sendPushNotification(clientId, title, body, mapOf("type" to "vip_email"))
            fcmPushService.sendPushNotification(clientId, title, body, mapOf("type" to "vip_email"))
        } catch (e: Exception) {
            logger.warn { "VIP push notification failed: ${e.message}" }
        }

        chatRpcImpl.pushBackgroundResult(
            taskTitle = "VIP Email",
            summary = "**$senderName:** $subject",
            success = true,
            clientId = clientId,
        )
    }

    private fun formatCzk(amount: Double): String {
        return "${String.format("%,.0f", amount).replace(',', ' ')} CZK"
    }
}
