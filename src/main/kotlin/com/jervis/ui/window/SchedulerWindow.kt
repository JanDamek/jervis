package com.jervis.ui.window

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.dto.ChatRequestContext
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.entity.mongo.ScheduledTaskStatus
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.scheduling.TaskQueryService
import com.jervis.service.scheduling.TaskSchedulingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

/**
 * Window for managing scheduled tasks (Plánovač).
 * Allows users to create, view, and manage scheduled tasks that can execute any available MCP tool.
 */
class SchedulerWindow(
    private val taskSchedulingService: TaskSchedulingService,
    private val taskQueryService: TaskQueryService,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val agentOrchestrator: AgentOrchestratorService,
) : JFrame("Plánovač úkolů") {
    private val taskListModel = DefaultListModel<Any>()
    private val taskList = JList(taskListModel)
    private val taskDescriptionArea = JTextArea(4, 40)
    private val scheduleTimeField = JTextField(20)
    private val repeatableCheckBox = JCheckBox("Opakující se úkol")
    private val cronExpressionField = JTextField(20)
    private val statusLabel = JLabel("Připraveno k vytvoření úkolu")

    // Client and project selection
    private val clientComboBox = JComboBox<ClientComboItem>()
    private val projectComboBox = JComboBox<ProjectComboItem>()

    init {
        setupUI()
        setupEventHandlers()
        loadInitialData()
    }

    private fun setupUI() {
        defaultCloseOperation = HIDE_ON_CLOSE
        layout = BorderLayout()
        size = Dimension(900, 600)

        // Set location relative to screen
        setLocationRelativeTo(null)

        add(createMainPanel(), BorderLayout.CENTER)
        add(createStatusPanel(), BorderLayout.SOUTH)
    }

    private fun createMainPanel(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = EmptyBorder(10, 10, 10, 10)

        // Left panel for task input
        val inputPanel = createTaskInputPanel()

        // Right panel for task list
        val listPanel = createTaskListPanel()

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputPanel, listPanel)
        splitPane.dividerLocation = 400
        splitPane.resizeWeight = 0.45

        mainPanel.add(splitPane, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createTaskInputPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Nový úkol")

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.anchor = GridBagConstraints.WEST

        // Client selection
        gbc.gridx = 0
        gbc.gridy = 0
        formPanel.add(JLabel("Klient:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        formPanel.add(clientComboBox, gbc)

        // Project selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        formPanel.add(JLabel("Projekt:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        formPanel.add(projectComboBox, gbc)

        // Task description
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        formPanel.add(JLabel("Popis úkolu:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        taskDescriptionArea.lineWrap = true
        taskDescriptionArea.wrapStyleWord = true
        taskDescriptionArea.border = BorderFactory.createLoweredBevelBorder()
        formPanel.add(JScrollPane(taskDescriptionArea), gbc)

        // Schedule time
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        formPanel.add(JLabel("Čas spuštění (volitelné):"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        scheduleTimeField.toolTipText =
            "Formáty: dd.MM.yyyy HH:mm, dd.MM.yyyy (9:00), 'zítra', 'dnes', 'za X hodin/dní' nebo prázdné pro okamžité spuštění"
        scheduleTimeField.text =
            "např. ${LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}"
        formPanel.add(scheduleTimeField, gbc)

        // Repeatable task checkbox
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.gridwidth = 2
        formPanel.add(repeatableCheckBox, gbc)

        // Cron expression field
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.gridwidth = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        formPanel.add(JLabel("Cron výraz:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        cronExpressionField.toolTipText = "Např.: '0 9 * * 1' = každé pondělí v 9:00"
        cronExpressionField.isEnabled = false
        formPanel.add(cronExpressionField, gbc)

        panel.add(formPanel, BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel(FlowLayout())
        val validateButton = JButton("Ověřit úkol")
        val scheduleButton = JButton("Naplánovat úkol")
        val scheduleNowButton = JButton("Spustit nyní")

        validateButton.addActionListener { validateTask() }
        scheduleButton.addActionListener { scheduleTask() }
        scheduleNowButton.addActionListener { executeTaskNow() }

        buttonPanel.add(validateButton)
        buttonPanel.add(scheduleButton)
        buttonPanel.add(scheduleNowButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createTaskListPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Naplánované úkoly")

        // Task list
        taskList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        taskList.cellRenderer = TaskListCellRenderer()
        panel.add(JScrollPane(taskList), BorderLayout.CENTER)

        // List buttons
        val buttonPanel = JPanel(FlowLayout())
        val refreshButton = JButton("Obnovit")
        val cancelButton = JButton("Zrušit úkol")
        val viewDetailsButton = JButton("Detail úkolu")

        refreshButton.addActionListener { loadTasks() }
        cancelButton.addActionListener { cancelSelectedTask() }
        viewDetailsButton.addActionListener { showTaskDetails() }

        buttonPanel.add(refreshButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(viewDetailsButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createStatusPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.add(statusLabel)
        return panel
    }

    private fun setupEventHandlers() {
        // Client selection changes project list
        clientComboBox.addActionListener {
            val selectedClient = clientComboBox.selectedItem as? ClientComboItem
            selectedClient?.let { loadProjectsForClient(it.id) }
        }

        // Repeatable checkbox enables/disables cron expression field
        repeatableCheckBox.addActionListener {
            cronExpressionField.isEnabled = repeatableCheckBox.isSelected
            if (!repeatableCheckBox.isSelected) {
                cronExpressionField.text = ""
            }
        }

        // Task list selection
        taskList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                // Enable/disable buttons based on selection
                taskList.selectedValue != null
                // Update button states here if needed
            }
        }

        // Double-click to run task
        taskList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        executeSelectedTask()
                    }
                }
            },
        )
    }

    private fun loadInitialData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clients = clientService.list()
                withContext(Dispatchers.Main) {
                    clientComboBox.removeAllItems()
                    clients.forEach { client ->
                        clientComboBox.addItem(ClientComboItem(client.id, client.name))
                    }

                    // Load projects for first client if available
                    if (clients.isNotEmpty()) {
                        loadProjectsForClient(clients.first().id)
                    }

                    // Load existing tasks
                    loadTasks()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při načítání dat: ${e.message}"
                }
            }
        }
    }

    private fun loadProjectsForClient(clientId: ObjectId) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allProjects = projectService.getAllProjects()
                val projects = allProjects.filter { it.clientId == clientId }
                withContext(Dispatchers.Main) {
                    projectComboBox.removeAllItems()
                    projects.forEach { project ->
                        projectComboBox.addItem(ProjectComboItem(project.id, project.name))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při načítání projektů: ${e.message}"
                }
            }
        }
    }

    private fun loadTasks() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks =
                    taskQueryService.getTasksByStatus(ScheduledTaskStatus.PENDING) +
                        taskQueryService.getTasksByStatus(ScheduledTaskStatus.RUNNING) +
                        taskQueryService
                            .getTasksByStatus(ScheduledTaskStatus.COMPLETED)
                            .takeLast(10) // Show last 10 completed

                // Load projects and clients to enhance display
                val projects = projectService.getAllProjects()
                val clients = clientService.list()

                // Create enhanced task info
                val enhancedTasks =
                    tasks.map { task ->
                        val project = projects.find { it.id == task.projectId }
                        val client = project?.clientId?.let { clientId -> clients.find { it.id == clientId } }
                        EnhancedTaskInfo(task, project?.name ?: "Neznámý projekt", client?.name)
                    }

                withContext(Dispatchers.Main) {
                    taskListModel.clear()
                    enhancedTasks.sortedByDescending { it.task.scheduledAt }.forEach { enhancedTask ->
                        taskListModel.addElement(enhancedTask)
                    }
                    statusLabel.text = "Načteno ${tasks.size} úkolů"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při načítání úkolů: ${e.message}"
                }
            }
        }
    }

    private fun validateTask() {
        val description = taskDescriptionArea.text.trim()
        if (description.isEmpty()) {
            statusLabel.text = "Zadejte popis úkolu"
            return
        }

        statusLabel.text = "Ověřuji úkol..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Basic task validation - check if description is meaningful
                val isValidTask = description.length >= 10 && description.contains(" ")
                
                withContext(Dispatchers.Main) {
                    if (isValidTask) {
                        statusLabel.text = "Úkol byl ověřen a je připraven k naplánování"
                    } else {
                        statusLabel.text = "Úkol je příliš krátký nebo nejasný - zadejte detailnější popis"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při ověřování úkolu: ${e.message}"
                }
            }
        }
    }

    private fun scheduleTask() {
        val selectedProject = projectComboBox.selectedItem as? ProjectComboItem
        val selectedClient = clientComboBox.selectedItem as? ClientComboItem
        val description = taskDescriptionArea.text.trim()

        // Ensure either project or client is selected (requirement)
        if (selectedProject == null && selectedClient == null) {
            statusLabel.text = "Musíte vybrat alespoň projekt nebo klienta"
            return
        }

        if (selectedProject == null) {
            statusLabel.text = "Vyberte projekt"
            return
        }

        if (description.isEmpty()) {
            statusLabel.text = "Zadejte popis úkolu"
            return
        }

        // Validate cron expression if repeatable is selected
        val cronExpression =
            if (repeatableCheckBox.isSelected) {
                val cron = cronExpressionField.text.trim()
                if (cron.isEmpty()) {
                    statusLabel.text = "Zadejte cron výraz pro opakující se úkol"
                    return
                }
                cron
            } else {
                null
            }

        val scheduledTime =
            parseScheduledTime(scheduleTimeField.text.trim())
                ?: Instant.now().plusSeconds(10) // Default to 10 seconds from now

        statusLabel.text = "Vytvářím úkol..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                taskSchedulingService.scheduleTask(
                    projectId = selectedProject.id,
                    taskInstruction = description,
                    taskName = "Task: ${description.take(50)}${if (description.length > 50) "..." else ""}",
                    scheduledAt = scheduledTime,
                    taskParameters = emptyMap(),
                    priority = 0,
                    maxRetries = 3,
                    cronExpression = cronExpression,
                    createdBy = "user",
                )

                withContext(Dispatchers.Main) {
                    statusLabel.text = "Úkol byl úspěšně naplánován"
                    taskDescriptionArea.text = ""
                    scheduleTimeField.text = ""
                    repeatableCheckBox.isSelected = false
                    cronExpressionField.text = ""
                    cronExpressionField.isEnabled = false
                    loadTasks()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při vytváření úkolu: ${e.message}"
                }
            }
        }
    }

    private fun executeTaskNow() {
        val selectedProject = projectComboBox.selectedItem as? ProjectComboItem
        val selectedClient = clientComboBox.selectedItem as? ClientComboItem
        val description = taskDescriptionArea.text.trim()

        if (selectedProject == null || selectedClient == null) {
            statusLabel.text = "Vyberte klienta a projekt"
            return
        }

        if (description.isEmpty()) {
            statusLabel.text = "Zadejte popis úkolu"
            return
        }

        statusLabel.text = "Spouštím úkol..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create ChatRequestContext for immediate execution
                val chatRequestContext =
                    ChatRequestContext(
                        clientId = selectedClient.id,
                        projectId = selectedProject.id,
                        quick = false, // Default to full processing
                        existingContextId = null, // Always create new context
                    )

                // Execute task immediately through AgentOrchestratorService
                val response = agentOrchestrator.handle(description, chatRequestContext)

                withContext(Dispatchers.Main) {
                    statusLabel.text = "Úkol byl úspěšně dokončen"
                    taskDescriptionArea.text = ""

                    // Show result in a dialog
                    val resultDialog = JDialog(this@SchedulerWindow, "Výsledek úkolu", true)
                    resultDialog.layout = BorderLayout()
                    resultDialog.size = Dimension(600, 400)
                    resultDialog.setLocationRelativeTo(this@SchedulerWindow)

                    val textArea = JTextArea(response.message)
                    textArea.isEditable = false
                    textArea.lineWrap = true
                    textArea.wrapStyleWord = true

                    resultDialog.add(JScrollPane(textArea), BorderLayout.CENTER)

                    val closeButton = JButton("Zavřít")
                    closeButton.addActionListener { resultDialog.dispose() }
                    val buttonPanel = JPanel(FlowLayout())
                    buttonPanel.add(closeButton)
                    resultDialog.add(buttonPanel, BorderLayout.SOUTH)

                    resultDialog.isVisible = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při spouštění úkolu: ${e.message}"
                }
            }
        }
    }

    private fun cancelSelectedTask() {
        val selectedValue = taskList.selectedValue
        if (selectedValue == null) {
            statusLabel.text = "Vyberte úkol ke zrušení"
            return
        }

        val task =
            when (selectedValue) {
                is EnhancedTaskInfo -> selectedValue.task
                is ScheduledTaskDocument -> selectedValue
                else -> {
                    statusLabel.text = "Neplatný typ úkolu"
                    return
                }
            }

        val result =
            JOptionPane.showConfirmDialog(
                this,
                "Opravdu chcete zrušit úkol '${task.taskName}'?",
                "Potvrdit zrušení",
                JOptionPane.YES_NO_OPTION,
            )

        if (result == JOptionPane.YES_OPTION) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cancelled = taskSchedulingService.cancelTask(task.id)
                    withContext(Dispatchers.Main) {
                        if (cancelled) {
                            statusLabel.text = "Úkol byl zrušen"
                            loadTasks()
                        } else {
                            statusLabel.text = "Úkol se nepodařilo zrušit"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusLabel.text = "Chyba při rušení úkolu: ${e.message}"
                    }
                }
            }
        }
    }

    private fun showTaskDetails() {
        val selectedValue = taskList.selectedValue
        if (selectedValue == null) {
            statusLabel.text = "Vyberte úkol pro zobrazení detailů"
            return
        }

        val task =
            when (selectedValue) {
                is EnhancedTaskInfo -> selectedValue.task
                is ScheduledTaskDocument -> selectedValue
                else -> {
                    statusLabel.text = "Neplatný typ úkolu"
                    return
                }
            }

        val detailsDialog = TaskDetailsDialog(this, task)
        detailsDialog.isVisible = true
    }

    private fun executeSelectedTask() {
        val selectedValue = taskList.selectedValue
        if (selectedValue == null) {
            statusLabel.text = "Vyberte úkol pro spuštění"
            return
        }

        val task =
            when (selectedValue) {
                is EnhancedTaskInfo -> selectedValue.task
                is ScheduledTaskDocument -> selectedValue
                else -> {
                    statusLabel.text = "Neplatný typ úkolu"
                    return
                }
            }

        // Can only execute pending tasks
        if (task.status != ScheduledTaskStatus.PENDING) {
            statusLabel.text = "Lze spustit pouze čekající úkoly"
            return
        }

        statusLabel.text = "Spouštím vybraný úkol..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get project and client information
                val project = projectService.getAllProjects().find { it.id == task.projectId }
                if (project == null) {
                    withContext(Dispatchers.Main) {
                        statusLabel.text = "Projekt nebyl nalezen"
                    }
                    return@launch
                }

                val clientId = project.clientId
                if (clientId == GLOBAL_ID) {
                    withContext(Dispatchers.Main) {
                        statusLabel.text = "Projekt nemá přiřazeného klienta"
                    }
                    return@launch
                }

                // Create ChatRequestContext for immediate execution
                val chatRequestContext =
                    ChatRequestContext(
                        clientId = clientId,
                        projectId = project.id,
                        quick = false, // Default to full processing
                        existingContextId = null, // Always create new context
                    )

                // Execute task immediately through AgentOrchestratorService
                val response = agentOrchestrator.handle(task.taskInstruction, chatRequestContext)

                withContext(Dispatchers.Main) {
                    statusLabel.text = "Vybraný úkol byl úspěšně dokončen"

                    // Show result in a dialog
                    val resultDialog = JDialog(this@SchedulerWindow, "Výsledek úkolu: ${task.taskName}", true)
                    resultDialog.layout = BorderLayout()
                    resultDialog.size = Dimension(700, 500)
                    resultDialog.setLocationRelativeTo(this@SchedulerWindow)

                    val textArea = JTextArea(response.message)
                    textArea.isEditable = false
                    textArea.lineWrap = true
                    textArea.wrapStyleWord = true

                    resultDialog.add(JScrollPane(textArea), BorderLayout.CENTER)

                    val closeButton = JButton("Zavřít")
                    closeButton.addActionListener { resultDialog.dispose() }
                    val buttonPanel = JPanel(FlowLayout())
                    buttonPanel.add(closeButton)
                    resultDialog.add(buttonPanel, BorderLayout.SOUTH)

                    resultDialog.isVisible = true

                    // Refresh task list to show updated status
                    loadTasks()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Chyba při spouštění úkolu: ${e.message}"
                }
            }
        }
    }

    private fun parseScheduledTime(timeString: String): Instant? {
        if (timeString.isEmpty()) return null

        val input = timeString.lowercase().trim()

        return try {
            when {
                input == "nyní" || input == "hned" -> Instant.now()
                input == "dnes" ->
                    LocalDateTime
                        .now()
                        .withHour(9)
                        .withMinute(0)
                        .withSecond(0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()

                input == "zítra" ->
                    LocalDateTime
                        .now()
                        .plusDays(1)
                        .withHour(9)
                        .withMinute(0)
                        .withSecond(0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()

                input.startsWith("za ") -> {
                    val parts = input.substringAfter("za ").split(" ")
                    if (parts.size >= 2) {
                        val amount = parts[0].toLongOrNull() ?: return null
                        val unit = parts[1].lowercase()
                        when {
                            unit.startsWith("minut") -> Instant.now().plusSeconds(amount * 60)
                            unit.startsWith("hodin") -> Instant.now().plusSeconds(amount * 3600)
                            unit.startsWith("den") || unit.startsWith("dní") ->
                                Instant
                                    .now()
                                    .plusSeconds(amount * 86400)

                            unit.startsWith("týden") -> Instant.now().plusSeconds(amount * 604800)
                            else -> null
                        }
                    } else {
                        null
                    }
                }

                input.contains(":") -> {
                    // Try full datetime format
                    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    val localDateTime = LocalDateTime.parse(timeString, formatter)
                    localDateTime.atZone(ZoneId.systemDefault()).toInstant()
                }

                input.matches(Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) -> {
                    // Date only format - default to 9 AM
                    val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    val localDate = java.time.LocalDate.parse(timeString, dateFormatter)
                    localDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Data classes for combo box items
    data class ClientComboItem(
        val id: ObjectId,
        val name: String,
    ) {
        override fun toString() = name
    }

    data class ProjectComboItem(
        val id: ObjectId,
        val name: String,
    ) {
        override fun toString() = name
    }

    // Enhanced task info for better display
    data class EnhancedTaskInfo(
        val task: ScheduledTaskDocument,
        val projectName: String,
        val clientName: String?,
    )

    // Custom cell renderer for task list
    private class TaskListCellRenderer : DefaultListCellRenderer() {
        private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            when (value) {
                is EnhancedTaskInfo -> {
                    val task = value.task
                    val scheduledTime = task.scheduledAt.atZone(ZoneId.systemDefault()).format(dateFormatter)
                    val status =
                        when (task.status) {
                            ScheduledTaskStatus.PENDING -> "Čekající"
                            ScheduledTaskStatus.RUNNING -> "Běží"
                            ScheduledTaskStatus.COMPLETED -> "Dokončeno"
                            ScheduledTaskStatus.FAILED -> "Selhalo"
                            ScheduledTaskStatus.CANCELLED -> "Zrušeno"
                        }

                    val clientInfo = value.clientName?.let { "Klient: $it" } ?: ""
                    val projectInfo = "Projekt: ${value.projectName}"
                    val repeatableInfo = task.cronExpression?.let { " (Opakuje se)" } ?: ""

                    // Format: [Status] Task Name | Client: ClientName | Project: ProjectName | Time: 01.01.2024 10:00
                    val description =
                        task.taskInstruction.take(80) + if (task.taskInstruction.length > 80) "..." else ""
                    text =
                        buildString {
                            append("[$status] ")
                            append(description)
                            append(repeatableInfo)
                            appendLine()
                            if (clientInfo.isNotEmpty()) {
                                append("$clientInfo | ")
                            }
                            append("$projectInfo | ")
                            append("Termín: $scheduledTime")
                        }

                    // Color coding based on status
                    when (task.status) {
                        ScheduledTaskStatus.RUNNING -> foreground = java.awt.Color.BLUE
                        ScheduledTaskStatus.FAILED -> foreground = java.awt.Color.RED
                        ScheduledTaskStatus.CANCELLED -> foreground = java.awt.Color.GRAY
                        ScheduledTaskStatus.COMPLETED -> foreground = java.awt.Color(0, 128, 0) // Dark green
                        else -> foreground = if (isSelected) list?.selectionForeground else list?.foreground
                    }
                }

                is ScheduledTaskDocument -> {
                    // Fallback for backward compatibility
                    val scheduledTime = value.scheduledAt.atZone(ZoneId.systemDefault()).format(dateFormatter)
                    val status =
                        when (value.status) {
                            ScheduledTaskStatus.PENDING -> "Čekající"
                            ScheduledTaskStatus.RUNNING -> "Běží"
                            ScheduledTaskStatus.COMPLETED -> "Dokončeno"
                            ScheduledTaskStatus.FAILED -> "Selhalo"
                            ScheduledTaskStatus.CANCELLED -> "Zrušeno"
                        }

                    text = "${value.taskName} - $status ($scheduledTime)"

                    when (value.status) {
                        ScheduledTaskStatus.RUNNING -> foreground = java.awt.Color.BLUE
                        ScheduledTaskStatus.FAILED -> foreground = java.awt.Color.RED
                        ScheduledTaskStatus.CANCELLED -> foreground = java.awt.Color.GRAY
                        else -> foreground = if (isSelected) list?.selectionForeground else list?.foreground
                    }
                }
            }

            return this
        }
    }

    // Task details dialog
    private class TaskDetailsDialog(
        parent: JFrame,
        private val task: ScheduledTaskDocument,
    ) : JDialog(parent, "Detail úkolu", true) {
        init {
            setupUI()
        }

        private fun setupUI() {
            layout = BorderLayout()
            size = Dimension(600, 400)
            setLocationRelativeTo(parent)

            val panel = JPanel(GridBagLayout())
            panel.border = EmptyBorder(20, 20, 20, 20)

            val gbc = GridBagConstraints()
            gbc.insets = Insets(5, 5, 5, 5)
            gbc.anchor = GridBagConstraints.WEST

            val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

            // Add task details
            addRow(panel, gbc, 0, "Název:", task.taskName)
            addRow(panel, gbc, 1, "Instrukce:", task.taskInstruction)
            addRow(panel, gbc, 2, "Stav:", task.status.name)
            addRow(
                panel,
                gbc,
                3,
                "Naplánováno na:",
                task.scheduledAt.atZone(ZoneId.systemDefault()).format(dateFormatter),
            )
            addRow(panel, gbc, 4, "Vytvořeno:", task.createdAt.atZone(ZoneId.systemDefault()).format(dateFormatter))
            addRow(panel, gbc, 5, "Vytvořil:", task.createdBy)
            addRow(panel, gbc, 6, "Priorita:", task.priority.toString())
            addRow(panel, gbc, 7, "Pokusů:", "${task.retryCount}/${task.maxRetries}")

            if (task.errorMessage != null) {
                addRow(panel, gbc, 8, "Chyba:", task.errorMessage)
            }

            add(panel, BorderLayout.CENTER)

            // Close button
            val buttonPanel = JPanel(FlowLayout())
            val closeButton = JButton("Zavřít")
            closeButton.addActionListener { dispose() }
            buttonPanel.add(closeButton)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        private fun addRow(
            panel: JPanel,
            gbc: GridBagConstraints,
            row: Int,
            label: String,
            value: String,
        ) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            val valueLabel = JLabel(value)
            valueLabel.font = valueLabel.font.deriveFont(java.awt.Font.PLAIN)
            panel.add(valueLabel, gbc)
        }
    }
}
