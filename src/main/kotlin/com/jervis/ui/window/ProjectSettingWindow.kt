package com.jervis.ui.window

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.entity.mongo.ClientDocument
import com.jervis.service.project.ProjectService
import org.bson.types.ObjectId
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
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JComboBox
import javax.swing.DefaultListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Window for managing project settings.
 * Provides functionality to add, edit, delete, and manage projects.
 */
class ProjectSettingWindow(
    private val projectService: ProjectService,
    private val clientService: com.jervis.service.client.ClientService,
) : JFrame("Project Management") {
    
    private val projectTableModel = ProjectTableModel(emptyList())
    private val projectTable = JTable(projectTableModel)

    private val addButton = JButton("Add Project")
    private val editButton = JButton("Edit Project")
    private val deleteButton = JButton("Delete Project")
    private val activateButton = JButton("Activate Project")
    private val defaultButton = JButton("Set as Default")
    private val assignClientButton = JButton("Assign Client…")
    private val newClientButton = JButton("New Client…")
    private val dependenciesButton = JButton("Dependencies…")
    private val toggleDisabledButton = JButton("Toggle Disabled")

    private var activeProjectId: ObjectId? = null

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
        setSize(800, 600)
        setLocationRelativeTo(null)

        // Configure table
        projectTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        projectTable.setDefaultRenderer(Boolean::class.java, BooleanTableCellRenderer())

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

        mainPanel.add(tablePanel, BorderLayout.CENTER)
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)

        add(mainPanel)
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

        // Table selection listener
        projectTable.selectionModel.addListSelectionListener { updateButtonState() }

        // Double-click to edit
        projectTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    editSelectedProject()
                }
            }
        })

        // Add ESC key handling - ESC hides the window
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    isVisible = false // Hide the window
                }
            }
        })
        
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
        activateButton.isEnabled = hasSelection
        defaultButton.isEnabled = hasSelection

        if (hasSelection) {
            val selectedProject = projectTableModel.getProjectAt(projectTable.selectedRow)
            val activeProject = CoroutineScope(Dispatchers.Default).launch {
                val active = projectService.getActiveProject()
                withContext(Dispatchers.Main) {
                    activeProjectId = active?.id
                    // Activation button is enabled only if project is not active
                    activateButton.isEnabled = selectedProject.id != activeProjectId
                    // Default button is enabled only if project is not default
                    defaultButton.isEnabled = !selectedProject.isActive
                }
            }
        } else {
            activateButton.isEnabled = false
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
            val (name, path, description, isDefault) = result

            val newProject = ProjectDocument(
                name = name,
                path = path,
                description = description,
                isActive = false,
            )

            CoroutineScope(Dispatchers.Main).launch {
                projectService.saveProject(newProject, isDefault)
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

            val dialog = ProjectDialog(
                this,
                "Edit Project",
                project.name,
                project.path,
                project.description ?: "",
                project.isActive,
            )
            dialog.isVisible = true

            val result = dialog.result
            if (result != null) {
                val (name, path, description, isDefault) = result

                // Create updated project
                val updatedProject = project.copy(
                    name = name,
                    path = path,
                    description = description
                )

                // Save project and optionally set as default
                CoroutineScope(Dispatchers.Main).launch {
                    projectService.saveProject(updatedProject, isDefault)
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

            val result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete project '${project.name}'?",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION
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
            JOptionPane.showMessageDialog(this, "Please select a project first.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectTableModel.getProjectAt(selectedRow)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val clients = clientService.list()
                if (clients.isEmpty()) {
                    JOptionPane.showMessageDialog(this@ProjectSettingWindow, "No clients found. Create a client first.", "Info", JOptionPane.INFORMATION_MESSAGE)
                    return@launch
                }

                val dialog = JDialog(this@ProjectSettingWindow, "Assign Client", true)
                val combo = JComboBox<ClientDocument>()
                clients.forEach { combo.addItem(it) }
                combo.renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        val c = value as? ClientDocument
                        if (c != null) label.text = "${c.name} (${c.slug})"
                        return label
                    }
                }
                val okBtn = JButton("OK")
                val cancelBtn = JButton("Cancel")

                okBtn.addActionListener {
                    val selected = combo.selectedItem as? ClientDocument
                    if (selected == null) {
                        JOptionPane.showMessageDialog(dialog, "Please select a client.", "Validation", JOptionPane.WARNING_MESSAGE)
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val updated = project.copy(clientId = selected.id)
                                projectService.saveProject(updated, false)
                                loadProjects()
                            } catch (e: Exception) {
                                JOptionPane.showMessageDialog(dialog, "Failed to assign client: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
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
                JOptionPane.showMessageDialog(this@ProjectSettingWindow, "Error loading clients: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
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
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.LINE_END
        form.add(JLabel("Name:*"), gbc)
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.LINE_START; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        form.add(nameField, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.LINE_END
        form.add(JLabel("Slug:*"), gbc)
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.LINE_START; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
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
                JOptionPane.showMessageDialog(dialog, "Name is required.", "Validation", JOptionPane.WARNING_MESSAGE); return@addActionListener
            }
            if (!regex.matches(slug)) {
                JOptionPane.showMessageDialog(dialog, "Slug must match ^[a-z0-9-]+$", "Validation", JOptionPane.WARNING_MESSAGE); return@addActionListener
            }
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val client = ClientDocument(name = name, slug = slug)
                    clientService.create(client)
                    JOptionPane.showMessageDialog(dialog, "Client created.", "Success", JOptionPane.INFORMATION_MESSAGE)
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(dialog, "Failed to create client: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
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
            JOptionPane.showMessageDialog(this, "Please select a project first.", "Info", JOptionPane.INFORMATION_MESSAGE)
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
            list.cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(listComp: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                    val label = super.getListCellRendererComponent(listComp, value, index, isSelected, cellHasFocus) as JLabel
                    val p = value as? ProjectDocument
                    if (p != null) label.text = "${p.name} (${p.slug})"
                    return label
                }
            }
            val preselect = selectable.mapIndexedNotNull { idx, p -> if (project.dependsOnProjects.contains(p.id)) idx else null }.toIntArray()
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
                        JOptionPane.showMessageDialog(this@ProjectSettingWindow, "Dependencies saved.", "Info", JOptionPane.INFORMATION_MESSAGE)
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(this@ProjectSettingWindow, "Failed to save: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    } finally {
                        dialog.dispose()
                    }
                }
            }
            cancel.addActionListener { dialog.dispose() }

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = EmptyBorder(12, 12, 12, 12)
            panel.add(JScrollPane(list), BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(ok); add(cancel) }
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
            JOptionPane.showMessageDialog(this, "Please select a project first.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectTableModel.getProjectAt(selectedRow)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val updated = project.copy(isDisabled = !project.isDisabled)
                withContext(Dispatchers.IO) { projectService.saveProject(updated, false) }
                loadProjects()
                JOptionPane.showMessageDialog(this@ProjectSettingWindow, if (updated.isDisabled) "Project disabled." else "Project enabled.", "Info", JOptionPane.INFORMATION_MESSAGE)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ProjectSettingWindow, "Failed to toggle disabled: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    /**
     * Table model for projects
     */
    private class ProjectTableModel(
        private var projectList: List<ProjectDocument>
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

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val project = projectList[rowIndex]

            return when (columnIndex) {
                0 -> project.id?.toHexString() ?: ""
                1 -> project.name
                2 -> project.path
                3 -> project.description ?: ""
                4 -> project.clientId?.toHexString() ?: ""
                5 -> project.isDisabled
                6 -> project.isActive
                7 -> false // TODO: Implement current project logic
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0 -> String::class.java
                5, 6, 7 -> Boolean::class.java
                else -> String::class.java
            }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
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
        initialDescription: String = "",
        initialDefault: Boolean = false,
    ) : JDialog(owner, title, true) {
        private val nameField = JTextField(initialName).apply {
            preferredSize = Dimension(480, 30) // Min 480px width as required
            toolTipText = "Project display name."
        }
        private val pathField = JTextField(initialPath).apply {
            preferredSize = Dimension(480, 30) // Min 480px width as required
            toolTipText = "Absolute path to your project directory."
        }
        private val browseButton = JButton("Browse…")
        private val descriptionArea = JTextArea(initialDescription.ifEmpty { "Optional project description" }, 8, 50).apply {
            // Add placeholder functionality
            if (initialDescription.isEmpty()) {
                foreground = java.awt.Color.GRAY
                addFocusListener(object : java.awt.event.FocusAdapter() {
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
                })
            }
        }
        private val defaultCheckbox = JCheckBox("Set as default project", initialDefault)

        private val okButton = JButton("OK")
        private val cancelButton = JButton("Cancel")

        var result: ProjectResult? = null

        init {
            // Basic dialog setup
            preferredSize = Dimension(620, 480)
            minimumSize = Dimension(600, 440)
            isResizable = true
            defaultCloseOperation = DISPOSE_ON_CLOSE

            // Component setup
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true

            // Button actions
            okButton.addActionListener { saveAndClose() }
            cancelButton.addActionListener { dispose() }
            browseButton.addActionListener { browsePath() }

            // Build UI
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(16, 16, 16, 16)

            // Form panel with GridBagLayout (2 columns + buttons)
            val formPanel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(8, 8, 8, 8)

            // Project name (required)
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            formPanel.add(JLabel("Name:*"), gbc)

            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            formPanel.add(nameField, gbc)

            // Project path (required)
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            formPanel.add(JLabel("Local project folder:*"), gbc)

            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            formPanel.add(pathField, gbc)

            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            formPanel.add(browseButton, gbc)

            // Project description (optional)
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            formPanel.add(JLabel("Description:"), gbc)

            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            formPanel.add(JScrollPane(descriptionArea), gbc)

            // Default project checkbox
            gbc.gridx = 1
            gbc.gridy = 3
            gbc.gridwidth = 2
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            formPanel.add(defaultCheckbox, gbc)

            // Button panel
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)

            panel.add(formPanel, BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            add(panel)

            // Set focus order: nameField → pathField → browseButton → descriptionArea → defaultCheckbox → okButton → cancelButton
            val focusOrder = arrayOf(nameField, pathField, browseButton, descriptionArea, defaultCheckbox, okButton, cancelButton)
            for (i in 0 until focusOrder.size - 1) {
                focusOrder[i].nextFocusableComponent = focusOrder[i + 1]
            }

            // Add ESC key handling - ESC acts as Cancel
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        dispose() // Close dialog without saving
                    }
                }
            })
            
            // Make sure the dialog can receive key events
            isFocusable = true
        }

        private fun saveAndClose() {
            val name = nameField.text.trim()
            val path = pathField.text.trim()
            val description = if (descriptionArea.text.trim() == "Optional project description") "" else descriptionArea.text.trim()
            val isDefault = defaultCheckbox.isSelected

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
                JOptionPane.showMessageDialog(this, "Selected path is not a directory.", "Error", JOptionPane.ERROR_MESSAGE)
                return
            }

            result = ProjectResult(name, path, description, isDefault)
            dispose()
        }

        private fun browsePath() {
            val fileChooser = JFileChooser()
            fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            
            // Set current directory based on pathField content or user home
            val currentPath = pathField.text.trim()
            fileChooser.currentDirectory = if (currentPath.isNotEmpty()) {
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
        val description: String,
        val isDefault: Boolean
    )

    /**
     * Custom cell renderer for boolean values
     */
    private class BooleanTableCellRenderer : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            text = when (value as? Boolean) {
                true -> "Yes"
                false -> "No"
                else -> ""
            }
            horizontalAlignment = SwingConstants.CENTER
        }
    }
}