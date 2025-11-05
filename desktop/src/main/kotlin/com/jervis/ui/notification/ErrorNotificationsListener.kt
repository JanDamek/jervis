package com.jervis.ui.notification

import com.jervis.dto.events.ErrorNotificationEventDto
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

@Component
class ErrorNotificationsListener {
    private val logger = KotlinLogging.logger {}

    @EventListener
    fun onError(event: ErrorNotificationEventDto) {
        logger.error { "ERROR event received: ${event.message}" }
        SwingUtilities.invokeLater {
            val msg = buildString {
                appendLine(event.message)
                event.stackTrace?.let {
                    appendLine()
                    appendLine("StackTrace:")
                    append(it)
                }
                event.correlationId?.let {
                    appendLine()
                    appendLine("CorrelationId: $it")
                }
                appendLine()
                append("Timestamp: ${event.timestamp}")
            }
            JOptionPane.showMessageDialog(null, msg, "Server Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
