package com.jervis.desktop

import com.jervis.dto.events.JervisEvent
import com.jervis.dto.events.UserDialogResponseEventDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Desktop UI manager for interactive User Dialogs received over kRPC event stream.
 * Ensures a single dialog instance. When one device closes/responds, all close.
 */
class UserDialogManager(
    private val repository: JervisRepository,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var activeDialogId: String? = null

    @Volatile
    private var dialog: JDialog? = null

    fun showRequest(req: JervisEvent.UserDialogRequest) {
        // Avoid multiple dialogs: if already showing same dialog, bring to front
        if (activeDialogId == req.dialogId && dialog?.isShowing == true) {
            SwingUtilities.invokeLater { dialog?.toFront() }
            return
        }

        // If some dialog is open but different ID, close it first
        if (dialog?.isShowing == true && activeDialogId != req.dialogId) {
            closeActiveDialog()
        }

        activeDialogId = req.dialogId

        SwingUtilities.invokeLater {
            val d = JDialog().apply {
                title = "User confirmation required"
                isModal = true
                setSize(640, 420)
                setLocationRelativeTo(null)
            }

            val content = JPanel(BorderLayout(10, 10))

            val questionArea = JTextArea(req.question).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            content.add(JLabel("Question:"), BorderLayout.NORTH)
            content.add(JScrollPane(questionArea).apply { preferredSize = Dimension(620, 180) }, BorderLayout.CENTER)

            val answerField = JTextField(req.proposedAnswer ?: "").apply {
                isEditable = true
            }
            val south = JPanel(BorderLayout(8, 8))
            south.add(JLabel("Answer:"), BorderLayout.NORTH)
            south.add(answerField, BorderLayout.CENTER)

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
            val ok = JButton("OK")
            val cancel = JButton("Cancel")
            buttons.add(ok)
            buttons.add(cancel)
            south.add(buttons, BorderLayout.SOUTH)

            content.add(south, BorderLayout.SOUTH)
            d.contentPane = content

            ok.addActionListener {
                ok.isEnabled = false
                cancel.isEnabled = false
                val answerText = answerField.text
                scope.launch(Dispatchers.IO) {
                    repository.connection.sendDialogResponse(
                        UserDialogResponseEventDto(
                            dialogId = req.dialogId,
                            correlationId = req.correlationId,
                            answer = answerText,
                            accepted = true,
                            timestamp = java.time.Instant.now().toString(),
                        ),
                    )
                }
                // Close locally; other devices will receive CLOSE broadcast and close as well
                d.dispose()
                dialog = null
            }

            cancel.addActionListener {
                ok.isEnabled = false
                cancel.isEnabled = false
                scope.launch(Dispatchers.IO) {
                    repository.connection.closeDialog(
                        clientId = req.clientId,
                        dialogId = req.dialogId,
                        correlationId = req.correlationId,
                        reason = "CANCELLED",
                    )
                }
                d.dispose()
                dialog = null
            }

            dialog = d
            d.isVisible = true
        }
    }

    fun closeIfMatches(dialogId: String) {
        if (activeDialogId == dialogId) {
            closeActiveDialog()
        }
    }

    private fun closeActiveDialog() {
        SwingUtilities.invokeLater {
            dialog?.let { dlg ->
                if (dlg.isShowing) {
                    dlg.dispose()
                }
            }
            dialog = null
            activeDialogId = null
        }
    }
}
