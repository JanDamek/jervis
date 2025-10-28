package com.jervis.service.debug

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Debug window for displaying LLM queries with real-time streaming responses.
 * Supports multiple sessions via tabbed interface, grouped by client.
 */
class DebugWindowFrame : JFrame("LLM Debug Window") {
    private val tabbedPane = JTabbedPane()
    private val sessionTabs = ConcurrentHashMap<String, SessionTabPanel>()

    init {
        setupUI()
        setupLayout()
        setupWindow()
    }

    private fun setupUI() {
        tabbedPane.tabPlacement = JTabbedPane.TOP
        tabbedPane.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
    }

    private fun setupLayout() {
        layout = BorderLayout()
        add(tabbedPane, BorderLayout.CENTER)
    }

    private fun setupWindow() {
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(1200, 800)
        setLocationRelativeTo(null) // Center on screen

        // Set minimum size to prevent UI issues
        minimumSize = Dimension(800, 600)
    }

    /**
     * Display a new debug session in a new tab
     */
    fun displaySession(session: DebugSession) {
        SwingUtilities.invokeLater {
            val tabPanel = SessionTabPanel(session)
            sessionTabs[session.id] = tabPanel

            val tabLabel = session.getTabLabel()
            tabbedPane.addTab(tabLabel, tabPanel)

            // Add closeable tab component with X button
            val tabIndex = tabbedPane.indexOfComponent(tabPanel)
            tabbedPane.setTabComponentAt(tabIndex, createCloseableTabComponent(tabLabel, session.id))

            tabbedPane.selectedComponent = tabPanel

            updateWindowTitle()
        }
    }

    /**
     * Append streaming response chunk to specific session
     */
    fun appendResponse(
        sessionId: String,
        chunk: String,
    ) {
        SwingUtilities.invokeLater {
            sessionTabs[sessionId]?.appendResponse(chunk)
        }
    }

    /**
     * Mark session as completed
     */
    fun completeSession(sessionId: String) {
        SwingUtilities.invokeLater {
            sessionTabs[sessionId]?.let { tabPanel ->
                tabPanel.markCompleted()

                val tabIndex = tabbedPane.indexOfComponent(tabPanel)
                if (tabIndex >= 0) {
                    val session = tabPanel.session
                    val newTitle = session.getTabLabel()
                    // Update the tab component with new title
                    tabbedPane.setTabComponentAt(tabIndex, createCloseableTabComponent(newTitle, sessionId))
                }
            }

            updateWindowTitle()
        }
    }

    /**
     * Clear all sessions and tabs
     */
    fun clearAllSessions() {
        SwingUtilities.invokeLater {
            sessionTabs.clear()
            tabbedPane.removeAll()
            title = "LLM Debug Window"
        }
    }

    /**
     * Remove completed sessions older than threshold
     */
    fun cleanupOldSessions(maxAgeDays: Long = 1) {
        SwingUtilities.invokeLater {
            val cutoffTime =
                java.time.LocalDateTime
                    .now()
                    .minusDays(maxAgeDays)
            val toRemove = mutableListOf<String>()

            sessionTabs.forEach { (sessionId, tabPanel) ->
                val session = tabPanel.session
                if (session.isCompleted() && session.completionTime?.isBefore(cutoffTime) == true) {
                    toRemove.add(sessionId)
                }
            }

            toRemove.forEach { sessionId ->
                sessionTabs[sessionId]?.let { tabPanel ->
                    val tabIndex = tabbedPane.indexOfComponent(tabPanel)
                    if (tabIndex >= 0) {
                        tabbedPane.removeTabAt(tabIndex)
                    }
                }
                sessionTabs.remove(sessionId)
            }
        }
    }

