package com.jervis.service.mcp.tools

import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
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
 * UserInteractionTool integrates the agent with the UI to handle interactive questions and answers.
 * Produces an ASK payload awaiting user input. Parameters can contain the question.
 */
@Service
class UserInteractionTool(
    private val gateway: LlmGateway,
) : McpTool {
    override val name: String = "user.await"
    override val description: String =
        "Display a blocking user dialog, show request details and collect user’s answer."

    override suspend fun execute(
        context: TaskContextDocument,
        parameters: String,
    ): ToolResult {
        val userLang = context.originalLanguage?.lowercase()?.ifBlank { null }

        val questionTranslated =
            gateway
                .callLlm(
                    ModelType.TRANSLATION,
                    parameters,
                    "Translate user prompt.",
                    userLang,
                    context.quick,
                ).answer
                .trim()

        val questionRephrased =
            runCatching {
                gateway
                    .callLlm(
                        type = ModelType.INTERNAL,
                        systemPrompt = """
                        Reformulate the following user request clearly and politely.
                        Return only the reformulated sentence.""",
                        userPrompt =
                            """
                            Request:
                            $questionTranslated
                            """.trimIndent(),
                        outputLanguage = userLang ?: "en",
                        quick = context.quick,
                    ).answer
            }.getOrElse { questionTranslated }

        val proposedAnswer =
            runCatching {
                gateway
                    .callLlm(
                        type = ModelType.INTERNAL,
                        systemPrompt = "Provide a concise, helpful answer to the following request. Keep it short and actionable.",
                        userPrompt =
                            """
                            Request:
                            $questionRephrased
                            """.trimIndent(),
                        outputLanguage = userLang ?: "en",
                        quick = context.quick,
                    ).answer
            }.getOrElse {
                if ((userLang ?: "en") == "en") "I need more details, please." else "Potřebuji více informací, prosím."
            }

        val previousOutput = context.finalResult ?: context.contextSummary
        val client = context.clientName
        val project = context.projectName

        val decisionResult =
            withContext(Dispatchers.Swing) {
                showUserAwaitDialog(
                    owner = null,
                    client = client,
                    project = project,
                    previousOutput = previousOutput,
                    questionOriginal = parameters,
                    questionTranslated = questionTranslated,
                    questionRephrased = questionRephrased,
                    proposedAnswer = proposedAnswer,
                )
            }

        val finalAnswerOriginal =
            when (decisionResult.decision) {
                UserDecision.ESC -> {
                    runCatching {
                        "Provide the best possible short answer based on the last user request. Keep it concise and helpful."
                    }.getOrElse {
                        "Proceeding with the model's decision."
                    }
                }

                UserDecision.ENTER -> {
                    decisionResult.answerText.ifBlank { proposedAnswer }
                }

                UserDecision.EDIT -> {
                    decisionResult.answerText.ifBlank { proposedAnswer }
                }
            }

        val finalAnswerEn =
            runCatching {
                gateway
                    .callLlm(
                        ModelType.TRANSLATION,
                        userPrompt = finalAnswerOriginal,
                        systemPrompt = "Translate.",
                        "en",
                        quick = context.quick,
                    ).answer
            }.getOrElse { finalAnswerOriginal }

        return ToolResult.ok(finalAnswerEn)
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
        client: String?,
        project: String?,
        previousOutput: String?,
        questionOriginal: String,
        questionTranslated: String,
        questionRephrased: String,
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
                appendLine("Client: ${client ?: "(unknown)"}")
                appendLine("Project: ${project ?: "(unknown)"}")
                appendLine("Original prompt: $questionOriginal")
                appendLine("Rephrased: $questionRephrased")
                if (!previousOutput.isNullOrBlank()) {
                    appendLine("\nPrevious output:")
                    appendLine(previousOutput.take(500))
                }
            }

        val promptArea =
            JTextArea(header.toString()).apply {
                isEditable = false
                background = Color(245, 245, 245)
                font = Font("Monospaced", Font.PLAIN, 12)
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
                font = Font("Monospaced", Font.PLAIN, 12)
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
