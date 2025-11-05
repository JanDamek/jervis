package com.jervis.ui.window

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.PlanDto
import com.jervis.dto.PlanStepDto
import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.dto.events.PlanStatusChangeEventDto
import com.jervis.dto.events.StepCompletionEventDto
import com.jervis.dto.events.UserTaskCreatedEventDto
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IProjectService
import com.jervis.service.debug.DesktopDebugWindowService
import com.jervis.ui.component.ApplicationWindowManager
import com.jervis.ui.utils.MacOSAppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
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
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MainWindow(
    private val projectService: IProjectService,
    private val chatCoordinator: IAgentOrchestratorService,
    private val clientService: IClientService,
    private val linkService: IClientProjectLinkService,
    private val applicationWindowManager: ApplicationWindowManager,
    private val debugWindowService: DesktopDebugWindowService,
    private val notificationsClient: com.jervis.client.NotificationsWebSocketClient,
) : JFrame("JERVIS Assistant") {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val windowScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val clientSelector = JComboBox<SelectorItem>(arrayOf())
    private val projectSelector = JComboBox<SelectorItem>(arrayOf())
    private val quickCheckbox = JCheckBox("Quick response", false)
    private val showPlansCheckbox = JCheckBox("Show Plans", false)

    // Chat UI
    private val chatArea = JTextArea()
    private val inputField = JTextArea()
    private val sendButton = JButton("Send")

    // Plan display UI (shown when enabled)
    private val planListModel = DefaultListModel<PlanDto>()
    private val planList = JList<PlanDto>(planListModel)
    private val planScroll = JScrollPane(planList)

    private val stepListModel = DefaultListModel<PlanStepDto>()
    private val stepList = JList<PlanStepDto>(stepListModel)
    private val stepScroll = JScrollPane(stepList)

    // Combined panel for two lists
    private val planDisplayPanel = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val projectNameById = mutableMapOf<String, String>()

    init {
        // Setup menu bar only on non-macOS systems (macOS uses native menu bar)
        if (!WindowUtils.isMacOS) {
            setupMenuBar()
        }
    }

    fun reloadClientsAndProjects() {
        // Load clients and projects asynchronously during reload
        windowScope.launch {
            try {
                val clients = clientService.list()
                EventQueue.invokeLater {
                    clientSelector.removeAllItems()
                    clients.forEach { c -> clientSelector.addItem(SelectorItem(c.id, c.name)) }
                    if (clients.isNotEmpty()) {
                        clientSelector.selectedIndex = 0
                    }
                }

                val selectedClientId = clients.firstOrNull()?.id
                val linkedProjectIds =
                    selectedClientId?.let { id -> linkService.listForClient(id).map { it.projectId }.toSet() }
                        ?: emptySet()

                val projects = projectService.getAllProjects()
                projectNameById.clear()
                projects.forEach { p -> projectNameById[p.id] = p.name }

                val filtered =
                    if (linkedProjectIds.isNotEmpty()) {
                        projects.filter { it.id in linkedProjectIds }
                    } else {
                        projects.filter { it.clientId == selectedClientId }
                    }
                val projectItems = filtered.map { SelectorItem(it.id, it.name) }
                val defaultProject = projectService.getDefaultProject()
                EventQueue.invokeLater {
                    projectSelector.removeAllItems()
                    projectItems.forEach { projectSelector.addItem(it) }
                    val defaultItem =
                        projectItems.find { it.name == defaultProject?.name } ?: projectItems.firstOrNull()
                    if (defaultItem != null) {
                        projectSelector.selectedItem = defaultItem
                    }
                }
            } catch (_: Exception) {
                // ignore UI refresh errors during reload
            }
        }
    }

    // UI selector models
    data class SelectorItem(
        val id: String,
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

        // Center area: chat/plan display
        chatArea.isEditable = false
        chatArea.lineWrap = true
        val chatScroll = JScrollPane(chatArea)
        chatScroll.border = EmptyBorder(10, 10, 10, 10)

        // Set up plan display panel with two lists
        planScroll.border = EmptyBorder(5, 5, 5, 5)
        stepScroll.border = EmptyBorder(5, 5, 5, 5)

        planDisplayPanel.leftComponent = planScroll
        planDisplayPanel.rightComponent = stepScroll
        planDisplayPanel.resizeWeight = 0.5
        planDisplayPanel.dividerLocation = 300

        // Set up list renderers to show meaningful text
        planList.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    if (value is PlanDto) {
                        text = "${value.taskInstruction.take(50)}... (${value.status})"
                    }
                }
            }

        stepList.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    if (value is PlanStepDto) {
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
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = adjustInputHeight()

                override fun removeUpdate(e: DocumentEvent) = adjustInputHeight()

                override fun changedUpdate(e: DocumentEvent) = adjustInputHeight()

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
        add(chatScroll, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        // Refresh projects when client changes
        clientSelector.addActionListener {
            val selected = clientSelector.selectedItem as? SelectorItem
            if (selected != null) {
                applicationWindowManager.updateCurrentClientId(selected.id)
            }
            refreshProjectsForSelectedClient()
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
    }

    /**
     * Sends the message from the input field to the chat service
     */
    private fun sendMessage() {
        val text = inputField.text.trim()
        if (text.isNotEmpty()) {
            chatArea.append("Me: $text\n")
            inputField.text = ""

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

                    val ctx =
                        ChatRequestContextDto(
                            clientId = selectedClient.id,
                            projectId = selectedProject.id,
                            autoScope = false,
                            quick = quickCheckbox.isSelected,
                            existingContextId = null,
                        )

                    // Process the query using the ChatCoordinator (fire-and-forget)
                    // Response will arrive via WebSocket notifications
                    chatCoordinator.handle(ChatRequestDto(text, ctx, wsSessionId = notificationsClient.sessionId))

                    // Update UI on the EDT
                    withContext(Dispatchers.Swing) {
                        // Keep "Processing..." message visible - will be replaced by WebSocket response

                        // Reset Quick response checkbox as one-time setting
                        quickCheckbox.isSelected = false

                        // Re-enable send button and focus input
                        sendButton.isEnabled = true
                        inputField.requestFocus()
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send chat request: ${e.message}" }

                    withContext(Dispatchers.Swing) {
                        val content = chatArea.text
                        chatArea.text = content.replace("Assistant: Processing...\n", "")

                        val userMessage =
                            when {
                                e.message?.contains("Connection refused") == true ->
                                    "Sorry, could not connect to the server. Please ensure the server is running."

                                e.message?.contains("Serializer") == true ->
                                    "Sorry, there was a technical issue. Please try again or contact support."

                                else ->
                                    "Sorry, an unexpected error occurred. Please try again."
                            }

                        chatArea.append("Assistant: $userMessage\n\n")

                        sendButton.isEnabled = true
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

    private fun togglePlanDisplay() {
        if (showPlansCheckbox.isSelected) {
            // Show plan display with two lists - replace center component with plan display
            val chatScroll = contentPane.getComponent(1) as JScrollPane
            contentPane.remove(chatScroll)
            contentPane.add(planDisplayPanel, BorderLayout.CENTER)
            updatePlanList()
        } else {
            // Show chat area - replace center component with chat
            contentPane.remove(planDisplayPanel)
            val chatScroll =
                JScrollPane(chatArea).apply {
                    border = EmptyBorder(10, 10, 10, 10)
                }
            contentPane.add(chatScroll, BorderLayout.CENTER)
        }
        contentPane.revalidate()
        contentPane.repaint()
    }

    private fun updatePlanList() {
        // Clear existing data
        planListModel.clear()

        // Note: Plan list is now independent - could be populated from service if needed
        // For now, just clear it since we don't have context selection

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

    private fun showStepDetails(step: PlanStepDto) {
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
            stepInfo.appendLine(step.toolResult)
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

        val userTasksItem = JMenuItem("User Tasks")
        userTasksItem.addActionListener { applicationWindowManager.showUserTasksWindow() }
        toolsMenu.add(userTasksItem)

        val ragSearchItem = JMenuItem("RAG Search")
        ragSearchItem.addActionListener { applicationWindowManager.showRagSearchWindow() }
        toolsMenu.add(ragSearchItem)

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

        contextMenu.add(projectSettingsItem)
        contextMenu.add(clientManagementItem)
        contextMenu.add(schedulerItem)

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

    @EventListener
    fun handleStepCompletionDto(event: StepCompletionEventDto) {
        // Step completion events are logged but no longer update UI
        // since we don't maintain context list
        logger.debug { "Step completion event received for context ${event.contextId}" }
    }

    @EventListener
    fun handlePlanStatusChangeDto(event: PlanStatusChangeEventDto) {
        // Plan status change events are logged but no longer update UI
        // since we don't maintain context list
        logger.debug { "Plan status change event received for context ${event.contextId}" }
    }

    @EventListener
    fun handleErrorNotification(event: ErrorNotificationEventDto) {
        // Show error message, with optional stack trace dialog on demand
        val message = event.message
        val stack = event.stackTrace
        javax.swing.SwingUtilities.invokeLater {
            if (stack.isNullOrBlank()) {
                JOptionPane.showMessageDialog(
                    this,
                    message,
                    "Server Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            } else {
                val choice =
                    JOptionPane.showOptionDialog(
                        this,
                        message,
                        "Server Error",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.ERROR_MESSAGE,
                        null,
                        arrayOf("Close", "Show stack trace"),
                        "Show stack trace",
                    )
                if (choice == 1) {
                    val area = JTextArea(stack)
                    area.isEditable = false
                    val scroll = JScrollPane(area)
                    scroll.preferredSize = Dimension(800, 400)
                    val dialog = JDialog(this, "Stack trace", true)
                    dialog.contentPane.add(scroll)
                    dialog.pack()
                    dialog.setLocationRelativeTo(this)
                    dialog.isVisible = true
                }
            }
        }
    }

    @EventListener
    fun handleUserTaskCreated(event: UserTaskCreatedEventDto) {
        // Update dock badge count and show macOS notification
        try {
            applicationWindowManager.updateUserTaskBadgeForClient("")
            MacOSAppUtils.showSystemNotification("New User Task", event.title)
        } catch (e: Exception) {
            logger.warn(e) { "Failed processing UserTaskCreatedEvent: ${'$'}{e.message}" }
        }
    }
}
