package com.jervis.ui.window

import com.jervis.entity.mongo.ProjectDocument
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
) : JFrame("Project Management") {
    
    private val projectTableModel = ProjectTableModel(emptyList())
    private val projectTable = JTable(projectTableModel)

    private val addButton = JButton("Add Project")
    private val editButton = JButton("Edit Project")
    private val deleteButton = JButton("Delete Project")
    private val activateButton = JButton("Activate Project")
    private val defaultButton = JButton("Set as Default")

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

    /**
     * Table model for projects
     */
    private class ProjectTableModel(
        private var projectList: List<ProjectDocument>
    ) : AbstractTableModel() {

        private val columns = arrayOf("ID", "Name", "Path", "Description", "Active", "Is Current")

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
                4 -> project.isActive
                5 -> false // TODO: Implement current project logic
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0 -> String::class.java
                4, 5 -> Boolean::class.java
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