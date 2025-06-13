package com.jervis.window

import com.jervis.service.ProjectService
import java.awt.BorderLayout
import java.awt.Dimension
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
    projectService: ProjectService,
    private val chatService: com.jervis.service.ChatService
) : JFrame("JERVIS Assistant") {
    private val projectSelector = JComboBox(projectService.getAllProjects().map { project -> project.name }.toTypedArray())
    private val chatArea = JTextArea()
    private val inputField = JTextField()

    init {
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
        sendButton.addActionListener {
            val text = inputField.text.trim()
            if (text.isNotEmpty()) {
                chatArea.append("Me: $text\n")
                inputField.text = ""

                // Disable input and button while processing
                inputField.isEnabled = false
                sendButton.isEnabled = false
                chatArea.append("Assistant: Processing...\n")

                // Process the query in a background thread to keep UI responsive
                Thread {
                    try {
                        // Process the query using the ChatService
                        val response = chatService.processQuery(text)

                        // Update UI on the EDT
                        java.awt.EventQueue.invokeLater {
                            // Remove the "Processing..." message
                            val content = chatArea.text
                            chatArea.text = content.replace("Assistant: Processing...\n", "")

                            // Add the response
                            chatArea.append("Assistant: $response\n\n")

                            // Re-enable input and button
                            inputField.isEnabled = true
                            sendButton.isEnabled = true
                            inputField.requestFocus()
                        }
                    } catch (e: Exception) {
                        // Handle errors
                        java.awt.EventQueue.invokeLater {
                            // Remove the "Processing..." message
                            val content = chatArea.text
                            chatArea.text = content.replace("Assistant: Processing...\n", "")

                            // Add the error message
                            chatArea.append("Assistant: Sorry, an error occurred: ${e.message}\n\n")

                            // Re-enable input and button
                            inputField.isEnabled = true
                            sendButton.isEnabled = true
                            inputField.requestFocus()
                        }
                    }
                }.start()
            }
        }
        bottomPanel.add(sendButton, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }
}
