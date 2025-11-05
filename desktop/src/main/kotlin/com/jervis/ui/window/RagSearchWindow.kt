package com.jervis.ui.window

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.service.IClientService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.ui.style.UiDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

class RagSearchWindow(
    private val ragSearchService: IRagSearchService,
    private val clientService: IClientService,
    private val projectService: IProjectService,
) : JFrame("RAG Search") {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val searchField = JTextField(40)
    private val searchButton = JButton("Search")

    private val clientCombo = JComboBox<ClientDto>()
    private val projectCombo = JComboBox<ProjectDto>()
    private val filterKeyField = JTextField(16)
    private val filterValueField = JTextField(16)
    private val maxChunksField = JTextField("20", 8)
    private val minScoreField = JTextField("0.15", 8)

    private val resultsModel = ResultsTableModel(emptyList())
    private val resultsTable = JTable(resultsModel)
    private val detailsArea =
        JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

    init {
        minimumSize = Dimension(1000, 700)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane = buildUi()
        loadInitialData()
        setupActions()
    }

    private fun buildUi(): JPanel {
        val header = UiDesign.headerLabel("RAG Search")

        val searchPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, UiDesign.gap, 0)).apply {
                add(JLabel("Query:"))
                add(searchField)
                add(searchButton)
            }

        val filtersForm =
            UiDesign.formPanel {
                row("Client", clientCombo)
                row("Project", projectCombo)
                row("Filter Key", filterKeyField)
                row("Filter Value", filterValueField)
                row("Max Chunks", maxChunksField)
                row("Min Score", minScoreField)
            }

        val top =
            JPanel(BorderLayout(UiDesign.gap, UiDesign.gap)).apply {
                border = EmptyBorder(UiDesign.outerMargin, UiDesign.outerMargin, 0, UiDesign.outerMargin)
                add(header, BorderLayout.NORTH)
                add(UiDesign.sectionPanel("Search", searchPanel), BorderLayout.CENTER)
                add(UiDesign.sectionPanel("Optional Filters", filtersForm), BorderLayout.SOUTH)
            }

        val tableScroll = JScrollPane(resultsTable)
        resultsTable.rowHeight = resultsTable.rowHeight

        val bottomSplit =
            JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
                topComponent = UiDesign.sectionPanel("Results", tableScroll)
                bottomComponent = UiDesign.sectionPanel("Details", JScrollPane(detailsArea))
                resizeWeight = 0.6
            }

        val content = JPanel(BorderLayout(UiDesign.gap, UiDesign.gap))
        content.add(top, BorderLayout.NORTH)
        content.add(bottomSplit, BorderLayout.CENTER)
        return content
    }

    private fun setupActions() {
        searchButton.addActionListener { performSearch() }
        searchField.addActionListener { performSearch() }
        resultsTable.selectionModel.addListSelectionListener {
            val idx = resultsTable.selectedRow
            if (idx >= 0) {
                val item = resultsModel.getAt(idx)
                detailsArea.text =
                    buildString {
                        appendLine("Score: ${"%.4f".format(item.score)}")
                        appendLine()

                        // Display key metadata in a readable format
                        appendLine("=== Metadata ===")
                        val sourceType = item.metadata["ragSourceType"] ?: "Unknown"
                        val sourceUri = item.metadata["sourceUri"] ?: ""
                        val createdAt = item.metadata["createdAt"] ?: ""
                        val chunkInfo =
                            buildString {
                                val chunkId = item.metadata["chunkId"]
                                val chunkOf = item.metadata["chunkOf"]
                                if (chunkId != null && chunkOf != null) {
                                    append("Chunk $chunkId of $chunkOf")
                                }
                            }

                        appendLine("Source Type: $sourceType")
                        if (sourceUri.isNotEmpty()) appendLine("URI: $sourceUri")
                        if (createdAt.isNotEmpty()) appendLine("Created: $createdAt")
                        if (chunkInfo.isNotEmpty()) appendLine("Chunk: $chunkInfo")

                        // Show summary if available
                        val summary = item.metadata["summary"]
                        if (!summary.isNullOrBlank()) {
                            appendLine()
                            appendLine("=== Summary ===")
                            appendLine(summary)
                        }

                        appendLine()
                        appendLine("=== Content ===")
                        appendLine(item.content)
                    }
            } else {
                detailsArea.text = ""
            }
        }
    }

    private fun loadInitialData() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val clients = clientService.list()
                val projects = projectService.getAllProjects()
                withContext(Dispatchers.Main) {
                    clientCombo.model = DefaultComboBoxModel(clients.toTypedArray())

                    // Add "All Projects" option at the beginning for projects only
                    val projectItems = mutableListOf<ProjectDto?>(null).apply { addAll(projects) }
                    projectCombo.model = DefaultComboBoxModel(projectItems.toTypedArray())

                    clientCombo.renderer =
                        object : DefaultListCellRenderer() {
                            override fun getListCellRendererComponent(
                                list: JList<*>,
                                value: Any?,
                                index: Int,
                                isSelected: Boolean,
                                cellHasFocus: Boolean,
                            ): java.awt.Component {
                                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                                text = (value as? ClientDto)?.name ?: ""
                                return c
                            }
                        }
                    projectCombo.renderer =
                        object : DefaultListCellRenderer() {
                            override fun getListCellRendererComponent(
                                list: JList<*>,
                                value: Any?,
                                index: Int,
                                isSelected: Boolean,
                                cellHasFocus: Boolean,
                            ): java.awt.Component {
                                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                                text = (value as? ProjectDto)?.name ?: "<All Projects>"
                                return c
                            }
                        }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    JOptionPane.showMessageDialog(
                        this@RagSearchWindow,
                        "Failed to load data: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun performSearch() {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter query text.", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        val clientId = (clientCombo.selectedItem as? ClientDto)?.id
        if (clientId.isNullOrBlank()) {
            JOptionPane.showMessageDialog(this, "Please select a client.", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        val projectId = (projectCombo.selectedItem as? ProjectDto)?.id
        val fk = filterKeyField.text.trim().ifEmpty { null }
        val fv = filterValueField.text.trim().ifEmpty { null }

        // Parse maxChunks and minScore with validation
        val maxChunks = maxChunksField.text.trim().toIntOrNull() ?: 20
        val minScore = minScoreField.text.trim().toDoubleOrNull() ?: 0.15

        if (maxChunks < 1 || maxChunks > 1000) {
            JOptionPane.showMessageDialog(
                this,
                "Max Chunks must be between 1 and 1000.",
                "Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        if (minScore < 0.0 || minScore > 1.0) {
            JOptionPane.showMessageDialog(
                this,
                "Min Score must be between 0.0 and 1.0.",
                "Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        searchButton.isEnabled = false

        scope.launch(Dispatchers.IO) {
            val req =
                RagSearchRequestDto(
                    clientId = clientId,
                    projectId = projectId,
                    searchText = query,
                    filterKey = fk,
                    filterValue = fv,
                    maxChunks = maxChunks,
                    minSimilarityThreshold = minScore,
                )
            runCatching { ragSearchService.search(req) }
                .onSuccess { resp ->
                    withContext(Dispatchers.Main) {
                        resultsModel.update(resp.items)
                        if (resp.items.isNotEmpty()) {
                            resultsTable.setRowSelectionInterval(0, 0)
                        }
                        title = "RAG Search — ${resp.items.size} results"
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        JOptionPane.showMessageDialog(
                            this@RagSearchWindow,
                            "Search failed: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            withContext(Dispatchers.Main) { searchButton.isEnabled = true }
        }
    }

    private class ResultsTableModel(
        private var items: List<RagSearchItemDto>,
    ) : AbstractTableModel() {
        private val columns = arrayOf("Score", "Content", "Metadata")

        override fun getRowCount(): Int = items.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val item = items[rowIndex]
            return when (columnIndex) {
                0 -> String.format("%.4f", item.score)
                1 -> item.content
                2 -> item.metadata.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                else -> ""
            }
        }

        fun update(newItems: List<RagSearchItemDto>) {
            this.items = newItems
            fireTableDataChanged()
        }

        fun getAt(row: Int): RagSearchItemDto = items[row]
    }
}
