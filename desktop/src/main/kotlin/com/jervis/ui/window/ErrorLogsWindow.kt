package com.jervis.ui.window

import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IClientService
import com.jervis.service.IErrorLogService
import com.jervis.ui.style.UiDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ErrorLogsWindow(
    private val errorLogService: IErrorLogService,
    private val clientService: IClientService,
) : JFrame("Error Logs") {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val tableModel = DefaultTableModel(arrayOf("ID", "Timestamp", "Message"), 0)
    private val table = JTable(tableModel)
    private var currentClientId: String? = null

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        size = Dimension(900, 500)
        layout = BorderLayout(10, 10)

        val contentPanel = JPanel(BorderLayout())
        val actions = createActionsPanel()
        val section = UiDesign.sectionPanel("Error Logs", JPanel(BorderLayout()).apply {
            add(JScrollPane(table), BorderLayout.CENTER)
            add(actions, BorderLayout.NORTH)
        })

        add(UiDesign.headerLabel("Server Error Logs"), BorderLayout.NORTH)
        add(section, BorderLayout.CENTER)
    }

    private fun createActionsPanel(): JPanel {
        val refreshBtn = JButton("Refresh").apply { addActionListener { refresh() } }
        val copyBtn = JButton("Copy Selected").apply { addActionListener { copySelected() } }
        val deleteBtn = JButton("Delete Selected").apply { addActionListener { deleteSelected() } }
        val deleteAllBtn = JButton("Delete All for Client").apply { addActionListener { deleteAllForClient() } }
        return UiDesign.actionBar(refreshBtn, copyBtn, deleteBtn, deleteAllBtn)
    }

    fun setCurrentClientId(clientId: String?) {
        currentClientId = clientId
    }

    fun refresh() {
        val clientId = currentClientId
        if (clientId == null) {
            JOptionPane.showMessageDialog(this, "Select a client in main UI first.", "Info", JOptionPane.WARNING_MESSAGE)
            return
        }
        scope.launch {
            runCatching { errorLogService.list(clientId, 500) }
                .onSuccess { logs ->
                    with(Dispatchers.Swing) {
                        SwingUtilities.invokeLater { render(logs) }
                    }
                }
                .onFailure { e ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this@ErrorLogsWindow, e.message, "Error", JOptionPane.ERROR_MESSAGE)
                    }
                }
        }
    }

    private fun render(logs: List<ErrorLogDto>) {
        tableModel.setRowCount(0)
        logs.forEach { l ->
            tableModel.addRow(arrayOf(l.id, l.createdAt, l.message))
        }
    }

    private fun copySelected() {
        val row = table.selectedRow
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a row first.")
            return
        }
        val id = tableModel.getValueAt(row, 0).toString()
        scope.launch {
            runCatching { errorLogService.get(id) }
                .onSuccess { dto ->
                    val text = buildString {
                        appendLine("ID: ${dto.id}")
                        appendLine("Timestamp: ${dto.createdAt}")
                        appendLine("Message: ${dto.message}")
                        dto.stackTrace?.let { appendLine("\n$it") }
                    }
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val selection = StringSelection(text)
                    clipboard.setContents(selection, selection)
                }
                .onFailure { e ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this@ErrorLogsWindow, e.message, "Error", JOptionPane.ERROR_MESSAGE)
                    }
                }
        }
    }

    private fun deleteSelected() {
        val row = table.selectedRow
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a row first.")
            return
        }
        val id = tableModel.getValueAt(row, 0).toString()
        scope.launch {
            runCatching { errorLogService.delete(id) }
                .onSuccess { refresh() }
                .onFailure { e -> SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this@ErrorLogsWindow, e.message, "Error", JOptionPane.ERROR_MESSAGE) } }
        }
    }

    private fun deleteAllForClient() {
        val clientId = currentClientId ?: return
        if (JOptionPane.showConfirmDialog(this, "Really delete all error logs for this client?", "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        scope.launch {
            runCatching { errorLogService.deleteAll(clientId) }
                .onSuccess { refresh() }
                .onFailure { e -> SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this@ErrorLogsWindow, e.message, "Error", JOptionPane.ERROR_MESSAGE) } }
        }
    }
}