    /**
     * Create a tab component with a close button
     */
    private fun createCloseableTabComponent(
        title: String,
        sessionId: String,
    ): Component {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        panel.isOpaque = false

        val label = JLabel(title)
        panel.add(label)

        val closeButton =
            JButton("×").apply {
                preferredSize = Dimension(17, 17)
                toolTipText = "Close this tab"
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusable = false
                font = Font(Font.SANS_SERIF, Font.BOLD, 16)

                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            isContentAreaFilled = true
                            background = Color(220, 220, 220)
                        }

                        override fun mouseExited(e: MouseEvent) {
                            isContentAreaFilled = false
                        }
                    },
                )

                addActionListener {
                    closeTab(sessionId)
                }
            }
        panel.add(closeButton)

        return panel
    }

    /**
     * Close a specific tab by session ID
     */
    private fun closeTab(sessionId: String) {
        SwingUtilities.invokeLater {
            sessionTabs[sessionId]?.let { tabPanel ->
                val tabIndex = tabbedPane.indexOfComponent(tabPanel)
                if (tabIndex >= 0) {
                    tabbedPane.removeTabAt(tabIndex)
                }
            }
            sessionTabs.remove(sessionId)
            updateWindowTitle()
        }
    }

    private fun updateWindowTitle() {
        val activeCount = sessionTabs.count { !it.value.session.isCompleted() }
        val totalCount = sessionTabs.size
        title = "LLM Debug Window - $activeCount active / $totalCount total sessions"
    }

    /**
     * Inner class representing a single session tab
     */
    private inner class SessionTabPanel(
        val session: DebugSession,
    ) : JPanel(BorderLayout()) {
        private val systemPromptArea = JTextArea(10, 50)
        private val userPromptArea = JTextArea(10, 50)
        private val responseArea = JTextArea(20, 50)
        private val infoArea = JTextArea(3, 50)

        init {
            setupSessionUI()
            displaySessionInfo()
        }

        private fun setupSessionUI() {
            systemPromptArea.apply {
                isEditable = false
                background = Color(240, 240, 240)
                border = BorderFactory.createTitledBorder("System Prompt")
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                text = session.systemPrompt
            }

            userPromptArea.apply {
                isEditable = false
                background = Color(245, 245, 245)
                border = BorderFactory.createTitledBorder("User Prompt")
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                text = session.userPrompt
            }

            responseArea.apply {
                isEditable = false
                background = Color.WHITE
                border = BorderFactory.createTitledBorder("Streaming Response")
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
            }

            infoArea.apply {
                isEditable = false
                background = Color(250, 250, 250)
                border = BorderFactory.createTitledBorder("Session Info")
                font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = true
            }

            val topPanel = JPanel(BorderLayout())
            topPanel.add(JScrollPane(infoArea), BorderLayout.CENTER)
            topPanel.preferredSize = Dimension(800, 100)

            val promptsPanel = JPanel(GridLayout(2, 1, 5, 5))
            promptsPanel.add(JScrollPane(systemPromptArea))
            promptsPanel.add(JScrollPane(userPromptArea))

            val responsePanel = JPanel(BorderLayout())
            responsePanel.add(JScrollPane(responseArea), BorderLayout.CENTER)

            val mainSplitPane =
                JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    promptsPanel,
                    responsePanel,
                )
            mainSplitPane.dividerLocation = 600
            mainSplitPane.resizeWeight = 0.6

            add(topPanel, BorderLayout.NORTH)
            add(mainSplitPane, BorderLayout.CENTER)
        }

        private fun displaySessionInfo() {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val info =
                buildString {
                    appendLine("Session ID: ${session.id}")
                    appendLine("Prompt Type: ${session.promptType}")
                    if (session.clientName != null) {
                        appendLine("Client: ${session.clientName} (${session.clientId})")
                    } else {
                        appendLine("Client: System Call")
                    }
                    appendLine("Started: ${session.startTime.format(formatter)}")
                    if (session.isCompleted()) {
                        appendLine("Completed: ${session.completionTime?.format(formatter) ?: "Unknown"}")
                        append("Status: COMPLETED")
                    } else {
                        append("Status: STREAMING...")
                    }
                }
            infoArea.text = info
        }

        fun appendResponse(chunk: String) {
            responseArea.append(chunk)
            responseArea.caretPosition = responseArea.document.length
        }

        fun markCompleted() {
            session.complete()
            displaySessionInfo()
            // Response is already complete from model - no need to append anything
        }
    }
}
