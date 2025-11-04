package com.jervis.ui.window

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

/**
 * Window that displays active user tasks for a selected client.
 * Includes a Quick Actions placeholder bar and Refresh.
 */
class UserTasksWindow(
    private val userTaskService: IUserTaskService,
    private val clientService: IClientService,
    private val windowManager: ApplicationWindowManager,
) : JFrame("User Tasks") {
    private val clientSelector = JComboBox<SelectorItem>(arrayOf())
    private val refreshButton = JButton("Refresh")

    // Quick actions placeholder (future per-task action buttons will be added here)
    private val quickActionsLabel = JLabel("Quick Actions (coming soon)")

    private val tasksTableModel = UserTasksTableModel(emptyList())
    private val tasksTable = JTable(tasksTableModel)

    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        minimumSize = Dimension(900, 480)
        layout = BorderLayout(10, 10)
        rootPane.border =
            EmptyBorder(UiDesign.outerMargin, UiDesign.outerMargin, UiDesign.outerMargin, UiDesign.outerMargin)

        // Header
        val header = UiDesign.headerLabel("User Tasks")

        // Form row: client selector
        val form =
            UiDesign.formPanel {
                row("Client:", clientSelector)
            }

        // Quick actions bar
        val qaPanel =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                add(UiDesign.subHeaderLabel("Quick Actions"), BorderLayout.WEST)
                add(quickActionsLabel, BorderLayout.CENTER)
                add(UiDesign.actionBar(refreshButton), BorderLayout.EAST)
            }

        // Table area
        tasksTable.fillsViewportHeight = true
        tasksTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        val tableScroll = JScrollPane(tasksTable)

        val content = JPanel(BorderLayout(UiDesign.gap, UiDesign.gap))
        content.add(header, BorderLayout.NORTH)
        content.add(UiDesign.sectionPanel(null, form), BorderLayout.WEST)
        content.add(tableScroll, BorderLayout.CENTER)
        content.add(UiDesign.sectionPanel(null, qaPanel), BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)

        // Handlers
        refreshButton.addActionListener { refreshTasks() }
        clientSelector.addActionListener {
            val selected = (clientSelector.selectedItem as? SelectorItem)?.id
            if (!selected.isNullOrBlank()) {
                windowManager.updateCurrentClientId(selected)
                refreshTasks()
            }
        }

        // Load clients and initial tasks
        loadClientsAndSelectFirst()
    }

    fun preselectClient(clientId: String?) {
        if (clientId == null) return
        val size = clientSelector.itemCount
        for (i in 0 until size) {
            val item = clientSelector.getItemAt(i)
            if (item.id == clientId) {
                clientSelector.selectedIndex = i
                break
            }
        }
    }

    private fun loadClientsAndSelectFirst() {
        scope.launch {
            try {
                val clients = withContext(Dispatchers.IO) { clientService.list() }
                clientSelector.removeAllItems()
                clients.forEach { c -> clientSelector.addItem(SelectorItem(c.id, c.name)) }
                if (clients.isNotEmpty()) {
                    clientSelector.selectedIndex = 0
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@UserTasksWindow,
                    "Failed to load clients: ${'$'}{e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    fun refreshTasks() {
        val client = clientSelector.selectedItem as? SelectorItem ?: return
        scope.launch {
            try {
                val tasks = withContext(Dispatchers.IO) { userTaskService.listActive(client.id) }
                tasksTableModel.update(tasks)
                // Update dock badge with active count for current client
                windowManager.updateUserTaskBadgeForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@UserTasksWindow,
                    "Failed to load tasks: ${'$'}{e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    data class SelectorItem(
        val id: String,
        val name: String,
    ) {
        override fun toString(): String = name
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
