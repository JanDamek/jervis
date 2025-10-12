package com.jervis.ui.window

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.IClientIndexingService
import com.jervis.service.IClientService
import com.jervis.service.IIndexingService
import com.jervis.service.IProjectService
import com.jervis.ui.component.ClientSettingsComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Window for managing project settings.
 * Provides functionality to add, edit, delete, and manage projects.
 */
class ProjectSettingWindow(
    private val projectService: IProjectService,
    private val clientService: IClientService,
    private val indexingService: IIndexingService,
    private val clientIndexingService: IClientIndexingService,
) : JFrame("Project Management") {
    private val projectTableModel = ProjectTableModel(emptyList())
    private val projectTable = JTable(projectTableModel)

    private val addButton = JButton("Add Project")
    private val editButton = JButton("Edit Project")
    private val deleteButton = JButton("Delete Project")
    private val activateButton =
        JButton("Activate Project").apply {
            isVisible = false
            isEnabled = false
        }
    private val defaultButton = JButton("Set as Default")
    private val assignClientButton = JButton("Assign Client…")
    private val newClientButton = JButton("New Client…")
    private val dependenciesButton = JButton("Dependencies…")
    private val toggleDisabledButton = JButton("Toggle Disabled")
    private val reindexProjectButton = JButton("Reindex Project")
    private val reindexAllButton = JButton("Reindex All Projects")

    init {
        setupUI()
        setupEventHandlers()
        loadProjectsWithCoroutine()
    }

    /**
     * Setup the user interface components
     */
    private fun setupUI() {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setSize(1280, 600)
        setLocationRelativeTo(null)

        // Configure table
        projectTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        projectTable.setDefaultRenderer(Boolean::class.java, BooleanTableCellRenderer())
        projectTable.autoResizeMode = JTable.AUTO_RESIZE_OFF

        // Main panel
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Table panel
        val tablePanel = JPanel(BorderLayout())
        tablePanel.add(JScrollPane(projectTable), BorderLayout.CENTER)

        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(addButton)
        buttonPanel.add(editButton)
        buttonPanel.add(deleteButton)
        buttonPanel.add(activateButton)
        buttonPanel.add(defaultButton)
        buttonPanel.add(assignClientButton)
        buttonPanel.add(newClientButton)
        buttonPanel.add(dependenciesButton)
        buttonPanel.add(toggleDisabledButton)
        buttonPanel.add(reindexProjectButton)
        buttonPanel.add(reindexAllButton)

        mainPanel.add(tablePanel, BorderLayout.CENTER)
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)

        add(mainPanel)

        // Configure column widths after all components are added
        configureTableColumnWidths()
    }

    /**
     * Configure table column widths to prevent character corruption
     */
    private fun configureTableColumnWidths() {
        val columnModel = projectTable.columnModel

        // Set preferred widths for each column based on content type
        // Column 0: ID - shorter since it's a hex string
        columnModel.getColumn(0).preferredWidth = 120

        // Column 1: Name - medium width for project names
        columnModel.getColumn(1).preferredWidth = 180

        // Column 2: Path - wider for file paths
        columnModel.getColumn(2).preferredWidth = 250

        // Column 3: Description - wide for descriptions
        columnModel.getColumn(3).preferredWidth = 200

        // Column 4: Client - medium width for client names
        columnModel.getColumn(4).preferredWidth = 150

        // Column 5: Disabled - narrow for boolean
        columnModel.getColumn(5).preferredWidth = 80

        // Column 6: Active - narrow for boolean
        columnModel.getColumn(6).preferredWidth = 80

        // Column 7: Is Current - narrow for boolean
        columnModel.getColumn(7).preferredWidth = 80
    }

    /**
     * Setup event handlers for buttons and table
     */
    private fun setupEventHandlers() {
        addButton.addActionListener { addNewProject() }
        editButton.addActionListener { editSelectedProject() }
        deleteButton.addActionListener { deleteSelectedProject() }
        activateButton.addActionListener { activateSelectedProject() }
        defaultButton.addActionListener { setSelectedProjectAsDefault() }
        assignClientButton.addActionListener { assignClientToSelectedProject() }
        newClientButton.addActionListener { createNewClient() }
        dependenciesButton.addActionListener { editDependenciesForSelectedProject() }
        toggleDisabledButton.addActionListener { toggleDisabledForSelectedProject() }
        reindexProjectButton.addActionListener { reindexSelectedProject() }
        reindexAllButton.addActionListener { reindexAllProjects() }

        // Table selection listener
        projectTable.selectionModel.addListSelectionListener { updateButtonState() }

        // Double-click to edit and right-click context menu
        projectTable.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        editSelectedProject()
                    }
                }

                override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)

                override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

                private fun maybeShowPopup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        val row = projectTable.rowAtPoint(e.point)
                        if (row >= 0) {
                            projectTable.setRowSelectionInterval(row, row)
                            val contextMenu = JPopupMenu()

                            // Add Project
                            val addItem = JMenuItem("Add New Project")
                            addItem.addActionListener { addNewProject() }
                            contextMenu.add(addItem)

                            contextMenu.addSeparator()

                            // Edit Project
                            val editItem = JMenuItem("Edit Project")
                            editItem.addActionListener { editSelectedProject() }
                            editItem.isEnabled = projectTable.selectedRow != -1
                            contextMenu.add(editItem)

                            // Delete Project
                            val deleteItem = JMenuItem("Delete Project")
                            deleteItem.addActionListener { deleteSelectedProject() }
                            deleteItem.isEnabled = projectTable.selectedRow != -1
                            contextMenu.add(deleteItem)

                            contextMenu.addSeparator()

                            // Set as Default
                            val defaultItem = JMenuItem("Set as Default")
                            defaultItem.addActionListener { setSelectedProjectAsDefault() }
                            if (projectTable.selectedRow != -1) {
                                val selectedProject = projectTableModel.getProjectAt(projectTable.selectedRow)
                                defaultItem.isEnabled = !selectedProject.isActive
                            } else {
                                defaultItem.isEnabled = false
                            }
                            contextMenu.add(defaultItem)

                            contextMenu.addSeparator()

                            // Assign Client
                            val assignClientItem = JMenuItem("Assign Client")
                            assignClientItem.addActionListener { assignClientToSelectedProject() }
                            contextMenu.add(assignClientItem)

                            // Create New Client
                            val newClientItem = JMenuItem("Create New Client")
                            newClientItem.addActionListener { createNewClient() }
                            contextMenu.add(newClientItem)

                            contextMenu.addSeparator()

                            // Edit Dependencies
                            val dependenciesItem = JMenuItem("Edit Dependencies")
                            dependenciesItem.addActionListener { editDependenciesForSelectedProject() }
                            contextMenu.add(dependenciesItem)

                            // Toggle Disabled
                            val toggleDisabledItem = JMenuItem("Toggle Disabled")
                            toggleDisabledItem.addActionListener { toggleDisabledForSelectedProject() }
                            contextMenu.add(toggleDisabledItem)

                            contextMenu.addSeparator()

                            // Reindex Project
                            val reindexProjectItem = JMenuItem("Reindex Project")
                            reindexProjectItem.addActionListener { reindexSelectedProject() }
                            contextMenu.add(reindexProjectItem)

                            // Reindex All Projects
                            val reindexAllItem = JMenuItem("Reindex All Projects")
                            reindexAllItem.addActionListener { reindexAllProjects() }
                            contextMenu.add(reindexAllItem)

                            contextMenu.show(projectTable, e.x, e.y)
                        }
                    }
                }
            },
        )

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

        updateButtonState()
    }

    /**
     * Update button states based on selection
     */
    private fun updateButtonState() {
        val hasSelection = projectTable.selectedRow != -1
        editButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
        activateButton.isEnabled = false
        activateButton.isVisible = false
        if (hasSelection) {
            val selectedProject = projectTableModel.getProjectAt(projectTable.selectedRow)
            // Default button is enabled only if project is not default
            defaultButton.isEnabled = !selectedProject.isActive
        } else {
            defaultButton.isEnabled = false
        }
    }

    /**
     * Load list of projects
     */
    suspend fun loadProjects() {
        val projectDocuments = projectService.getAllProjects()
        projectTableModel.updateProjects(projectDocuments)
        updateButtonState()
    }

    /**
     * Load projects using coroutine
     */
    fun loadProjectsWithCoroutine() {
        CoroutineScope(Dispatchers.Main).launch {
            loadProjects()
        }
    }

    /**
     * Add new project
     */
    private fun addNewProject() {
        val dialog = ProjectDialog(this, "New Project")
        dialog.isVisible = true

        val result = dialog.result
        if (result != null) {
            val newProject =
                ProjectDocument(
                    clientId = GLOBAL_ID,
                    name = result.name,
                    projectPath = result.path,
                    meetingPath = result.meetingPath,
                    audioPath = result.audioPath,
                    documentationPath = result.documentationPath,
                    description = result.description,
                    languages = result.languages,
                    primaryUrl = result.primaryUrl.takeIf { it.isNotBlank() },
                    extraUrls = result.extraUrls,
                    credentialsRef = result.credentialsRef,
                    defaultBranch = result.defaultBranch,
                    inspirationOnly = result.inspirationOnly,
                    indexingRules =
                        com.jervis.domain.project.IndexingRules(
                            includeGlobs = result.includeGlobs,
                            excludeGlobs = result.excludeGlobs,
                            maxFileSizeMB = result.maxFileSizeMB,
                        ),
                    isDisabled = result.isDisabled,
                    isActive = false,
                )

            CoroutineScope(Dispatchers.Main).launch {
                projectService.saveProject(newProject, result.isDefault)
                loadProjects()
            }
        }
    }

    /**
     * Edit selected project
     */
    private fun editSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            val dialog =
                ProjectDialog(
                    this,
                    "Edit Project",
                    project.name,
                    project.projectPath,
                    project.meetingPath ?: "",
                    project.audioPath ?: "",
                    project.documentationPath ?: "",
                    project.description ?: "",
                    project.primaryUrl ?: "",
                    project.extraUrls.joinToString(", "),
                    project.credentialsRef ?: "",
                    project.languages.joinToString(", "),
                    project.inspirationOnly,
                    project.defaultBranch,
                    project.indexingRules.includeGlobs.joinToString(", "),
                    project.indexingRules.excludeGlobs.joinToString(", "),
                    project.indexingRules.maxFileSizeMB,
                    project.isDisabled,
                    project.isActive,
                )
            dialog.isVisible = true

            val result = dialog.result
            if (result != null) {
                // Create updated project
                val updatedProject =
                    project.copy(
                        name = result.name,
                        projectPath = result.path,
                        meetingPath = result.meetingPath,
                        audioPath = result.audioPath,
                        documentationPath = result.documentationPath,
                        description = result.description,
                        languages = result.languages,
                        primaryUrl = result.primaryUrl.takeIf { it.isNotBlank() },
                        extraUrls = result.extraUrls,
                        credentialsRef = result.credentialsRef,
                        defaultBranch = result.defaultBranch,
                        inspirationOnly = result.inspirationOnly,
                        indexingRules =
                            com.jervis.domain.project.IndexingRules(
                                includeGlobs = result.includeGlobs,
                                excludeGlobs = result.excludeGlobs,
                                maxFileSizeMB = result.maxFileSizeMB,
                            ),
                        isDisabled = result.isDisabled,
                    )

                // Save project and optionally set as default
                CoroutineScope(Dispatchers.Main).launch {
                    projectService.saveProject(updatedProject, result.isDefault)
                    loadProjects()
                }
            }
        }
    }

    /**
     * Delete selected project
     */
    private fun deleteSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            val result =
                JOptionPane.showConfirmDialog(
                    this,
                    "Are you sure you want to delete project '${project.name}'?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                )

            if (result == JOptionPane.YES_OPTION) {
                CoroutineScope(Dispatchers.Main).launch {
                    projectService.deleteProject(project)
                    loadProjects()
                }
            }
        }
    }

    /**
     * Activate selected project
     */
    private fun activateSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            CoroutineScope(Dispatchers.Main).launch {
                projectService.setActiveProject(project)
                loadProjects()
            }
        }
    }

    /**
     * Set selected project as default
     */
    private fun setSelectedProjectAsDefault() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            CoroutineScope(Dispatchers.Main).launch {
                projectService.setDefaultProject(project)
                loadProjects()
            }
        }
    }

    private fun assignClientToSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a project first.",
                "Info",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        val project = projectTableModel.getProjectAt(selectedRow)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val clients = clientService.list()
                if (clients.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this@ProjectSettingWindow,
                        "No clients found. Create a client first.",
                        "Info",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    return@launch
                }

                val dialog = JDialog(this@ProjectSettingWindow, "Assign Client", true)
                val combo = JComboBox<ClientDocument>()
                clients.forEach { combo.addItem(it) }
                combo.renderer =
                    object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: javax.swing.JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean,
                        ): java.awt.Component {
                            val label =
                                super.getListCellRendererComponent(
                                    list,
                                    value,
                                    index,
                                    isSelected,
                                    cellHasFocus,
                                ) as JLabel
                            val c = value as? ClientDocument
                            if (c != null) label.text = c.name
                            return label
                        }
                    }
                val okBtn = JButton("OK")
                val cancelBtn = JButton("Cancel")

                okBtn.addActionListener {
                    val selected = combo.selectedItem as? ClientDocument
                    if (selected == null) {
                        JOptionPane.showMessageDialog(
                            dialog,
                            "Please select a client.",
                            "Validation",
                            JOptionPane.WARNING_MESSAGE,
                        )
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val updated = project.copy(clientId = selected.id)
                                projectService.saveProject(updated, false)
                                loadProjects()
                            } catch (e: Exception) {
                                JOptionPane.showMessageDialog(
                                    dialog,
                                    "Failed to assign client: ${e.message}",
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE,
                                )
                            } finally {
                                dialog.dispose()
                            }
                        }
                    }
                }
                cancelBtn.addActionListener { dialog.dispose() }

                val panel = JPanel(BorderLayout(8, 8))
                panel.border = EmptyBorder(12, 12, 12, 12)
                panel.add(JLabel("Select client:"), BorderLayout.NORTH)
                panel.add(combo, BorderLayout.CENTER)
                val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
                btnPanel.add(okBtn)
                btnPanel.add(cancelBtn)
                panel.add(btnPanel, BorderLayout.SOUTH)

                dialog.contentPane = panel
                dialog.pack()
                dialog.setLocationRelativeTo(this@ProjectSettingWindow)
                dialog.isVisible = true
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "Error loading clients: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun createNewClient() {
        val dialog = JDialog(this, "New Client", true)
        val nameField = JTextField(30)
        val slugField = JTextField(30)

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(6, 8, 6, 8)
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.LINE_END
        form.add(JLabel("Name:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        form.add(nameField, gbc)
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.anchor =
            GridBagConstraints.LINE_END
        form.add(JLabel("Slug:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        form.add(slugField, gbc)

        val okBtn = JButton("Create")
        val cancelBtn = JButton("Cancel")
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        btnPanel.add(okBtn)
        btnPanel.add(cancelBtn)

        val panel = JPanel(BorderLayout(8, 8))
        panel.border = EmptyBorder(12, 12, 12, 12)
        panel.add(form, BorderLayout.CENTER)
        panel.add(btnPanel, BorderLayout.SOUTH)

        okBtn.addActionListener {
            val name = nameField.text.trim()
            val slug = slugField.text.trim()
            val regex = Regex("^[a-z0-9-]+$")
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Name is required.",
                    "Validation",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }
            if (!regex.matches(slug)) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Slug must match ^[a-z0-9-]+$",
                    "Validation",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val client = ClientDocument(name = name)
                    clientService.create(client)
                    JOptionPane.showMessageDialog(dialog, "Client created.", "Success", JOptionPane.INFORMATION_MESSAGE)
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        dialog,
                        "Failed to create client: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                } finally {
                    dialog.dispose()
                }
            }
        }
        cancelBtn.addActionListener { dialog.dispose() }

        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun editDependenciesForSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a project first.",
                "Info",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        val project = projectTableModel.getProjectAt(selectedRow)
        CoroutineScope(Dispatchers.Main).launch {
            val allProjects = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            val selectable = allProjects.filter { it.id != project.id }
            val listModel = javax.swing.DefaultListModel<ProjectDocument>()
            selectable.forEach { listModel.addElement(it) }
            val list = javax.swing.JList(listModel)
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

            list.cellRenderer =
                object : DefaultListCellRenderer() {
                    init {
                        // Ensure renderer is opaque to prevent background from painting over text
                        isOpaque = true
                    }

                    override fun getListCellRendererComponent(
                        listComp: javax.swing.JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): java.awt.Component {
                        val label =
                            super.getListCellRendererComponent(
                                listComp,
                                value,
                                index,
                                isSelected,
                                cellHasFocus,
                            ) as JLabel
                        val p = value as? ProjectDocument
                        if (p != null) label.text = p.name
                        return label
                    }
                }
            val preselect =
                selectable
                    .mapIndexedNotNull { idx, p -> if (project.dependsOnProjects.contains(p.id)) idx else null }
                    .toIntArray()
            list.selectedIndices = preselect

            val ok = JButton("OK")
            val cancel = JButton("Cancel")
            val dialog = JDialog(this@ProjectSettingWindow, "Dependencies", true)
            ok.addActionListener {
                val selected = list.selectedValuesList
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val updated = project.copy(dependsOnProjects = selected.map { it.id })
                        withContext(Dispatchers.IO) { projectService.saveProject(updated, false) }
                        loadProjects()
                        JOptionPane.showMessageDialog(
                            this@ProjectSettingWindow,
                            "Dependencies saved.",
                            "Info",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(
                            this@ProjectSettingWindow,
                            "Failed to save: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    } finally {
                        dialog.dispose()
                    }
                }
            }
            cancel.addActionListener { dialog.dispose() }

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = EmptyBorder(12, 12, 12, 12)
            panel.add(JScrollPane(list), BorderLayout.CENTER)
            val btns =
                JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(ok)
                    add(cancel)
                }
            panel.add(btns, BorderLayout.SOUTH)
            dialog.contentPane = panel
            dialog.setSize(520, 420)
            dialog.setLocationRelativeTo(this@ProjectSettingWindow)
            dialog.isVisible = true
        }
    }

    private fun toggleDisabledForSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a project first.",
                "Info",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        val project = projectTableModel.getProjectAt(selectedRow)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val updated = project.copy(isDisabled = !project.isDisabled)
                withContext(Dispatchers.IO) { projectService.saveProject(updated, false) }
                loadProjects()
                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    if (updated.isDisabled) "Project disabled." else "Project enabled.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "Failed to toggle disabled: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    /**
     * Table model for projects
     */
    private class ProjectTableModel(
        private var projectList: List<ProjectDocument>,
    ) : AbstractTableModel() {
        private val columns = arrayOf("ID", "Name", "Path", "Description", "Client", "Disabled", "Active", "Is Current")

        fun updateProjects(projects: List<ProjectDocument>) {
            this.projectList = projects
            fireTableDataChanged()
        }

        fun getProjectAt(rowIndex: Int): ProjectDocument = projectList[rowIndex]

        override fun getRowCount(): Int = projectList.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val project = projectList[rowIndex]

            return when (columnIndex) {
                0 -> project.id.toHexString() ?: ""
                1 -> project.name
                2 -> project.projectPath
                3 -> project.description ?: ""
                4 -> project.clientId?.toHexString() ?: ""
                5 -> project.isDisabled
                6 -> project.isActive
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0 -> String::class.java
                5, 6, 7 -> Boolean::class.java
                else -> String::class.java
            }

        override fun isCellEditable(
            rowIndex: Int,
            columnIndex: Int,
        ): Boolean {
            return false // All cells are non-editable directly in table
        }
    }

    /**
     * Dialog for adding/editing project
     */
    private class ProjectDialog(
        owner: JFrame,
        title: String,
        initialName: String = "",
        initialPath: String = "",
        initialMeetingPath: String = "",
        initialAudioPath: String = "",
        initialDocumentationPath: String = "",
        initialDescription: String = "",
        initialPrimaryUrl: String = "",
        initialExtraUrls: String = "",
        initialCredentialsRef: String = "",
        initialLanguages: String = "",
        initialInspirationOnly: Boolean = false,
        initialDefaultBranch: String = "main",
        initialIncludeGlobs: String = "**/*.kt, **/*.java, **/*.md",
        initialExcludeGlobs: String = "**/build/**, **/.git/**, **/*.min.*",
        initialMaxFileSizeMB: Int = 5,
        initialIsDisabled: Boolean = false,
        initialDefault: Boolean = false,
    ) : JDialog(owner, title, true) {
        private val nameField =
            JTextField(initialName).apply {
                preferredSize = Dimension(480, 30) // Min 480px width as required
                toolTipText = "Project display name."
            }
        private val pathField =
            JTextField(initialPath).apply {
                preferredSize = Dimension(480, 30) // Min 480px width as required
                toolTipText = "Absolute path to your project directory."
            }
        private val browseButton = JButton("Browse…")

        // Optional paths
        private val meetingPathField =
            JTextField(initialMeetingPath).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Folder with meeting transcripts (text/markdown)."
            }
        private val browseMeetingButton = JButton("Browse…")
        private val audioPathField =
            JTextField(initialAudioPath).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Folder with audio files to be transcribed (wav/mp3/m4a/etc.)."
            }
        private val browseAudioButton = JButton("Browse…")
        private val documentationPathField =
            JTextField(initialDocumentationPath).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Folder with local documentation (markdown/html/pdf)."
            }
        private val browseDocumentationButton = JButton("Browse…")
        private val descriptionArea =
            JTextArea(initialDescription.ifEmpty { "Optional project description" }, 4, 50).apply {
                // Add placeholder functionality
                if (initialDescription.isEmpty()) {
                    foreground = java.awt.Color.GRAY
                    addFocusListener(
                        object : java.awt.event.FocusAdapter() {
                            override fun focusGained(e: java.awt.event.FocusEvent) {
                                if (text == "Optional project description") {
                                    text = ""
                                    foreground = java.awt.Color.BLACK
                                }
                            }

                            override fun focusLost(e: java.awt.event.FocusEvent) {
                                if (text.trim().isEmpty()) {
                                    text = "Optional project description"
                                    foreground = java.awt.Color.GRAY
                                }
                            }
                        },
                    )
                }
            }
        private val primaryUrlField =
            JTextField(initialPrimaryUrl).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Primary repository URL (Git, HTTP, or SSH)."
            }
        private val extraUrlsField =
            JTextField(initialExtraUrls).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Additional repository URLs (comma-separated)."
            }
        private val credentialsRefField =
            JTextField(initialCredentialsRef).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Reference to stored credentials for repository access."
            }
        private val languagesField =
            JTextField(initialLanguages).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Programming languages used in project (comma-separated)."
            }
        private val inspirationOnlyCheckbox =
            JCheckBox("Inspiration only", initialInspirationOnly).apply {
                toolTipText = "If checked, this project is used only for inspiration and not active development."
            }
        private val defaultBranchField =
            JTextField(initialDefaultBranch).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Default branch name for the repository."
            }
        private val includeGlobsField =
            JTextField(initialIncludeGlobs).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "File patterns to include in indexing (comma-separated glob patterns)."
            }
        private val excludeGlobsField =
            JTextField(initialExcludeGlobs).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "File patterns to exclude from indexing (comma-separated glob patterns)."
            }
        private val maxFileSizeMBField =
            JTextField(initialMaxFileSizeMB.toString()).apply {
                preferredSize = Dimension(480, 30)
                toolTipText = "Maximum file size in MB to include in indexing."
            }
        private val isDisabledCheckbox =
            JCheckBox("Project disabled", initialIsDisabled).apply {
                toolTipText = "If checked, this project is globally disabled."
            }
        private val defaultCheckbox = JCheckBox("Set as default project", initialDefault)

        // Project Override Panels (nullable, can override client settings)
        private val guidelinesPanel = ClientSettingsComponents.createGuidelinesPanel()
        private val reviewPolicyPanel = ClientSettingsComponents.createReviewPolicyPanel()
        private val formattingPanel = ClientSettingsComponents.createFormattingPanel()
        private val secretsPolicyPanel = ClientSettingsComponents.createSecretsPolicyPanel()
        private val anonymizationPanel = ClientSettingsComponents.createAnonymizationPanel()
        private val inspirationPolicyPanel = ClientSettingsComponents.createInspirationPolicyPanel()
        private val clientToolsPanel = ClientSettingsComponents.createClientToolsPanel()

        private val okButton = JButton("OK")
        private val cancelButton = JButton("Cancel")

        var result: ProjectResult? = null

        init {
            // Basic dialog setup
            preferredSize = Dimension(720, 650)
            minimumSize = Dimension(700, 600)
            isResizable = true
            defaultCloseOperation = DISPOSE_ON_CLOSE

            // Component setup
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true

            // Button actions
            okButton.addActionListener { saveAndClose() }
            cancelButton.addActionListener { dispose() }
            browseButton.addActionListener { browsePath() }
            browseMeetingButton.addActionListener { browseDirectoryInto(meetingPathField) }
            browseAudioButton.addActionListener { browseDirectoryInto(audioPathField) }
            browseDocumentationButton.addActionListener { browseDirectoryInto(documentationPathField) }

            // Build UI with tabs
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(16, 16, 16, 16)

            val tabbedPane = javax.swing.JTabbedPane()

            // Basic Information Tab
            val basicPanel = createBasicInfoPanel()
            tabbedPane.addTab("Basic Information", basicPanel)

            // Paths Tab
            val pathsPanel = createPathsPanel()
            tabbedPane.addTab("Paths", pathsPanel)

            // Repository Tab
            val repoPanel = createRepositoryPanel()
            tabbedPane.addTab("Repository", repoPanel)

            // Indexing Tab
            val indexingPanel = createIndexingPanel()
            tabbedPane.addTab("Indexing", indexingPanel)

            // Advanced Tab
            val advancedPanel = createAdvancedPanel()
            tabbedPane.addTab("Advanced", advancedPanel)

            // Project Overrides Tab
            val overridesPanel = createProjectOverridesPanel()
            tabbedPane.addTab("Project Overrides", overridesPanel)

            // Button panel
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)

            panel.add(tabbedPane, BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            add(panel)

            // Set focus order: nameField → pathField → browseButton → descriptionArea → defaultCheckbox → okButton → cancelButton
            val focusOrder =
                arrayOf(nameField, pathField, browseButton, descriptionArea, defaultCheckbox, okButton, cancelButton)
            for (i in 0 until focusOrder.size - 1) {
                focusOrder[i].nextFocusableComponent = focusOrder[i + 1]
            }

            // Add ESC key handling - ESC acts as Cancel
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) {
                            dispose() // Close dialog without saving
                        }
                    }
                },
            )

            // Make sure the dialog can receive key events
            isFocusable = true
        }

        private fun createBasicInfoPanel(): JPanel = createBasicInfoPanelInternal()

        private fun createBasicInfoPanelInternal(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)

            var row = 0

            // Project name (required)
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Name:*"), gbc)
            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(nameField, gbc)
            row++

            // Project path (required)
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Local project folder:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(pathField, gbc)
            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(browseButton, gbc)
            row++

            // Project description (optional)
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            panel.add(JLabel("Description:"), gbc)
            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            panel.add(JScrollPane(descriptionArea), gbc)
            row++

            // Languages
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.weighty = 0.0
            panel.add(JLabel("Languages:"), gbc)
            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(languagesField, gbc)
            row++

            // Checkboxes
            gbc.gridx = 1
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(defaultCheckbox, gbc)

            return panel
        }

        private fun createPathsPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)
            var row = 0

            // Meeting transcripts folder
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            panel.add(JLabel("Meeting transcripts folder:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(meetingPathField, gbc)
            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(browseMeetingButton, gbc)
            row++

            // Audio files folder
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            panel.add(JLabel("Audio files folder:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(audioPathField, gbc)
            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(browseAudioButton, gbc)
            row++

            // Documentation folder
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            panel.add(JLabel("Documentation folder:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(documentationPathField, gbc)
            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(browseDocumentationButton, gbc)
            row++

            // Spacer
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 3
            gbc.weighty = 1.0
            panel.add(JPanel(), gbc)

            return panel
        }

        private fun createRepositoryPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)

            var row = 0

            // Primary URL (optional)
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Primary URL:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(primaryUrlField, gbc)
            row++

            // Extra URLs
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Extra URLs:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(extraUrlsField, gbc)
            row++

            // Credentials Reference
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(credentialsRefField, gbc)
            row++

            // Default Branch
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Default Branch:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(defaultBranchField, gbc)
            row++

            // Add spacer to push content to top
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 1.0
            panel.add(JPanel(), gbc)

            return panel
        }

        private fun createIndexingPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)

            var row = 0

            // Include Globs
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Include Patterns:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(includeGlobsField, gbc)
            row++

            // Exclude Globs
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Exclude Patterns:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(excludeGlobsField, gbc)
            row++

            // Max File Size
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Max File Size (MB):"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(maxFileSizeMBField, gbc)
            row++

            // Add spacer to push content to top
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 1.0
            panel.add(JPanel(), gbc)

            return panel
        }

        private fun createAdvancedPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)

            var row = 0

            // Checkboxes
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            panel.add(inspirationOnlyCheckbox, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            panel.add(isDisabledCheckbox, gbc)
            row++

            // Add spacer to push content to top
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 1.0
            panel.add(JPanel(), gbc)

            return panel
        }

        private fun createProjectOverridesPanel(): JPanel {
            val panel = JPanel(BorderLayout())

            val tabbedPane = javax.swing.JTabbedPane()

            // Guidelines Override Tab
            tabbedPane.addTab("Guidelines", JScrollPane(guidelinesPanel))

            // Review Policy Override Tab
            tabbedPane.addTab("Review Policy", JScrollPane(reviewPolicyPanel))

            // Formatting Override Tab
            tabbedPane.addTab("Formatting", JScrollPane(formattingPanel))

            // Secrets Policy Override Tab
            tabbedPane.addTab("Secrets Policy", JScrollPane(secretsPolicyPanel))

            // Anonymization Override Tab
            tabbedPane.addTab("Anonymization", JScrollPane(anonymizationPanel))

            // Inspiration Policy Override Tab
            tabbedPane.addTab("Inspiration Policy", JScrollPane(inspirationPolicyPanel))

            // Client Tools Override Tab
            tabbedPane.addTab("Client Tools", JScrollPane(clientToolsPanel))

            panel.add(tabbedPane, BorderLayout.CENTER)

            return panel
        }

        private fun browseDirectoryInto(target: JTextField) {
            val fileChooser = JFileChooser()
            fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val currentPath = target.text.trim()
            fileChooser.currentDirectory =
                if (currentPath.isNotEmpty()) {
                    val pathFile = File(currentPath)
                    if (pathFile.exists()) pathFile else File(System.getProperty("user.home"))
                } else {
                    File(System.getProperty("user.home"))
                }
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.text = fileChooser.selectedFile.absolutePath
            }
        }

        private fun saveAndClose() {
            val name = nameField.text.trim()
            val path = pathField.text.trim()
            val description =
                if (descriptionArea.text.trim() == "Optional project description") "" else descriptionArea.text.trim()
            val primaryUrl = primaryUrlField.text.trim()
            val extraUrls =
                extraUrlsField.text
                    .trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val credentialsRef = credentialsRefField.text.trim().ifEmpty { null }
            val languages =
                languagesField.text
                    .trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val inspirationOnly = inspirationOnlyCheckbox.isSelected
            val defaultBranch = defaultBranchField.text.trim()
            val includeGlobs =
                includeGlobsField.text
                    .trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val excludeGlobs =
                excludeGlobsField.text
                    .trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val maxFileSizeMB =
                try {
                    maxFileSizeMBField.text.trim().toInt()
                } catch (e: NumberFormatException) {
                    5 // default value
                }
            val isDisabled = isDisabledCheckbox.isSelected
            val isDefault = defaultCheckbox.isSelected

            val meetingPath = meetingPathField.text.trim().ifEmpty { null }
            val audioPath = audioPathField.text.trim().ifEmpty { null }
            val documentationPath = documentationPathField.text.trim().ifEmpty { null }

            // Validate required fields
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(this, "Project name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE)
                return
            }

            if (path.isBlank()) {
                JOptionPane.showMessageDialog(this, "Project path cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE)
                return
            }

            val file = File(path)
            if (!file.exists() || !file.isDirectory) {
                JOptionPane.showMessageDialog(
                    this,
                    "Selected path is not a directory.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }

            if (maxFileSizeMB <= 0) {
                JOptionPane.showMessageDialog(
                    this,
                    "Max file size must be a positive number.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }

            // Collect override data from panels
            val overrides =
                com.jervis.domain.project.ProjectOverrides(
                    codingGuidelines = guidelinesPanel.getGuidelines(),
                    reviewPolicy = reviewPolicyPanel.getReviewPolicy(),
                    formatting = formattingPanel.getFormatting(),
                    secretsPolicy = secretsPolicyPanel.getSecretsPolicy(),
                    anonymization = anonymizationPanel.getAnonymization(),
                    inspirationPolicy = inspirationPolicyPanel.getInspirationPolicy(),
                    tools = clientToolsPanel.getClientTools(),
                )

            result =
                ProjectResult(
                    name,
                    path,
                    meetingPath,
                    audioPath,
                    documentationPath,
                    description,
                    primaryUrl,
                    extraUrls,
                    credentialsRef,
                    languages,
                    inspirationOnly,
                    defaultBranch,
                    includeGlobs,
                    excludeGlobs,
                    maxFileSizeMB,
                    isDisabled,
                    isDefault,
                    overrides,
                )
            dispose()
        }

        private fun browsePath() {
            val fileChooser = JFileChooser()
            fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY

            // Set current directory based on pathField content or user home
            val currentPath = pathField.text.trim()
            fileChooser.currentDirectory =
                if (currentPath.isNotEmpty()) {
                    val pathFile = File(currentPath)
                    if (pathFile.exists()) pathFile else File(System.getProperty("user.home"))
                } else {
                    File(System.getProperty("user.home"))
                }

            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                pathField.text = fileChooser.selectedFile.absolutePath
            }
        }
    }

    /**
     * Result data class for project dialog
     */
    data class ProjectResult(
        val name: String,
        val path: String,
        val meetingPath: String?,
        val audioPath: String?,
        val documentationPath: String?,
        val description: String,
        val primaryUrl: String,
        val extraUrls: List<String>,
        val credentialsRef: String?,
        val languages: List<String>,
        val inspirationOnly: Boolean,
        val defaultBranch: String,
        val includeGlobs: List<String>,
        val excludeGlobs: List<String>,
        val maxFileSizeMB: Int,
        val isDisabled: Boolean,
        val isDefault: Boolean,
        val overrides: com.jervis.domain.project.ProjectOverrides,
    )

    /**
     * Custom cell renderer for boolean values
     */
    private class BooleanTableCellRenderer : DefaultTableCellRenderer() {
        init {
            // Ensure renderer is opaque to prevent grid lines from painting over text
            isOpaque = true
        }

        override fun setValue(value: Any?) {
            text =
                when (value as? Boolean) {
                    true -> "Yes"
                    false -> "No"
                    else -> ""
                }
            horizontalAlignment = CENTER
        }
    }

    /**
     * Reindex the selected project
     */
    private fun reindexSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a project to reindex.",
                "No Selection",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val project = projectTableModel.getProjectAt(selectedRow)
        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Do you want to reindex project '${project.name}'?\nThis operation may take several minutes.",
                "Confirm Reindexing",
                JOptionPane.YES_NO_OPTION,
            )

        if (confirm != JOptionPane.YES_OPTION) return

        // Disable the button to prevent multiple clicks
        reindexProjectButton.isEnabled = false
        reindexProjectButton.text = "Reindexing..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    indexingService.indexProject(project)
                }

                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "Project '${project.name}' has been successfully reindexed.",
                    "Reindexing Complete",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "Error during reindexing: ${e.message}",
                    "Reindexing Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            } finally {
                // Re-enable the button
                reindexProjectButton.isEnabled = true
                reindexProjectButton.text = "Reindex Project"
            }
        }
    }

    /**
     * Reindex all projects in the system
     */
    private fun reindexAllProjects() {
        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Do you want to reindex ALL projects in the system?\nThis operation may take a very long time and will process all projects.",
                "Confirm Full System Reindexing",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )

        if (confirm != JOptionPane.YES_OPTION) return

        // Disable both buttons to prevent multiple operations
        reindexProjectButton.isEnabled = false
        reindexAllButton.isEnabled = false
        reindexAllButton.text = "Reindexing All..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val allProjects = projectService.getAllProjects()
                    indexingService.indexAllProjects(allProjects)
                }

                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "All projects have been successfully reindexed.\nThe system will now reload project data.",
                    "Full Reindexing Complete",
                    JOptionPane.INFORMATION_MESSAGE,
                )

                // Reload projects to reflect any changes
                loadProjectsWithCoroutine()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ProjectSettingWindow,
                    "Error during full system reindexing: ${e.message}",
                    "Reindexing Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            } finally {
                // Re-enable both buttons
                reindexProjectButton.isEnabled = true
                reindexAllButton.isEnabled = true
                reindexAllButton.text = "Reindex All Projects"
            }
        }
    }
}
