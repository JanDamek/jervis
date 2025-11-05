package com.jervis.ui.window

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.service.IClientService
import com.jervis.service.IUserTaskService
import com.jervis.ui.component.ApplicationWindowManager
import com.jervis.ui.style.UiDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

/**
 * Window that displays active user tasks for a selected client.
 * Includes a Quick Actions placeholder bar and Refresh.
 */
class UserTasksWindow(
    private val userTaskService: IUserTaskService,
    private val clientService: IClientService,
    private val agentOrchestrator: com.jervis.service.IAgentOrchestratorService,
    private val windowManager: ApplicationWindowManager,
) : JFrame("User Tasks") {
    private val refreshButton = JButton("Refresh")
    private val revokeButton = JButton("Revoke")
    private val proceedButton = JButton("Proceed")
    private val filterField = JTextField(20)

    private val instructionArea = JTextArea(9, 40)

    private val tasksTableModel = UserTasksTableModel(emptyList())
    private val tasksTable = JTable(tasksTableModel)

    private val detailsArea =
        JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

    private val scope = CoroutineScope(Dispatchers.Main)

    private var allTasks: List<UserTaskDto> = emptyList()

    private fun selectedTask(): UserTaskDto? {
        val row = tasksTable.selectedRow
        if (row < 0) return null
        return tasksTableModel.getTaskAt(row)
    }

    private fun updateDetailsFromSelection() {
        val task = selectedTask()
        if (task == null) {
            detailsArea.text = ""
            return
        }
        val sb = StringBuilder()
        sb.appendLine("Title: ${task.title}")
        sb.appendLine("Priority: ${task.priority}")
        sb.appendLine("Status: ${task.status}")
        task.dueDateEpochMillis?.let {
            sb.appendLine("Due: ${Instant.ofEpochMilli(it)}")
        }
        val projectText = task.projectId ?: "-"
        sb.appendLine("Project: $projectText")
        sb.appendLine("Source: ${task.sourceType}")
        sb.appendLine()
        if (!task.description.isNullOrBlank()) {
            sb.appendLine(task.description)
        }
        detailsArea.text = sb.toString()
    }

    private fun updateProceedEnabled() {
        val hasInstruction = instructionArea.text.trim().isNotEmpty()
        val hasSelection = tasksTable.selectedRow >= 0
        proceedButton.isEnabled = hasInstruction && hasSelection
    }

    private fun handleProceed() {
        val instruction = instructionArea.text.trim()
        val task = selectedTask()
        if (instruction.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter instruction text.",
                "Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        if (task == null) {
            JOptionPane.showMessageDialog(this, "Please select a user task.", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        val projectId = task.projectId
        if (projectId.isNullOrBlank()) {
            JOptionPane.showMessageDialog(
                this,
                "Selected task has no project assigned.",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        val clientId = task.clientId
        val ctx =
            ChatRequestContextDto(
                clientId = clientId,
                projectId = projectId,
                autoScope = false,
                quick = false,
                existingContextId = null,
            )
        val message =
            buildString {
                appendLine(instruction)
                appendLine()
                appendLine("Continue using the following user-task content:")
                appendLine("Title: ${task.title}")
                if (!task.description.isNullOrBlank()) {
                    appendLine(task.description!!)
                }
            }
        scope.launch(Dispatchers.IO) {
            runCatching {
                agentOrchestrator.handle(
                    ChatRequestDto(
                        text = message,
                        context = ctx,
                        wsSessionId = windowManager.getNotificationsSessionId(),
                    ),
                )
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    JOptionPane.showMessageDialog(
                        this@UserTasksWindow,
                        "Instruction sent to agent orchestrator.",
                        "Info",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    JOptionPane.showMessageDialog(
                        this@UserTasksWindow,
                        "Failed to send: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        minimumSize = Dimension(1100, 700)
        size = Dimension(1280, 900)
        layout = BorderLayout(10, 10)
        rootPane.border =
            EmptyBorder(UiDesign.outerMargin, UiDesign.outerMargin, UiDesign.outerMargin, UiDesign.outerMargin)

        // Header
        val header = UiDesign.headerLabel("User Tasks")

        // Top toolbar with filter and actions
        val topBar =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                val left =
                    JPanel(FlowLayout(FlowLayout.LEFT, UiDesign.gap, 0)).apply {
                        add(JLabel("Filter:"))
                        add(filterField)
                    }
                val right = UiDesign.actionBar(refreshButton, revokeButton)
                add(left, BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }
        val northPanel =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                add(header, BorderLayout.NORTH)
                add(topBar, BorderLayout.SOUTH)
            }

        // Quick actions bar with instruction input
        val instructionPanel =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                add(JLabel("Instruction:"), BorderLayout.WEST)
                add(JScrollPane(instructionArea), BorderLayout.CENTER)
            }
        val qaPanel =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                add(UiDesign.subHeaderLabel("Quick Actions"), BorderLayout.NORTH)
                add(instructionPanel, BorderLayout.CENTER)
                add(UiDesign.actionBar(proceedButton, refreshButton), BorderLayout.EAST)
            }

        // Table area + details
        tasksTable.fillsViewportHeight = true
        tasksTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        val tableScroll = JScrollPane(tasksTable)
        val detailsSection = UiDesign.sectionPanel("Task Content", JScrollPane(detailsArea))
        val splitPane =
            JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailsSection).apply {
                setOneTouchExpandable(true)
                setContinuousLayout(true)
                setResizeWeight(0.6)
                setDividerLocation(0.6)
                setDividerSize(10)
            }

        val content = JPanel(BorderLayout(UiDesign.gap, UiDesign.gap))
        content.add(northPanel, BorderLayout.NORTH)
        content.add(splitPane, BorderLayout.CENTER)
        content.add(UiDesign.sectionPanel(null, qaPanel), BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)

        // Handlers
        refreshButton.addActionListener { refreshTasks() }
        proceedButton.addActionListener { handleProceed() }
        revokeButton.addActionListener { handleRevoke() }
        filterField.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                private fun update() {
                    applyFilterAndUpdate()
                }

                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = update()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = update()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = update()
            },
        )
        instructionArea.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                private fun update() {
                    updateProceedEnabled()
                }

                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = update()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = update()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = update()
            },
        )
        tasksTable.selectionModel.addListSelectionListener { _ ->
            updateDetailsFromSelection()
            updateProceedEnabled()
        }

        // Initial load
        refreshTasks()
    }

    private fun handleRevoke() {
        val task =
            selectedTask() ?: run {
                JOptionPane.showMessageDialog(this, "Select a task to revoke.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Discard selected task?",
                "Confirm Revoke",
                JOptionPane.OK_CANCEL_OPTION,
            )
        if (confirm != JOptionPane.OK_OPTION) return
        scope.launch(Dispatchers.IO) {
            runCatching { userTaskService.cancel(task.id) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        // No success dialog per guidelines; just refresh the list
                        refreshTasks()
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        JOptionPane.showMessageDialog(
                            this@UserTasksWindow,
                            "Failed to revoke: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
        }
    }

    private fun applyFilterAndUpdate() {
        val q = filterField.text.trim().lowercase()
        val filtered =
            if (q.isBlank()) {
                allTasks
            } else {
                allTasks.filter { t ->
                    t.title.lowercase().contains(q) ||
                        (t.description?.lowercase()?.contains(q) == true) ||
                        t.sourceType.lowercase().contains(q) ||
                        (t.projectId?.lowercase()?.contains(q) == true)
                }
            }
        tasksTableModel.update(filtered)
    }

    fun refreshTasks() {
        scope.launch {
            try {
                val tasks =
                    withContext(Dispatchers.IO) {
                        val clients = clientService.list()
                        val all = mutableListOf<UserTaskDto>()
                        for (c in clients) {
                            runCatching { userTaskService.listActive(c.id) }.onSuccess { all.addAll(it) }
                        }
                        // sort by age ascending using createdAtEpochMillis (older first)
                        all.sortedBy { it.createdAtEpochMillis }
                    }
                allTasks = tasks
                applyFilterAndUpdate()
                // Update dock badge with total active count
                windowManager.updateUserTaskBadgeForClient("")
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@UserTasksWindow,
                    "Failed to load tasks: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}

private class UserTasksTableModel(
    private var tasks: List<UserTaskDto>,
) : AbstractTableModel() {
    private val columns = arrayOf("Title", "Priority", "Status", "Due", "Project", "Source")

    fun update(newTasks: List<UserTaskDto>) {
        tasks = newTasks
        fireTableDataChanged()
    }

    fun getTaskAt(rowIndex: Int): UserTaskDto = tasks[rowIndex]

    override fun getRowCount(): Int = tasks.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        val t = tasks[rowIndex]
        return when (columnIndex) {
            0 -> t.title
            1 -> t.priority
            2 -> t.status
            3 -> t.dueDateEpochMillis?.let { formatDate(it) } ?: ""
            4 -> t.projectId ?: ""
            5 -> t.sourceType
            else -> ""
        }
    }

    private fun formatDate(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return fmt.format(instant)
    }
}
