package com.jervis.ui.window

import com.jervis.dto.ChatRequestContext
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
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
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: com.jervis.service.client.ClientService,
    private val linkService: com.jervis.service.client.ClientProjectLinkService,
) : JFrame("JERVIS Assistant") {
    private val clientSelector = JComboBox<String>(arrayOf())
    private val projectSelector = JComboBox<String>(arrayOf())
    private val quickCheckbox = JCheckBox("Quick response", false)
    private val chatArea = JTextArea()
    private val inputField = JTextField()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val clientIdByName = mutableMapOf<String, ObjectId>()
    private val projectNameById = mutableMapOf<ObjectId, String>()

    init {
        // Load clients and projects in a blocking way during initialization
        runBlocking {
            val clients = clientService.list()
            EventQueue.invokeLater {
                clientSelector.removeAllItems()
                clients.forEach { c ->
                    clientIdByName[c.name] = c.id
                    clientSelector.addItem(c.name)
                }
                if (clients.isNotEmpty()) {
                    clientSelector.selectedIndex = 0
                }
            }

            val selectedClientId = clients.firstOrNull()?.id
            val linkedProjectIds =
                selectedClientId?.let { id ->
                    linkService.listForClient(id).map { it.projectId }.toSet()
                } ?: emptySet()

            val projects = projectService.getAllProjects()
            projectNameById.clear()
            projects.forEach { p -> projectNameById[p.id] = p.name }

            val filtered =
                if (linkedProjectIds.isNotEmpty()) {
                    projects.filter { it.id in linkedProjectIds }
                } else {
                    projects.filter {
                        it.clientId ==
                            selectedClientId
                    }
                }
            val projectNames = filtered.map { it.name }.toTypedArray()
            val defaultProject = projectService.getDefaultProject()
            EventQueue.invokeLater {
                projectSelector.removeAllItems()
                projectNames.forEach { projectSelector.addItem(it) }
                val defaultName = defaultProject?.name ?: projectNames.firstOrNull()
                if (defaultName != null) {
                    projectSelector.selectedItem = defaultName
                }
            }
        }
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(500, 600)
        setLocationRelativeTo(null)
        layout = BorderLayout()

        // Top panel: client + auto-scope + project
        val topPanel = JPanel(BorderLayout())
        topPanel.border = EmptyBorder(10, 10, 10, 10)
        val row1 = JPanel()
        row1.add(JLabel("Client:"))
        row1.add(clientSelector)
        val row2 = JPanel()
        row2.add(JLabel("Project:"))
        row2.add(projectSelector)
        row2.add(quickCheckbox)
        topPanel.add(row1, BorderLayout.NORTH)
        topPanel.add(row2, BorderLayout.SOUTH)

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

        // Refresh projects when client changes
        clientSelector.addActionListener {
            refreshProjectsForSelectedClient()
        }

        // Add ESC key handling - ESC hides the window
        addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        isVisible = false // Hide the window
                    }
                }
            },
        )

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
                    // Build context from UI selections
                    val ctx =
                        ChatRequestContext(
                            clientName = clientSelector.selectedItem as? String,
                            projectName = projectSelector.selectedItem as? String,
                            autoScope = false,
                            quick = quickCheckbox.isSelected,
                        )

                    // Process the query using the ChatCoordinator
                    val response = chatCoordinator.handle(text, ctx)

                    // Update UI on the EDT
                    withContext(Dispatchers.Swing) {
                        // Remove the "Processing..." message
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")
                    }

                    if (response.requiresConfirmation) {
                        // Show confirmation dialog on EDT to let the user choose scope
                        val choice =
                            withContext(Dispatchers.Swing) {
                                showScopeConfirmationDialog(
                                    originalClient = ctx.clientName,
                                    originalProject = ctx.projectName,
                                    suggestedClient = response.detectedClient,
                                    suggestedProject = response.detectedProject,
                                    explanation = response.scopeExplanation,
                                    englishText = response.englishText,
                                )
                            }
                        // Re-call with confirmed scope
                        val confirmedCtx =
                            ChatRequestContext(
                                clientName = choice.first,
                                projectName = choice.second,
                                autoScope = false,
                                quick = ctx.quick,
                                confirmedScope = true,
                            )
                        val confirmedResponse = chatCoordinator.handle(text, confirmedCtx)

                        withContext(Dispatchers.Swing) {
                            // Apply confirmed scope to selectors
                            choice.first?.let { detectedClient ->
                                val size = clientSelector.itemCount
                                for (i in 0 until size) {
                                    if (clientSelector.getItemAt(i) == detectedClient) {
                                        clientSelector.selectedIndex = i
                                        break
                                    }
                                }
                            }
                            refreshProjectsForSelectedClient()
                            choice.second?.let { detectedProject ->
                                val size = projectSelector.itemCount
                                for (i in 0 until size) {
                                    if (projectSelector.getItemAt(i) == detectedProject) {
                                        projectSelector.selectedIndex = i
                                        break
                                    }
                                }
                            }
                            chatArea.append("Assistant: ${confirmedResponse.message}\n\n")
                            inputField.isEnabled = true
                            inputField.requestFocus()
                        }
                    } else {
                        withContext(Dispatchers.Swing) {
                            // Apply detected client/project if provided
                            response.detectedClient?.let { detectedClient ->
                                val size = clientSelector.itemCount
                                for (i in 0 until size) {
                                    if (clientSelector.getItemAt(i) == detectedClient) {
                                        clientSelector.selectedIndex = i
                                        break
                                    }
                                }
                            }
                            // Refresh projects for selected client and then select detected project if any
                            refreshProjectsForSelectedClient()
                            response.detectedProject?.let { detectedProject ->
                                val size = projectSelector.itemCount
                                for (i in 0 until size) {
                                    if (projectSelector.getItemAt(i) == detectedProject) {
                                        projectSelector.selectedIndex = i
                                        break
                                    }
                                }
                            }

                            // Add the response message
                            chatArea.append("Assistant: ${response.message}\n\n")

                            // Re-enable input and button
                            inputField.isEnabled = true
                            inputField.requestFocus()
                        }
                    }
                } catch (e: Exception) {
                    // Handle errors
                    withContext(Dispatchers.Swing) {
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

    private fun showScopeConfirmationDialog(
        originalClient: String?,
        originalProject: String?,
        suggestedClient: String?,
        suggestedProject: String?,
        explanation: String?,
        englishText: String?,
    ): Pair<String?, String?> {
        val dialog = javax.swing.JDialog(this, "Confirm scope", true)
        dialog.layout = BorderLayout()
        val panel = JPanel()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 10, 10, 10)

        val info = JTextArea()
        info.isEditable = false
        info.lineWrap = true
        info.wrapStyleWord = true
        val reasonText = (explanation ?: "Model suggested a different scope based on the request.")
        val english = englishText?.let { "\n\nEnglish translation:\n$it" } ?: ""
        info.text = "Reason: $reasonText$english"
        val infoScroll = JScrollPane(info)
        infoScroll.preferredSize = Dimension(460, 100)
        panel.add(infoScroll)

        val clientRow = JPanel(BorderLayout())
        clientRow.add(JLabel("Client:"), BorderLayout.WEST)
        val clientCombo = JComboBox<String>()
        // populate clients from current UI selector
        for (i in 0 until clientSelector.itemCount) {
            clientCombo.addItem(clientSelector.getItemAt(i))
        }
        clientRow.add(clientCombo, BorderLayout.CENTER)
        panel.add(clientRow)

        val projectRow = JPanel(BorderLayout())
        projectRow.add(JLabel("Project:"), BorderLayout.WEST)
        val projectCombo = JComboBox<String>()
        projectRow.add(projectCombo, BorderLayout.CENTER)
        panel.add(projectRow)

        fun loadProjectsForClient(cname: String?) {
            projectCombo.removeAllItems()
            val names: List<String> =
                if (cname == null) {
                    emptyList()
                } else {
                    runBlocking {
                        val cid = clientIdByName[cname]
                        if (cid == null) {
                            emptyList()
                        } else {
                            val links = linkService.listForClient(cid)
                            val linkedIds = links.map { it.projectId }.toSet()
                            val all = projectService.getAllProjects()
                            val base = all.filter { it.clientId == cid }
                            val filtered = if (linkedIds.isEmpty()) base else base.filter { it.id in linkedIds }
                            filtered.map { it.name }
                        }
                    }
                }
            names.forEach { projectCombo.addItem(it) }
        }

        // Set initial selections
        val initialClient = suggestedClient ?: originalClient ?: (clientSelector.selectedItem as? String)
        clientCombo.selectedItem = initialClient
        loadProjectsForClient(initialClient)
        val initialProject =
            when {
                suggestedProject != null &&
                    (0 until projectCombo.itemCount).any {
                        projectCombo.getItemAt(
                            it,
                        ) == suggestedProject
                    }
                -> suggestedProject

                originalProject != null &&
                    (0 until projectCombo.itemCount).any {
                        projectCombo.getItemAt(
                            it,
                        ) == originalProject
                    }
                -> originalProject

                else -> projectCombo.getItemAt(0)?.toString()
            }
        if (initialProject != null) projectCombo.selectedItem = initialProject

        clientCombo.addActionListener {
            val selected = clientCombo.selectedItem as? String
            loadProjectsForClient(selected)
            // try to keep suggested project when switching clients
            val target = suggestedProject ?: originalProject
            if (target != null) {
                for (i in 0 until projectCombo.itemCount) {
                    if (projectCombo.getItemAt(i) == target) {
                        projectCombo.selectedIndex = i
                        break
                    }
                }
            }
        }

        val buttons = JPanel()
        val ok = JButton("OK")
        val cancel = JButton("Cancel")
        buttons.add(ok)
        buttons.add(cancel)

        var result: Pair<String?, String?> = Pair(originalClient, originalProject)
        ok.addActionListener {
            result = Pair(clientCombo.selectedItem as? String, projectCombo.selectedItem as? String)
            dialog.dispose()
        }
        cancel.addActionListener {
            result = Pair(originalClient, originalProject)
            dialog.dispose()
        }

        // ESC = keep original
        val escKey = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        dialog.rootPane.registerKeyboardAction(
            {
                result = Pair(originalClient, originalProject)
                dialog.dispose()
            },
            escKey,
            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        // ENTER = accept current selection (defaults to suggested)
        dialog.rootPane.defaultButton = ok

        dialog.add(panel, BorderLayout.CENTER)
        dialog.add(buttons, BorderLayout.SOUTH)
        dialog.setSize(500, 320)
        dialog.setLocationRelativeTo(this)
        dialog.isResizable = true
        dialog.isVisible = true
        return result
    }

    private fun refreshProjectsForSelectedClient() {
        val clientName = clientSelector.selectedItem as? String ?: return
        val clientId = clientIdByName[clientName] ?: return
        coroutineScope.launch {
            try {
                val links = linkService.listForClient(clientId)
                val linkedIds = links.map { it.projectId }.toSet()
                val projects = projectService.getAllProjects()
                val filtered =
                    if (linkedIds.isEmpty()) projects.filter { it.clientId == clientId } else projects.filter { it.id in linkedIds }
                val names = filtered.map { it.name }
                val defaultProject = projectService.getDefaultProject()
                val previous = projectSelector.selectedItem as? String
                val preferred =
                    when {
                        previous != null && names.contains(previous) -> previous
                        defaultProject?.name != null && names.contains(defaultProject.name) -> defaultProject.name
                        names.isNotEmpty() -> names.first()
                        else -> null
                    }
                withContext(Dispatchers.Swing) {
                    projectSelector.removeAllItems()
                    names.forEach { projectSelector.addItem(it) }
                    if (preferred != null) {
                        projectSelector.selectedItem = preferred
                    }
                }
            } catch (_: Exception) {
                // ignore UI refresh errors
            }
        }
    }
}
