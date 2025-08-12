package com.jervis.ui.window

import com.jervis.service.controller.ChatService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

class MainWindow(
    private val projectService: ProjectService,
    private val chatService: ChatService,
) : JFrame("JERVIS Assistant") {
    private val projectSelector = JComboBox<String>(arrayOf())
    private val chatArea = JTextArea()
    private val inputField = JTextField()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        // Load projects in a blocking way during initialization
        runBlocking {
            val projects = projectService.getAllProjects()
            val projectNames = projects.map { project -> project.name }.toTypedArray()
            EventQueue.invokeLater {
                projectSelector.removeAllItems()
                projectNames.forEach { projectSelector.addItem(it) }
            }
        }
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(500, 600)
        setLocationRelativeTo(null)
        layout = BorderLayout()

        // Horní panel s výběrem projektu
        val topPanel = JPanel(BorderLayout())
        topPanel.border = EmptyBorder(10, 10, 10, 10)
        topPanel.add(JLabel("Active Project:"), BorderLayout.WEST)
        topPanel.add(projectSelector, BorderLayout.CENTER)

        // Střední oblast s přehledem chatu
        chatArea.isEditable = false
        chatArea.lineWrap = true
        val chatScroll = JScrollPane(chatArea)
        chatScroll.border = EmptyBorder(10, 10, 10, 10)

        // Dolní panel s inputem
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = EmptyBorder(10, 10, 10, 10)
        bottomPanel.add(inputField, BorderLayout.CENTER)
        val sendButton = JButton("Send")

        // Add key listener to handle Enter and Shift+Enter
        inputField.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        if (e.isShiftDown) {
                            // Shift+Enter: add a new line
                            inputField.text += "\n"
                            // Set caret position to the end of text
                            inputField.caretPosition = inputField.text.length
                            e.consume()
                        } else {
                            // Enter: send message
                            e.consume()
                            sendMessage()
                        }
                    }
                }
            },
        )

        // Add action listener to send button
        sendButton.addActionListener {
            sendMessage()
        }

        // Make sure the send button is disabled when the input is disabled
        inputField.addPropertyChangeListener("enabled") { event ->
            sendButton.isEnabled = event.newValue as Boolean
        }

        bottomPanel.add(sendButton, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        // Add ESC key handling - ESC hides the window
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    isVisible = false // Hide the window
                }
            }
        })
        
        // Make sure the window can receive key events
        isFocusable = true
    }

    /**
     * Sends the message from the input field to the chat service
     */
    private fun sendMessage() {
        val text = inputField.text.trim()
        if (text.isNotEmpty()) {
            chatArea.append("Me: $text\n")
            inputField.text = ""

            // Disable input while processing
            inputField.isEnabled = false

            chatArea.append("Assistant: Processing...\n")

            // Process the query in a coroutine to keep UI responsive
            coroutineScope.launch {
                try {
                    // Process the query using the ChatService
                    val response = chatService.processQuery(text)

                    // Update UI on the EDT
                    withContext(Dispatchers.Main) {
                        // Remove the "Processing..." message
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")

                        // Add the response
                        chatArea.append("Assistant: $response\n\n")

                        // Re-enable input and button
                        inputField.isEnabled = true
                        inputField.requestFocus()
                    }
                } catch (e: Exception) {
                    // Handle errors
                    withContext(Dispatchers.Main) {
                        // Remove the "Processing..." message
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")

                        // Add the error message
                        chatArea.append("Assistant: Sorry, an error occurred: ${e.message}\n\n")

                        // Re-enable input
                        inputField.isEnabled = true
                        inputField.requestFocus()
                    }
                }
            }
        }
    }
}
