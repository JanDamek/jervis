package com.jervis.ui.window

import com.jervis.dto.ChatRequestContext
import com.jervis.service.agent.context.TaskContextService
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

class MainWindow(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: com.jervis.service.client.ClientService,
    private val linkService: com.jervis.service.client.ClientProjectLinkService,
    private val taskContextService: TaskContextService,
) : JFrame("JERVIS Assistant") {
    private val clientSelector = JComboBox<SelectorItem>(arrayOf())
    private val projectSelector = JComboBox<SelectorItem>(arrayOf())
    private val quickCheckbox = JCheckBox("Quick response", false)

    // Chat UI (right side)
    private val chatArea = JTextArea()
    private val inputField = JTextField()

    // Context list UI (left side)
    private val contextListModel = DefaultListModel<Any>()
    private val contextList = JList<Any>(contextListModel)

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val projectNameById = mutableMapOf<ObjectId, String>()

    // In-memory context storage, grouped by (client, project)
    private val contextsByScope = mutableMapOf<Pair<String?, String?>, MutableList<UiChatContext>>()

    init {
        // initialize context list defaults
        contextList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        contextList.visibleRowCount = -1

        // Set opaque cell renderer to prevent text overlap issues
        contextList.cellRenderer =
            object : javax.swing.DefaultListCellRenderer() {
                init {
                    // Ensure renderer is opaque to prevent background from painting over text
                    isOpaque = true
                }
            }
    }

    // UI selector models
    data class SelectorItem(
        val id: ObjectId,
        val name: String,
    ) {
        override fun toString(): String = name
    }

    // UI context models
    data class UiPlan(
        val userQueryOriginal: String,
        val englishText: String?,
        val finalAnswer: String?,
        val createdAt: java.time.Instant = java.time.Instant.now(),
    )

    data class UiChatContext(
        val id: java.util.UUID = java.util.UUID.randomUUID(),
        var name: String,
        val client: String?,
        val project: String?,
        val plans: MutableList<UiPlan> = mutableListOf(),
        var lastUpdated: java.time.Instant = java.time.Instant.now(),
        val taskContextId: ObjectId? = null, // Link to actual TaskContext from database
    ) {
        override fun toString(): String = name
    }

    init {
        // Load clients and projects in a blocking way during initialization
        runBlocking {
            val clients = clientService.list()
            EventQueue.invokeLater {
                clientSelector.removeAllItems()
                clients.forEach { c ->
                    clientSelector.addItem(SelectorItem(c.id, c.name))
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
            projects.forEach { p ->
                projectNameById[p.id] = p.name
            }

            val filtered =
                if (linkedProjectIds.isNotEmpty()) {
                    projects.filter { it.id in linkedProjectIds }
                } else {
                    projects.filter {
                        it.clientId ==
                            selectedClientId
                    }
                }
            val projectItems = filtered.map { SelectorItem(it.id, it.name) }
            val defaultProject = projectService.getDefaultProject()
            EventQueue.invokeLater {
                projectSelector.removeAllItems()
                projectItems.forEach { projectSelector.addItem(it) }
                val defaultItem = projectItems.find { it.name == defaultProject?.name } ?: projectItems.firstOrNull()
                if (defaultItem != null) {
                    projectSelector.selectedItem = defaultItem
                }

                // Load contexts after both selectors are properly initialized
                refreshContextsFromDbForCurrentSelection(selectFirst = true)
            }
        }
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(900, 650)
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

        // Center area: split pane with left context list and right chat
        chatArea.isEditable = false
        chatArea.lineWrap = true
        val chatScroll = JScrollPane(chatArea)
        chatScroll.border = EmptyBorder(10, 10, 10, 10)

        val contextScroll = JScrollPane(contextList)
        contextScroll.border = EmptyBorder(10, 5, 10, 5)
        contextScroll.preferredSize = Dimension(200, 500)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contextScroll, chatScroll)
        split.resizeWeight = 0.0
        split.dividerLocation = 200

        // Bottom panel with input
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
        add(split, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        // Refresh projects when client changes
        clientSelector.addActionListener {
            refreshProjectsForSelectedClient()
            refreshContextsFromDbForCurrentSelection(selectFirst = true)
        }
        // Refresh context list when project changes
        projectSelector.addActionListener {
            refreshContextsFromDbForCurrentSelection(selectFirst = true)
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

        // Setup context list UI and populate for current scope
        installContextListHandlers()

        // Refresh contexts whenever the window is shown
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) {
                    refreshContextsFromDbForCurrentSelection(selectFirst = true)
                }
            },
        )
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
                    val selectedClient = clientSelector.selectedItem as? SelectorItem
                    val selectedProject = projectSelector.selectedItem as? SelectorItem

                    if (selectedClient == null || selectedProject == null) {
                        throw IllegalStateException("Client and project must be selected")
                    }

                    // Check if user has selected an existing context
                    val selectedContextIndex = contextList.selectedIndex
                    val selectedUiContext =
                        if (selectedContextIndex >= 0) {
                            val item = contextListModel.getElementAt(selectedContextIndex)
                            item as? UiChatContext
                        } else {
                            null
                        }

                    val ctx =
                        ChatRequestContext(
                            clientId = selectedClient.id,
                            projectId = selectedProject.id,
                            autoScope = false,
                            quick = quickCheckbox.isSelected,
                            existingContextId = selectedUiContext?.taskContextId, // Pass existing context ID if selected
                        )

                    // Process the query using the ChatCoordinator
                    val response = chatCoordinator.handle(text, ctx)

                    // Update UI on the EDT
                    withContext(Dispatchers.Swing) {
                        // Remove the "Processing..." message
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")
                    }

                    // Ensure a UI context exists (name from the first message if needed)
                    val uiCtx = withContext(Dispatchers.Swing) { ensureSelectedContextForCurrentScope(text) }
                    withContext(Dispatchers.Swing) {
                        // Display only final assistant message; scope decisions happen in services
                        chatArea.append("Assistant: ${response.message}\n\n")

                        // Store as a new plan in the selected UI context
                        uiCtx.plans.add(
                            UiPlan(
                                userQueryOriginal = text,
                                englishText = null,
                                finalAnswer = response.message,
                            ),
                        )
                        uiCtx.lastUpdated = java.time.Instant.now()
                        rebuildContextList()

                        // Re-enable input
                        inputField.isEnabled = true
                        inputField.requestFocus()
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

    private fun refreshProjectsForSelectedClient() {
        val selectedClient = clientSelector.selectedItem as? SelectorItem ?: return
        selectedClient.name
        val clientId = selectedClient.id
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
                    filtered.forEach { project ->
                        projectSelector.addItem(SelectorItem(project.id, project.name))
                    }
                    val preferredItem = filtered.find { it.name == preferred }?.let { SelectorItem(it.id, it.name) }
                    if (preferredItem != null) {
                        projectSelector.selectedItem = preferredItem
                    }
                }
            } catch (_: Exception) {
                // ignore UI refresh errors
            }
        }
    }

    private fun refreshContextsFromDbForCurrentSelection(selectFirst: Boolean = false) {
        val selectedClient =
            clientSelector.selectedItem as? SelectorItem ?: run {
                contextsByScope[currentScopeKey()] = mutableListOf()
                rebuildContextList(selectFirst)
                return
            }
        val clientName = selectedClient.name
        val clientId = selectedClient.id
        val selectedProject = projectSelector.selectedItem as? SelectorItem
        val projectName = selectedProject?.name
        val projectId = selectedProject?.id

        coroutineScope.launch {
            try {
                val docs = taskContextService.listFor(clientId, projectId)
                val mapped =
                    docs
                        .map { doc ->
                            // Convert TaskContext plans to UiPlan objects
                            val uiPlans =
                                doc.plans
                                    .map { plan ->
                                        UiPlan(
                                            userQueryOriginal = plan.originalQuestion,
                                            englishText = plan.englishQuestion.takeIf { it != plan.originalQuestion },
                                            finalAnswer = plan.finalAnswer ?: "Plan completed",
                                            createdAt = plan.createdAt,
                                        )
                                    }.toMutableList()

                            UiChatContext(
                                name =
                                    if (doc.quick) {
                                        "⚡ Quick Context"
                                    } else {
                                        "Context ${
                                            doc.id.toHexString().takeLast(6)
                                        }"
                                    },
                                client = clientName,
                                project = projectName,
                                plans = uiPlans,
                                lastUpdated = doc.updatedAt,
                                taskContextId = doc.id, // Store TaskContext ID for later retrieval
                            )
                        }.toMutableList()
                withContext(Dispatchers.Swing) {
                    contextsByScope[currentScopeKey()] = mapped
                    rebuildContextList(selectFirst)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    contextsByScope[currentScopeKey()] = mutableListOf()
                    rebuildContextList(selectFirst)
                }
            }
        }
    }

    // --- Context list helpers and tool skeletons ---
    private val NEW_CONTEXT_LABEL = "➕ New context"

    private fun currentScopeKey(): Pair<String?, String?> =
        Pair(
            (clientSelector.selectedItem as? SelectorItem)?.name,
            (projectSelector.selectedItem as? SelectorItem)?.name,
        )

    private fun contextsForCurrentScope(): MutableList<UiChatContext> = contextsByScope.getOrPut(currentScopeKey()) { mutableListOf() }

    private fun installContextListHandlers() {
        contextList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = contextList.locationToIndex(e.point)
                    if (index >= 0) {
                        when (val item = contextListModel.getElementAt(index)) {
                            is String ->
                                if (item == NEW_CONTEXT_LABEL && e.clickCount == 2) {
                                    // Clear chat area for new context
                                    chatArea.text = ""
                                    val created = createAndSelectNewContext()
                                    promptRename(created)
                                }

                            is UiChatContext -> {
                                if (e.clickCount == 1) {
                                    // Single click - display conversation in chat area
                                    displayContextInChatArea(item)
                                } else if (e.clickCount == 2) {
                                    // Double click - also display conversation (same behavior)
                                    displayContextInChatArea(item)
                                }
                            }
                        }
                    }
                }

                override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)

                override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

                private fun maybeShowPopup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        val index = contextList.locationToIndex(e.point)
                        if (index >= 0) {
                            contextList.selectedIndex = index
                            val item = contextListModel.getElementAt(index)
                            if (item is UiChatContext) {
                                val menu = JPopupMenu()
                                val renameItem = JMenuItem("Rename…")
                                val deleteItem = JMenuItem("Delete…")
                                renameItem.addActionListener {
                                    promptRename(item)
                                }
                                deleteItem.addActionListener {
                                    promptDelete(item)
                                }
                                menu.add(renameItem)
                                menu.addSeparator()
                                menu.add(deleteItem)
                                menu.show(contextList, e.x, e.y)
                            }
                        }
                    }
                }
            },
        )
    }

    private fun promptRename(ctx: UiChatContext) {
        val newName = JOptionPane.showInputDialog(this, "Context name:", ctx.name)
        if (newName != null && newName.isNotBlank()) {
            ctx.name = newName.trim()
            ctx.lastUpdated = java.time.Instant.now()

            // Only update UI - do not modify stored TaskContext content
            rebuildContextList()
        }
    }

    private fun promptDelete(ctx: UiChatContext) {
        val result =
            JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete context '${ctx.name}'?\nThis will also delete all associated plans and steps.",
                "Delete Context",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )

        if (result == JOptionPane.YES_OPTION) {
            ctx.taskContextId?.let { contextId ->
                coroutineScope.launch {
                    try {
                        taskContextService.delete(contextId)
                        withContext(Dispatchers.Swing) {
                            // Remove from local list and refresh UI
                            contextsByScope[currentScopeKey()]?.remove(ctx)
                            rebuildContextList()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Swing) {
                            JOptionPane.showMessageDialog(
                                this@MainWindow,
                                "Failed to delete context: ${e.message}",
                                "Error",
                                JOptionPane.ERROR_MESSAGE,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun rebuildContextList(selectFirst: Boolean = false) {
        val items = mutableListOf<Any>()
        items.add(NEW_CONTEXT_LABEL)
        val contexts = contextsForCurrentScope().sortedByDescending { it.lastUpdated }
        items.addAll(contexts)
        EventQueue.invokeLater {
            contextListModel.removeAllElements()
            items.forEach { contextListModel.addElement(it) }
            if (selectFirst) {
                contextList.selectedIndex = 0
            }
        }
    }

    private fun createAndSelectNewContext(defaultNameFromText: String? = null): UiChatContext {
        val key = currentScopeKey()
        val name = (defaultNameFromText?.take(40)?.ifBlank { null }) ?: "New context"
        val ctx = UiChatContext(name = name, client = key.first, project = key.second)
        val list = contextsByScope.getOrPut(key) { mutableListOf() }
        list.add(ctx)
        ctx.lastUpdated = java.time.Instant.now()
        rebuildContextList()
        // select just-created context (index 1: under the NEW item)
        EventQueue.invokeLater {
            for (i in 0 until contextListModel.size()) {
                val item = contextListModel.getElementAt(i)
                if (item is UiChatContext && item.id == ctx.id) {
                    contextList.selectedIndex = i
                    break
                }
            }
        }
        return ctx
    }

    private fun ensureSelectedContextForCurrentScope(defaultNameFromText: String?): UiChatContext {
        val selected = contextList.selectedValue
        return if (selected is UiChatContext) selected else createAndSelectNewContext(defaultNameFromText)
    }

    private fun displayContextInChatArea(ctx: UiChatContext) {
        val sb = StringBuilder()

        if (ctx.plans.isEmpty()) {
            sb.appendLine("No conversation history in this context yet.")
        } else {
            ctx.plans.sortedBy { it.createdAt }.forEach { p ->
                sb.appendLine("Me: ${p.userQueryOriginal}")

                // Extract the assistant's response from finalAnswer, removing duplicate question text
                p.finalAnswer?.let { answer ->
                    val cleanAnswer =
                        if (answer.startsWith("Question: ${p.userQueryOriginal}")) {
                            // Remove the duplicate question part and "Answer:" prefix if present
                            val withoutQuestion = answer.substringAfter("Question: ${p.userQueryOriginal}").trim()
                            if (withoutQuestion.startsWith("Answer:")) {
                                withoutQuestion.substringAfter("Answer:").trim()
                            } else {
                                withoutQuestion
                            }
                        } else {
                            answer
                        }
                    if (cleanAnswer.isNotBlank()) {
                        sb.appendLine("Assistant: $cleanAnswer")
                    }
                }
                sb.appendLine()
            }
        }

        chatArea.text = sb.toString()
        chatArea.caretPosition = 0 // Scroll to top
    }
}
