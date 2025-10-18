package com.jervis.service.debug

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Debug window for displaying LLM queries with real-time streaming responses.
 * Implements proper Swing UI with split panes for prompts and streaming response.
 */
class DebugWindowFrame : JFrame("LLM Debug Window") {
    private val systemPromptArea = JTextArea(10, 50)
    private val userPromptArea = JTextArea(10, 50)
    private val responseArea = JTextArea(20, 50)
    private val infoArea = JTextArea(3, 50)

    private var currentSessionId: String? = null

    init {
        setupUI()
        setupLayout()
        setupWindow()
    }

    private fun setupUI() {
        // System prompt area
        systemPromptArea.apply {
            isEditable = false
            background = Color(240, 240, 240)
            border = BorderFactory.createTitledBorder("System Prompt")
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }

        // User prompt area
        userPromptArea.apply {
            isEditable = false
            background = Color(245, 245, 245)
            border = BorderFactory.createTitledBorder("User Prompt")
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }

        // Response area for streaming
        responseArea.apply {
            isEditable = false
            background = Color.WHITE
            border = BorderFactory.createTitledBorder("Streaming Response")
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }

        // Info area for metadata
        infoArea.apply {
            isEditable = false
            background = Color(250, 250, 250)
            border = BorderFactory.createTitledBorder("Session Info")
            font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            lineWrap = true
            wrapStyleWord = true
        }
    }

    private fun setupLayout() {
        layout = BorderLayout()

        // Top panel with info
        val topPanel = JPanel(BorderLayout())
        topPanel.add(JScrollPane(infoArea), BorderLayout.CENTER)
        topPanel.preferredSize = Dimension(800, 80)

        // Left panel with prompts
        val promptsPanel = JPanel(GridLayout(2, 1, 5, 5))
        promptsPanel.add(JScrollPane(systemPromptArea))
        promptsPanel.add(JScrollPane(userPromptArea))

        // Right panel with response
        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(JScrollPane(responseArea), BorderLayout.CENTER)

        // Main split pane
        val mainSplitPane =
            JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                promptsPanel,
                responsePanel,
            )
        mainSplitPane.dividerLocation = 600
        mainSplitPane.resizeWeight = 0.6

        // Add components to frame
        add(topPanel, BorderLayout.NORTH)
        add(mainSplitPane, BorderLayout.CENTER)
    }

    private fun setupWindow() {
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(1200, 800)
        setLocationRelativeTo(null) // Center on screen

        // Set minimum size to prevent UI issues
        minimumSize = Dimension(800, 600)
    }

    /**
     * Display a new debug session in the window
     */
    fun displaySession(session: DebugSession) {
        SwingUtilities.invokeLater {
            currentSessionId = session.id

            // Update prompts
            systemPromptArea.text = session.systemPrompt
            userPromptArea.text = session.userPrompt

            // Clear response area for new session
            responseArea.text = ""

            // Update info area
            updateSessionInfo(session)

            // Update window title
            title = "LLM Debug Window - ${session.promptType}"
        }
    }

    /**
     * Append streaming response chunk
     */
    fun appendResponse(chunk: String) {
        SwingUtilities.invokeLater {
            responseArea.append(chunk)
            // Auto-scroll to bottom
            responseArea.caretPosition = responseArea.document.length
        }
    }

    /**
     * Mark session as completed and show final stats
     */
    fun completeSession(sessionId: String) {
        SwingUtilities.invokeLater {
            if (currentSessionId == sessionId) {
                val completionInfo =
                    buildString {
                        appendLine()
                        appendLine("=" * 50)
                        appendLine("SESSION COMPLETED")
                        appendLine("=" * 50)
                    }

                responseArea.append(completionInfo)
                responseArea.caretPosition = responseArea.document.length

                title = "$title - COMPLETED"
            }
        }
    }

    /**
     * Clear all content (useful for new session)
     */
    fun clearContent() {
        SwingUtilities.invokeLater {
            systemPromptArea.text = ""
            userPromptArea.text = ""
            responseArea.text = ""
            infoArea.text = ""
            currentSessionId = null
            title = "LLM Debug Window"
        }
    }

    private fun updateSessionInfo(session: DebugSession) {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val info =
            buildString {
                appendLine("Session ID: ${session.id}")
                appendLine("Prompt Type: ${session.promptType}")
                appendLine("Started: ${session.startTime.format(formatter)}")
                if (session.isCompleted()) {
                    appendLine("Completed: ${session.completionTime?.format(formatter) ?: "Unknown"}")
                } else {
                    append("Status: STREAMING...")
                }
            }
        infoArea.text = info
    }

    private operator fun String.times(times: Int): String = this.repeat(times)
}
