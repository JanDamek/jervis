package com.jervis.ui.window

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.dto.ChatRequestContext
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.debug.DesktopDebugWindowService
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.notification.PlanStatusChangeEvent
import com.jervis.service.notification.StepCompletionEvent
import com.jervis.service.project.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.event.EventListener
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import kotlin.collections.indexOfFirst
import kotlin.collections.sortedByDescending

class MainWindow(
    private val projectService: ProjectService,
    private val chatCoordinator: AgentOrchestratorService,
    private val clientService: com.jervis.service.client.ClientService,
    private val linkService: com.jervis.service.client.ClientProjectLinkService,
    private val taskContextService: TaskContextService,
    private val indexingMonitorService: IndexingMonitorService,
    private val applicationWindowManager: com.jervis.ui.component.ApplicationWindowManager,
    private val debugWindowService: DesktopDebugWindowService,
) : JFrame("JERVIS Assistant") {
    private val windowScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val clientSelector = JComboBox<SelectorItem>(arrayOf())
    private val projectSelector = JComboBox<SelectorItem>(arrayOf())
    private val quickCheckbox = JCheckBox("Quick response", false)
    private val showPlansCheckbox = JCheckBox("Show Plans", false)

    // Chat UI (right side)
    private val chatArea = JTextArea()
    private val inputField = JTextArea()
    private val sendButton = JButton("Send")

    // Text persistence for different contexts
    private val contextDrafts = mutableMapOf<String, String>()

    // Context list UI (left side)
    private val contextListModel = DefaultListModel<Any>()
    private val contextList = JList<Any>(contextListModel)

    // Plan display UI (right side when enabled)
    private val planListModel = DefaultListModel<Plan>()
    private val planList = JList<Plan>(planListModel)
    private val planScroll = JScrollPane(planList)

    private val stepListModel = DefaultListModel<PlanStep>()
    private val stepList = JList<PlanStep>(stepListModel)
    private val stepScroll = JScrollPane(stepList)

    // Combined panel for two lists
    private val planDisplayPanel = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val projectNameById = mutableMapOf<ObjectId, String>()

    // In-memory context storage, grouped by (client, project)
    private val contextsByScope =
        mutableMapOf<Pair<String?, String?>, MutableList<com.jervis.domain.context.TaskContext>>()

    // Project change blocking state
    private var isProjectLoading = false

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    init {
        // Setup menu bar only on non-macOS systems (macOS uses native menu bar)
        if (!WindowUtils.isMacOS) {
            setupMenuBar()
        }
        // initialize context list defaults
        contextList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        contextList.visibleRowCount = -1

        // Set opaque cell renderer to prevent text overlap issues and add status icons
        contextList.cellRenderer =
            object : javax.swing.DefaultListCellRenderer() {
                init {
                    // Ensure renderer is opaque to prevent background from painting over text
                    isOpaque = true
                }

                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    when (value) {
                        is com.jervis.domain.context.TaskContext -> {
                            val hasRunningPlans =
                                value.plans.any { it.status == com.jervis.domain.plan.PlanStatus.RUNNING }
                            val hasCompletedPlans =
                                value.plans.any {
                                    it.status == com.jervis.domain.plan.PlanStatus.COMPLETED ||
                                        it.status == com.jervis.domain.plan.PlanStatus.FINALIZED
                                }
                            val icon =
                                when {
                                    hasRunningPlans -> "⚡ "
                                    hasCompletedPlans -> "✅ "
                                    else -> ""
                                }
                            text = "$icon${value.name}"
                        }

                        else -> {
                            // Handle "New context" label and other items
                            text = value?.toString() ?: ""
                        }
                    }
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

    init {
        // Load clients and projects asynchronously during initialization
        windowScope.launch {
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
        size = Dimension(1200, 800)
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
        row2.add(showPlansCheckbox)
        topPanel.add(row1, BorderLayout.NORTH)
        topPanel.add(row2, BorderLayout.SOUTH)

        // Center area: split pane with left context list and right chat/plan display
        chatArea.isEditable = false
        chatArea.lineWrap = true
        val chatScroll = JScrollPane(chatArea)
        chatScroll.border = EmptyBorder(10, 10, 10, 10)

        val contextScroll = JScrollPane(contextList)
        contextScroll.border = EmptyBorder(10, 5, 10, 5)
        contextScroll.preferredSize = Dimension(200, 500)

        // Set up plan display panel with two lists
        planScroll.border = EmptyBorder(5, 5, 5, 5)
        stepScroll.border = EmptyBorder(5, 5, 5, 5)

        planDisplayPanel.leftComponent = planScroll
        planDisplayPanel.rightComponent = stepScroll
        planDisplayPanel.resizeWeight = 0.5
        planDisplayPanel.dividerLocation = 300

        // Set up list renderers to show meaningful text
        planList.cellRenderer =
            object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    if (value is Plan) {
                        text = "${value.originalQuestion.take(50)}... (${value.status})"
                    }
                }
            }

        stepList.cellRenderer =
            object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    if (value is PlanStep) {
                        text = "${value.stepToolName} (${value.status})"
                    }
                }
            }

        // Add selection listener to plan list to update step list
        planList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                updateStepList()
            }
        }

        // Add double-click handler for step list to show input/output
        stepList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val selectedStep = stepList.selectedValue
                        if (selectedStep != null) {
                            showStepDetails(selectedStep)
                        }
                    }
                }
            },
        )

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contextScroll, chatScroll)
        split.resizeWeight = 0.0
        split.dividerLocation = 200

        // Configure input field as multi-line text area
        inputField.lineWrap = true
        inputField.wrapStyleWord = true
        inputField.rows = 3
        inputField.border = EmptyBorder(5, 5, 5, 5)

        // Bottom panel with input wrapped in scroll pane for dynamic resizing
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = EmptyBorder(10, 10, 10, 10)

        val inputScrollPane = JScrollPane(inputField)
        inputScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        inputScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        inputScrollPane.preferredSize = Dimension(0, 80)
        bottomPanel.add(inputScrollPane, BorderLayout.CENTER)

        // Add document listener for dynamic resizing
        inputField.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = adjustInputHeight()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = adjustInputHeight()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = adjustInputHeight()

                private fun adjustInputHeight() {
                    EventQueue.invokeLater {
                        val lines = inputField.text.count { it == '\n' } + 1
                        val minLines = 3
                        val maxLines = 10
                        val actualLines = minLines.coerceAtLeast(lines.coerceAtMost(maxLines))
                        val lineHeight = inputField.getFontMetrics(inputField.font).height
                        val newHeight = actualLines * lineHeight + 20 // padding

                        if (inputScrollPane.preferredSize.height != newHeight) {
                            inputScrollPane.preferredSize = Dimension(inputScrollPane.preferredSize.width, newHeight)
                            inputScrollPane.revalidate()
                            bottomPanel.revalidate()
                            this@MainWindow.revalidate()
                        }
                    }
                }
            },
        )

        // Add key listener to handle Enter and Shift+Enter for multi-line support
        inputField.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        if (e.isShiftDown) {
                            // Shift+Enter: allow default behavior (new line)
                            // Don't consume the event - let JTextArea handle it
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
            // Block context list and send functionality during project change
            isProjectLoading = true
            contextList.isEnabled = false
            sendButton.isEnabled = false
            // Note: inputField is never disabled per requirements

            refreshContextsFromDbForCurrentSelection(selectFirst = true)
        }

        // Show Plans checkbox handler
        showPlansCheckbox.addActionListener {
            togglePlanDisplay()
        }

        // Add context menu to project selector with same options as tray icon
        setupProjectContextMenu()

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
            // Check if selected context has running plans (should only block running plans)
            val selectedContextIndex = contextList.selectedIndex
            val selectedTaskContext =
                if (selectedContextIndex >= 0) {
                    val item = contextListModel.getElementAt(selectedContextIndex)
                    item as? com.jervis.domain.context.TaskContext
                } else {
                    null
                }

            // If context has running plans, prevent sending
            if (selectedTaskContext != null && selectedTaskContext.plans.any { it.status == com.jervis.domain.plan.PlanStatus.RUNNING }) {
                chatArea.append(
                    "System: Cannot send to context with running plan. Please wait for completion or select another context.\n\n",
                )
                return
            }

            chatArea.append("Me: $text\n")
            inputField.text = ""

            // Clear draft text after sending
            val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext
            val key = contextDraftKey(selectedContext)
            contextDrafts.remove(key)

            // Disable send button while processing (input field stays enabled)
            sendButton.isEnabled = false

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
                    val selectedTaskContext =
                        if (selectedContextIndex >= 0) {
                            val item = contextListModel.getElementAt(selectedContextIndex)
                            item as? com.jervis.domain.context.TaskContext
                        } else {
                            null
                        }

                    val ctx =
                        ChatRequestContext(
                            clientId = selectedClient.id,
                            projectId = selectedProject.id,
                            autoScope = false,
                            quick = quickCheckbox.isSelected,
                            existingContextId = selectedTaskContext?.id, // Pass existing context ID if selected
                        )

                    // Process the query using the ChatCoordinator
                    val response = chatCoordinator.handle(text, ctx)

                    // Update UI on the EDT
                    withContext(Dispatchers.Swing) {
                        // Remove the "Processing..." message
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")

                        // Reset Quick response checkbox as one-time setting
                        quickCheckbox.isSelected = false

                        // Display only final assistant message; scope decisions happen in services
                        chatArea.append("Assistant: ${response.message}\n\n")

                        // Refresh contexts from database to get updated plans and newly created contexts
                        refreshContextsFromDbForCurrentSelection(selectFirst = false) {
                            // If no context was selected (new context created), select the most recent one
                            if (selectedTaskContext == null) {
                                if (contextListModel.size() > 1) {
                                    // Select the first actual context (index 1, after "New context" item)
                                    contextList.selectedIndex = 1
                                    val selectedContext = contextListModel.getElementAt(1)
                                    if (selectedContext is com.jervis.domain.context.TaskContext) {
                                        displayContextInChatArea(selectedContext)
                                        updateSendButtonState()
                                    }
                                }
                            } else {
                                // Update send button state for existing context
                                updateSendButtonState()
                            }
                        }

                        // Update send button state and focus input
                        updateSendButtonState()
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

                        // Update send button state and focus input
                        updateSendButtonState()
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
        refreshContextsFromDbForCurrentSelection(selectFirst) { }
    }

    private fun refreshContextsFromDbForCurrentSelection(
        selectFirst: Boolean = false,
        onComplete: () -> Unit,
    ) {
        val selectedClient =
            clientSelector.selectedItem as? SelectorItem ?: run {
                contextsByScope[currentScopeKey()] = mutableListOf()
                rebuildContextList(selectFirst)
                onComplete()
                return
            }
        selectedClient.name
        val clientId = selectedClient.id
        val selectedProject = projectSelector.selectedItem as? SelectorItem
        selectedProject?.name
        val projectId = selectedProject?.id

        coroutineScope.launch {
            try {
                val docs = taskContextService.listFor(clientId, projectId)
                // Use TaskContext objects directly instead of converting to custom UI objects
                withContext(Dispatchers.Swing) {
                    contextsByScope[currentScopeKey()] = docs.toMutableList()
                    rebuildContextList(selectFirst)

                    // Unblock UI after loading completes
                    isProjectLoading = false
                    contextList.isEnabled = true
                    // Note: inputField stays always enabled per requirements
                    updateSendButtonState()

                    // Call completion callback
                    onComplete()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    contextsByScope[currentScopeKey()] = mutableListOf()
                    rebuildContextList(selectFirst)

                    // Unblock UI even if loading fails
                    isProjectLoading = false
                    contextList.isEnabled = true
                    // Note: inputField stays always enabled per requirements
                    updateSendButtonState()

                    // Call completion callback even on error
                    onComplete()
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

    private fun contextsForCurrentScope(): MutableList<com.jervis.domain.context.TaskContext> =
        contextsByScope.getOrPut(currentScopeKey()) { mutableListOf() }

    /**
     * Generate a unique key for draft text storage
     */
    private fun contextDraftKey(context: com.jervis.domain.context.TaskContext?): String =
        if (context != null) {
            "${context.clientDocument.name}:${context.projectDocument.name}:${context.id}"
        } else {
            "${currentScopeKey().first}:${currentScopeKey().second}:NEW"
        }

    /**
     * Save current input text as draft for the currently selected context
     */
    private fun saveDraftText() {
        val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext
        val key = contextDraftKey(selectedContext)
        val currentText = inputField.text
        if (currentText.isNotBlank()) {
            contextDrafts[key] = currentText
        } else {
            contextDrafts.remove(key)
        }
    }

    /**
     * Restore draft text for the specified context
     */
    private fun restoreDraftText(context: com.jervis.domain.context.TaskContext?) {
        val key = contextDraftKey(context)
        val draftText = contextDrafts[key] ?: ""
        inputField.text = draftText
    }

    private fun installContextListHandlers() {
        contextList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = contextList.locationToIndex(e.point)
                    if (index >= 0) {
                        // Save current draft text before switching contexts
                        saveDraftText()

                        when (val item = contextListModel.getElementAt(index)) {
                            is String ->
                                if (item == NEW_CONTEXT_LABEL && e.clickCount == 1) {
                                    // Clear chat area for new context - context will be created on send
                                    chatArea.text = ""
                                    // Clear selection to indicate new context mode
                                    contextList.clearSelection()
                                    // Restore draft text for new context
                                    restoreDraftText(null)
                                }

                            is com.jervis.domain.context.TaskContext -> {
                                if (e.clickCount == 1) {
                                    // Single click - display conversation in chat area
                                    displayContextInChatArea(item)
                                    updateSendButtonState()
                                    // Restore draft text for selected context
                                    restoreDraftText(item)
                                    // Update plan list if plans are shown
                                    if (showPlansCheckbox.isSelected) {
                                        updatePlanList()
                                    }
                                } else if (e.clickCount == 2) {
                                    // Double click - also display conversation (same behavior)
                                    displayContextInChatArea(item)
                                    updateSendButtonState()
                                    // Restore draft text for selected context
                                    restoreDraftText(item)
                                    if (showPlansCheckbox.isSelected) {
                                        updatePlanList()
                                    }
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
                            if (item is com.jervis.domain.context.TaskContext) {
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

    private fun promptRename(ctx: com.jervis.domain.context.TaskContext) {
        val newName = JOptionPane.showInputDialog(this, "Context name:", ctx.name)
        if (newName != null && newName.isNotBlank()) {
            ctx.name = newName.trim()
            ctx.updatedAt = Instant.now()

            // Save the updated context to database
            coroutineScope.launch {
                try {
                    taskContextService.save(ctx)
                } catch (e: Exception) {
                    // Handle save error silently or log
                }
            }
            rebuildContextList()
        }
    }

    private fun promptDelete(ctx: com.jervis.domain.context.TaskContext) {
        val result =
            JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete context '${ctx.name}'?\nThis will also delete all associated plans and steps.",
                "Delete Context",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )

        if (result == JOptionPane.YES_OPTION) {
            coroutineScope.launch {
                try {
                    taskContextService.delete(ctx.id)
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

    private fun rebuildContextList(selectFirst: Boolean = false) {
        val items = mutableListOf<Any>()
        items.add(NEW_CONTEXT_LABEL)
        val contexts = contextsForCurrentScope().sortedByDescending { it.updatedAt }
        items.addAll(contexts)
        EventQueue.invokeLater {
            contextListModel.removeAllElements()
            items.forEach { contextListModel.addElement(it) }
            if (selectFirst) {
                contextList.selectedIndex = 0
            }
        }
    }

    private fun createAndSelectNewContext(defaultNameFromText: String? = null): com.jervis.domain.context.TaskContext {
        val key = currentScopeKey()
        val name = (defaultNameFromText?.take(40)?.ifBlank { null }) ?: "New context"

        // Create TaskContext using TaskContextService
        val selectedClient = clientSelector.selectedItem as? SelectorItem
        val selectedProject = projectSelector.selectedItem as? SelectorItem

        val ctx =
            com.jervis.domain.context.TaskContext(
                id =
                    ObjectId
                        .get(),
                clientDocument =
                    com.jervis.entity.mongo.ClientDocument(
                        id =
                            selectedClient?.id ?: ObjectId
                                .get(),
                        name = key.first ?: "Unknown Client",
                    ),
                projectDocument =
                    com.jervis.entity.mongo.ProjectDocument(
                        id =
                            selectedProject?.id ?: ObjectId
                                .get(),
                        clientId = GLOBAL_ID,
                        name = key.second ?: "Unknown Project",
                    ),
                name = name,
                plans = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                quick = false,
            )

        val list = contextsByScope.getOrPut(key) { mutableListOf() }
        list.add(ctx)
        rebuildContextList()
        // select just-created context (index 1: under the NEW item)
        EventQueue.invokeLater {
            for (i in 0 until contextListModel.size()) {
                val item = contextListModel.getElementAt(i)
                if (item is com.jervis.domain.context.TaskContext && item.id == ctx.id) {
                    contextList.selectedIndex = i
                    break
                }
            }
        }
        return ctx
    }

    private fun ensureSelectedContextForCurrentScope(defaultNameFromText: String?): com.jervis.domain.context.TaskContext {
        val selected = contextList.selectedValue
        return if (selected is com.jervis.domain.context.TaskContext) {
            selected
        } else {
            createAndSelectNewContext(
                defaultNameFromText,
            )
        }
    }

    private fun displayContextInChatArea(ctx: com.jervis.domain.context.TaskContext) {
        val sb = StringBuilder()

        if (ctx.plans.isEmpty()) {
            sb.appendLine("No conversation history in this context yet.")
        } else {
            ctx.plans.sortedBy { it.createdAt }.forEach { plan ->
                sb.appendLine("Me: ${plan.originalQuestion}")

                // Extract the assistant's response from finalAnswer, removing duplicate question text
                plan.finalAnswer?.let { answer ->
                    val cleanAnswer =
                        if (answer.startsWith("Question: ${plan.originalQuestion}")) {
                            // Remove the duplicate question part and "Answer:" prefix if present
                            val withoutQuestion = answer.substringAfter("Question: ${plan.originalQuestion}").trim()
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

    private fun updateSendButtonState() {
        val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext
        val hasRunningPlans =
            selectedContext?.plans?.any { it.status == com.jervis.domain.plan.PlanStatus.RUNNING } == true

        // Enable send button if:
        // 1. No context is selected (New context mode) - always allow new context creation
        // 2. Context is selected but has no running plans
        sendButton.isEnabled = selectedContext == null || !hasRunningPlans
    }

    private fun togglePlanDisplay() {
        val split = contentPane.getComponent(1) as JSplitPane
        if (showPlansCheckbox.isSelected) {
            // Show plan display with two lists
            split.rightComponent = planDisplayPanel
            updatePlanList()
        } else {
            // Show chat area
            split.rightComponent =
                JScrollPane(chatArea).apply {
                    border = EmptyBorder(10, 10, 10, 10)
                }
        }
        split.revalidate()
        split.repaint()
    }

    private fun updatePlanList() {
        // Clear existing data
        planListModel.clear()

        // Get selected context
        val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext

        if (selectedContext != null && selectedContext.plans.isNotEmpty()) {
            selectedContext.plans.sortedBy { it.createdAt }.forEach { plan ->
                planListModel.addElement(plan)
            }
        }

        // Clear step list when plan list is updated
        stepListModel.clear()
    }

    private fun updateStepList() {
        // Clear existing step data
        stepListModel.clear()

        // Get selected plan
        val selectedPlan = planList.selectedValue

        if (selectedPlan != null && selectedPlan.steps.isNotEmpty()) {
            selectedPlan.steps.sortedBy { it.order }.forEach { step ->
                stepListModel.addElement(step)
            }
        }
    }

    private fun showStepDetails(step: PlanStep) {
        val dialog = JDialog(this, "Step Details: ${step.stepToolName}", true)
        dialog.size = Dimension(600, 400)
        dialog.setLocationRelativeTo(this)

        val content = JPanel(BorderLayout())

        // Step information
        val stepInfo = StringBuilder()
        stepInfo.appendLine("Step Name: ${step.stepToolName}")
        stepInfo.appendLine("Task Description: ${step.stepInstruction}")
        stepInfo.appendLine("Status: ${step.status}")
        stepInfo.appendLine("Order: ${step.order}")
        stepInfo.appendLine()

        if (step.toolResult != null) {
            stepInfo.appendLine("Output:")
            stepInfo.appendLine(step.toolResult!!.output)
        } else {
            stepInfo.appendLine("No output available for this step")
        }

        val textArea = JTextArea(stepInfo.toString())
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = true

        content.add(JScrollPane(textArea), BorderLayout.CENTER)

        val closeButton = JButton("Close")
        closeButton.addActionListener { dialog.dispose() }
        val buttonPanel = JPanel()
        buttonPanel.add(closeButton)
        content.add(buttonPanel, BorderLayout.SOUTH)

        // Add ESC key handling to close dialog
        dialog.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        dialog.dispose()
                    }
                }
            },
        )

        // Make sure the dialog can receive key events
        dialog.isFocusable = true

        dialog.contentPane = content
        dialog.isVisible = true
    }

    private fun showPlanDetails(row: Int) {
        val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext ?: return

        if (selectedContext.plans.isEmpty() || row >= selectedContext.plans.size) return

        val plan = selectedContext.plans.sortedBy { it.createdAt }[row]

        val dialog = JDialog(this, "Plan Details: ${plan.originalQuestion.take(50)}...", true)
        dialog.size = Dimension(600, 400)
        dialog.setLocationRelativeTo(this)

        val content = JPanel(BorderLayout())

        // Plan information
        val planInfo = StringBuilder()
        planInfo.appendLine("Original Question: ${plan.originalQuestion}")
        planInfo.appendLine("English Question: ${plan.englishQuestion}")
        planInfo.appendLine("Status: ${plan.status}")
        planInfo.appendLine("Created: ${plan.createdAt}")
        planInfo.appendLine("Updated: ${plan.updatedAt}")
        planInfo.appendLine()

        if (plan.contextSummary != null) {
            planInfo.appendLine("Context Summary:")
            planInfo.appendLine(plan.contextSummary)
            planInfo.appendLine()
        }

        if (plan.finalAnswer != null) {
            planInfo.appendLine("Final Answer:")
            planInfo.appendLine(plan.finalAnswer)
            planInfo.appendLine()
        }

        planInfo.appendLine("Steps:")
        if (plan.steps.isEmpty()) {
            planInfo.appendLine("No steps available")
        } else {
            plan.steps.sortedBy { it.order }.forEach { step ->
                planInfo.appendLine("- ${step.stepToolName} (${step.status}): ${step.stepInstruction}")
                step.toolResult?.let { output ->
                    planInfo.appendLine("  Output: ${output.output.take(200)}...")
                }
            }
        }

        val textArea = JTextArea(planInfo.toString())
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = true

        content.add(JScrollPane(textArea), BorderLayout.CENTER)

        val closeButton = JButton("Close")
        closeButton.addActionListener { dialog.dispose() }
        val buttonPanel = JPanel()
        buttonPanel.add(closeButton)
        content.add(buttonPanel, BorderLayout.SOUTH)

        // Add ESC key handling to close dialog
        dialog.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        dialog.dispose()
                    }
                }
            },
        )

        // Make sure the dialog can receive key events
        dialog.isFocusable = true

        dialog.contentPane = content
        dialog.isVisible = true
    }

    @EventListener
    fun handleStepCompletion(event: StepCompletionEvent) {
        // Update plan display if it's currently shown and the event is for the selected context
        val selectedContext = contextList.selectedValue as? com.jervis.domain.context.TaskContext
        if (selectedContext != null && selectedContext.id == event.contextId && showPlansCheckbox.isSelected) {
            coroutineScope.launch {
                // Refresh context from database to get updated data
                try {
                    val updatedContext = taskContextService.findById(event.contextId)
                    if (updatedContext != null) {
                        withContext(Dispatchers.Swing) {
                            // Update the context in our local storage
                            val currentScope = currentScopeKey()
                            val contextList = contextsByScope[currentScope]
                            val index = contextList?.indexOfFirst { it.id == updatedContext.id }
                            if (index != null && index >= 0) {
                                contextList[index] = updatedContext
                                updatePlanList()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Handle error silently or log
                }
            }
        }
    }

    private fun setupMenuBar() {
        val menuBar = JMenuBar()

        // File menu
        val fileMenu = JMenu("File")
        val exitItem = JMenuItem("Exit")
        exitItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.META_DOWN_MASK)
        exitItem.addActionListener { System.exit(0) }
        fileMenu.add(exitItem)

        // Edit menu
        val editMenu = JMenu("Edit")
        // Standard edit menu items can be added here if needed

        // Tools menu
        val toolsMenu = JMenu("Tools")

        // Indexing Monitor
        val indexingMonitorItem = JMenuItem("Indexing Monitor")
        indexingMonitorItem.accelerator =
            KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        indexingMonitorItem.addActionListener { showIndexingMonitor() }
        toolsMenu.add(indexingMonitorItem)

        // Debug Window
        val debugWindowItem = JMenuItem("Show Debug Window")
        debugWindowItem.accelerator =
            KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        debugWindowItem.addActionListener { showDebugWindow() }
        toolsMenu.add(debugWindowItem)

        toolsMenu.addSeparator()

        // Additional tools can be added here as needed

        // Window menu
        val windowMenu = JMenu("Window")
        val minimizeItem = JMenuItem("Minimize")
        minimizeItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.META_DOWN_MASK)
        minimizeItem.addActionListener { extendedState = ICONIFIED }
        windowMenu.add(minimizeItem)

        // Help menu
        val helpMenu = JMenu("Help")
        val aboutItem = JMenuItem("About JERVIS")
        aboutItem.addActionListener { showAboutDialog() }
        helpMenu.add(aboutItem)

        menuBar.add(fileMenu)
        menuBar.add(editMenu)
        menuBar.add(toolsMenu)
        menuBar.add(windowMenu)
        menuBar.add(helpMenu)

        jMenuBar = menuBar
    }

    private fun showAboutDialog() {
        JOptionPane.showMessageDialog(
            this,
            "JERVIS Assistant\nVersion 1.0\nAI-Powered Development Assistant",
            "About JERVIS",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    fun showIndexingMonitor() {
        try {
            val monitorWindow = IndexingMonitorWindow(indexingMonitorService, this)
            monitorWindow.isVisible = true
        } catch (e: Exception) {
            logger.error(e) { "Failed to open indexing monitor window" }
            JOptionPane.showMessageDialog(
                this,
                "Failed to open indexing monitor: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun showDebugWindow() {
        try {
            debugWindowService.showDebugWindow()
            logger.info { "Debug window opened" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to open debug window" }
            JOptionPane.showMessageDialog(
                this,
                "Failed to open debug window: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    @EventListener
    fun handlePlanStatusChange(event: PlanStatusChangeEvent) {
        // Update context display and UI state whenever plan status changes
        coroutineScope.launch {
            // Refresh context from database to get updated data
            try {
                val updatedContext = taskContextService.findById(event.contextId)
                if (updatedContext != null) {
                    withContext(Dispatchers.Swing) {
                        // Update the context in our local storage
                        val currentScope = currentScopeKey()
                        val contextList = contextsByScope[currentScope]
                        val index = contextList?.indexOfFirst { it.id == updatedContext.id }
                        if (index != null && index >= 0) {
                            contextList[index] = updatedContext
                            // Rebuild context list to update status icons
                            rebuildContextList()
                            // Update send button state
                            updateSendButtonState()
                            // Update plan list if it's currently shown
                            if (showPlansCheckbox.isSelected) {
                                updatePlanList()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in handlePlanStatusChange" }
            }
        }
    }

    /**
     * Sets up the context menu for the project selector with the same options as tray icon
     */
    private fun setupProjectContextMenu() {
        val contextMenu = JPopupMenu()

        val projectSettingsItem = JMenuItem("Project Settings")
        projectSettingsItem.addActionListener {
            applicationWindowManager.showProjectSettingWindow()
        }

        val clientManagementItem = JMenuItem("Client Management")
        clientManagementItem.addActionListener {
            applicationWindowManager.showClientsWindow()
        }

        val schedulerItem = JMenuItem("Scheduler")
        schedulerItem.addActionListener {
            applicationWindowManager.showSchedulerWindow()
        }

        val indexingMonitorItem = JMenuItem("Indexing Monitor")
        indexingMonitorItem.addActionListener {
            applicationWindowManager.showIndexingMonitor()
        }

        contextMenu.add(projectSettingsItem)
        contextMenu.add(clientManagementItem)
        contextMenu.add(schedulerItem)
        contextMenu.add(indexingMonitorItem)

        // Add mouse listener to project selector for right-click context menu
        projectSelector.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        contextMenu.show(e.component, e.x, e.y)
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        contextMenu.show(e.component, e.x, e.y)
                    }
                }
            },
        )
    }
}
