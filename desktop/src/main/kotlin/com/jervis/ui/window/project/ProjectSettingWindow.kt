package com.jervis.ui.window.project

import com.jervis.common.Constants
import com.jervis.dto.ClientDto
import com.jervis.dto.IndexingRulesDto
import com.jervis.dto.ProjectDto
import com.jervis.service.IClientService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IIntegrationSettingsService
import com.jervis.service.IJiraSetupService
import com.jervis.service.IProjectService
import com.jervis.ui.component.ProjectIntegrationPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

/**
 * Window for managing project settings.
 * Provides functionality to add, edit, delete, and manage projects.
 */
class ProjectSettingWindow(
    private val projectService: IProjectService,
    private val clientService: IClientService,
    private val gitConfigurationService: IGitConfigurationService,
    private val jiraSetupService: IJiraSetupService,
    private val integrationSettingsService: IIntegrationSettingsService,
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
    private val integrationButton = JButton("Integration…")

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
        buttonPanel.add(integrationButton)

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
        integrationButton.addActionListener { openIntegrationOverridesForSelectedProject() }

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

                            // Integration Overrides
                            val integrationItem = JMenuItem("Integration Overrides…")
                            integrationItem.addActionListener { openIntegrationOverridesForSelectedProject() }
                            contextMenu.add(integrationItem)

                            // Toggle Disabled
                            val toggleDisabledItem = JMenuItem("Toggle Disabled")
                            toggleDisabledItem.addActionListener { toggleDisabledForSelectedProject() }
                            contextMenu.add(toggleDisabledItem)

                            contextMenu.addSeparator()
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
        integrationButton.isEnabled = hasSelection
        if (hasSelection) {
            val selectedProject = projectTableModel.getProjectAt(projectTable.selectedRow)
            // Default button is enabled only if project is not default
            defaultButton.isEnabled = !selectedProject.isActive
        } else {
            defaultButton.isEnabled = false
        }
    }

    private fun openIntegrationOverridesForSelectedProject() {
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
        if (project.clientId == Constants.GLOBAL_ID_STRING) {
            JOptionPane.showMessageDialog(
                this,
                "Assign a client to this project first to configure integration overrides.",
                "Integration Overrides",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }

        val dialog = JDialog(this, "Integration Overrides – ${'$'}{project.name}", true)
        val panel = ProjectIntegrationPanel(project.id, integrationSettingsService, jiraSetupService)
        dialog.contentPane.add(JScrollPane(panel))
        dialog.setSize(600, 300)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
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
                ProjectDto(
                    clientId = Constants.GLOBAL_ID_STRING,
                    name = result.name,
                    projectPath = result.projectPath,
                    description = result.description,
                    languages = result.languages,
                    inspirationOnly = result.inspirationOnly,
                    indexingRules =
                        IndexingRulesDto(
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
                    project.description ?: "",
                    project.projectPath ?: "",
                    project.languages.joinToString(", "),
                    project.inspirationOnly,
                    project.indexingRules.includeGlobs.joinToString(", "),
                    project.indexingRules.excludeGlobs.joinToString(", "),
                    project.indexingRules.maxFileSizeMB,
                    project.isDisabled,
                    project.isActive,
                    project.overrides,
                )
            dialog.isVisible = true

            val result = dialog.result
            val gitOverrideRequest = dialog.gitOverrideRequest
            if (result != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        // First, setup Git override with credentials if provided
                        if (gitOverrideRequest != null) {
                            println("ProjectSettingWindow: Saving Git override for project ${project.id}")
                            println("  Has SSH Key: ${gitOverrideRequest.sshPrivateKey != null}")
                            println("  Has HTTPS Token: ${gitOverrideRequest.httpsToken != null}")

                            withContext(Dispatchers.IO) {
                                gitConfigurationService.setupGitOverrideForProject(
                                    project.id,
                                    gitOverrideRequest,
                                )
                            }

                            println("ProjectSettingWindow: Git override saved successfully")
                        }

                        // Then update project with other settings
                        val updatedProject =
                            project.copy(
                                name = result.name,
                                projectPath = result.projectPath,
                                description = result.description,
                                languages = result.languages,
                                inspirationOnly = result.inspirationOnly,
                                indexingRules =
                                    IndexingRulesDto(
                                        includeGlobs = result.includeGlobs,
                                        excludeGlobs = result.excludeGlobs,
                                        maxFileSizeMB = result.maxFileSizeMB,
                                    ),
                                isDisabled = result.isDisabled,
                                overrides = result.overrides,
                            )

                        // Save project and optionally set as default
                        projectService.saveProject(updatedProject, result.isDefault)
                        loadProjects()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        JOptionPane.showMessageDialog(
                            this@ProjectSettingWindow,
                            "Failed to save project: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
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
                val combo = JComboBox<ClientDto>()
                clients.forEach { combo.addItem(it) }
                combo.renderer =
                    object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean,
                        ): Component {
                            val label =
                                super.getListCellRendererComponent(
                                    list,
                                    value,
                                    index,
                                    isSelected,
                                    cellHasFocus,
                                ) as JLabel
                            val c = value as? ClientDto
                            if (c != null) label.text = c.name
                            return label
                        }
                    }
                val okBtn = JButton("OK")
                val cancelBtn = JButton("Cancel")

                okBtn.addActionListener {
                    val selected = combo.selectedItem as? ClientDto
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
                    clientService.createByName(name)
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
            val listModel = DefaultListModel<ProjectDto>()
            selectable.forEach { listModel.addElement(it) }
            val list = JList(listModel)
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

            list.cellRenderer =
                object : DefaultListCellRenderer() {
                    init {
                        // Ensure renderer is opaque to prevent background from painting over text
                        isOpaque = true
                    }

                    override fun getListCellRendererComponent(
                        listComp: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component {
                        val label =
                            super.getListCellRendererComponent(
                                listComp,
                                value,
                                index,
                                isSelected,
                                cellHasFocus,
                            ) as JLabel
                        val p = value as? ProjectDto
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
}
