package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.WindowConstants

/**
 * CommunicationUserDialogTool integrates the agent with the UI to handle interactive questions and answers.
 * Produces an ASK payload awaiting user input. Parameters can contain the question.
 */
@Service
class CommunicationUserDialogTool(
    private val gateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name = PromptTypeEnum.COMMUNICATION_USER_DIALOG_TOOL

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val proposedAnswer =
            gateway
                .callLlm(
                    type = PromptTypeEnum.COMMUNICATION_USER_DIALOG_TOOL,
                    responseSchema = LlmResponseWrapper(),
                    quick = context.quick,
                    mappingValue = mapOf("taskDescription" to taskDescription),
                    outputLanguage = plan.originalLanguage.lowercase().ifBlank { "en" },
                )

        val previousOutput = plan.finalAnswer ?: plan.contextSummary
        val decisionResult =
            withContext(Dispatchers.Swing) {
                showUserAwaitDialog(
                    owner = null,
                    client = context.clientDocument,
                    project = context.projectDocument,
                    previousOutput = previousOutput,
                    questionOriginal = taskDescription,
                    questionTranslated = taskDescription,
                    proposedAnswer = proposedAnswer.result.response,
                )
            }

        val finalAnswerOriginal =
            when (decisionResult.decision) {
                UserDecision.ESC -> {
                    "Provide the best possible short answer based on the last user request. Keep it concise and helpful."
                }

                UserDecision.ENTER -> {
                    decisionResult.answerText.ifBlank { proposedAnswer.result.response }
                }

                UserDecision.EDIT -> {
                    decisionResult.answerText.ifBlank { proposedAnswer.result.response }
                }
            }

        val finalAnswerEn =
            gateway
                .callLlm(
                    type = PromptTypeEnum.PLANNING_ANALYZE_QUESTION,
                    responseSchema = LlmResponseWrapper(),
                    quick = context.quick,
                    mappingValue = mapOf("userText" to finalAnswerOriginal),
                )

        val summary =
            when (decisionResult.decision) {
                UserDecision.ESC -> "User cancelled interaction"
                UserDecision.ENTER -> "User accepted proposed answer"
                UserDecision.EDIT -> "User provided custom answer"
            }

        return ToolResult.success(
            "USER_INTERACTION",
            summary,
            finalAnswerEn.result.response,
        )
    }

    data class UserDecisionResult(
        val decision: UserDecision,
        val answerText: String = "",
    )

    enum class UserDecision {
        ENTER,
        ESC,
        EDIT,
    }

    fun showUserAwaitDialog(
        owner: java.awt.Component? = null,
        client: ClientDocument,
        project: ProjectDocument,
        previousOutput: String?,
        questionOriginal: String,
        questionTranslated: String,
        proposedAnswer: String,
    ): UserDecisionResult {
        var result = UserDecision.ESC
        var finalAnswer = proposedAnswer

        val dialog = JDialog(null as Frame?, "User Interaction", true)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.setSize(600, 480)
        dialog.setLocationRelativeTo(owner)

        val panel =
            JPanel(BorderLayout(10, 10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            }

        val header =
            StringBuilder().apply {
                appendLine("Client: ${client.name}")
                appendLine("Project: ${project.name}")
                appendLine("Original prompt: $questionOriginal")
                if (!previousOutput.isNullOrBlank()) {
                    appendLine("\nPrevious output:")
                    appendLine(previousOutput.take(500))
                }
            }

        val promptArea =
            JTextArea(header.toString()).apply {
                isEditable = false
                background = Color(245, 245, 245)
                lineWrap = true
                wrapStyleWord = true
            }

        val scrollPane =
            JScrollPane(promptArea).apply {
                preferredSize = Dimension(580, 200)
            }

        val translatedLabel = JLabel("Translated prompt:")
        val translatedArea =
            JTextArea(questionTranslated).apply {
                isEditable = false
                background = Color(250, 250, 250)
                lineWrap = true
                wrapStyleWord = true
            }
        val translatedScroll =
            JScrollPane(translatedArea).apply {
                preferredSize = Dimension(580, 80)
            }

        val translatedPanel =
            JPanel(BorderLayout(6, 6)).apply {
                add(translatedLabel, BorderLayout.NORTH)
                add(translatedScroll, BorderLayout.CENTER)
            }

        val answerField =
            JTextField(proposedAnswer).apply {
                toolTipText = "Můžete lehce upravit navrženou odpověď."
                selectAll()
            }

        val centerStack =
            JPanel(BorderLayout(10, 10)).apply {
                border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
                add(translatedPanel, BorderLayout.NORTH)
                add(answerField, BorderLayout.CENTER)
            }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val okButton = JButton("OK")
        val cancelButton = JButton("Cancel")

        okButton.addActionListener {
            result = if (answerField.text == proposedAnswer) UserDecision.ENTER else UserDecision.EDIT
            finalAnswer = answerField.text
            dialog.dispose()
        }

        cancelButton.addActionListener {
            result = UserDecision.ESC
            dialog.dispose()
        }

        dialog.rootPane.defaultButton = okButton
        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        buttonPanel.add(okButton)
        buttonPanel.add(cancelButton)

        panel.add(scrollPane, BorderLayout.NORTH)
        panel.add(centerStack, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        dialog.contentPane = panel
        dialog.isVisible = true

        return UserDecisionResult(decision = result, answerText = finalAnswer)
    }
}
