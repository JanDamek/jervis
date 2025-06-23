package com.jervis.window

import com.jervis.entity.Project
import com.jervis.service.ProjectService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Okno pro správu projektů
 */
class ProjectSettingWindow(
    private val projectService: ProjectService,
) : JFrame("Správa projektů") {
    private val projectTableModel = ProjectTableModel(emptyList())
    private val projectTable = JTable(projectTableModel)

    private val addButton = JButton("Přidat projekt")
    private val editButton = JButton("Upravit projekt")
    private val deleteButton = JButton("Odstranit projekt")
    private val activateButton = JButton("Aktivovat projekt")
    private val defaultButton = JButton("Nastavit jako výchozí")
    private val uploadButton = JButton("Načíst do RAG") // Přesunuto sem aby bylo deklarováno před použitím

    init {
        // Základní nastavení okna
        setSize(900, 500) // Zvětšeno z 800 na 900 pro více prostoru
        setLocationRelativeTo(null)
        defaultCloseOperation = HIDE_ON_CLOSE

        // Nastavení tabulky
        setupTable()

        // Nastavení ovládacích prvků
        setupControls()

        // Sestavení UI
        val contentPane = JPanel(BorderLayout(10, 10))
        contentPane.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Panel tabulky s projekty
        val tableScrollPane = JScrollPane(projectTable)
        contentPane.add(tableScrollPane, BorderLayout.CENTER)

        // Panel tlačítek
        val buttonPanel = createButtonPanel()
        contentPane.add(buttonPanel, BorderLayout.SOUTH)

        setContentPane(contentPane)

        // Načtení projektů
        loadProjectsWithCoroutine()
    }

    /**
     * Nastavení tabulky projektů
     */
    private fun setupTable() {
        // Nastavení selekce
        projectTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Nastavení rozměrů sloupců
        projectTable.columnModel.getColumn(0).preferredWidth = 30 // ID
        projectTable.columnModel.getColumn(1).preferredWidth = 150 // Název
        projectTable.columnModel.getColumn(2).preferredWidth = 200 // Cesta
        projectTable.columnModel.getColumn(3).preferredWidth = 200 // Popis
        projectTable.columnModel.getColumn(4).preferredWidth = 80 // Výchozí
        projectTable.columnModel.getColumn(5).preferredWidth = 80 // Aktivní

        // Renderer pro checkboxy (centrování)
        val checkboxRenderer = DefaultTableCellRenderer()
        checkboxRenderer.horizontalAlignment = SwingConstants.CENTER
        projectTable.columnModel.getColumn(4).cellRenderer = checkboxRenderer
        projectTable.columnModel.getColumn(5).cellRenderer = checkboxRenderer

        // Listener pro výběr řádku
        projectTable.selectionModel.addListSelectionListener {
            CoroutineScope(Dispatchers.Main).launch {
                updateButtonState()
            }
        }

        // Double-click na řádek pro editaci
        projectTable.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && projectTable.selectedRow != -1) {
                        editSelectedProject()
                    }
                }
            },
        )
    }

    /**
     * Nastavení ovládacích prvků
     */
    private fun setupControls() {
        // Akce pro tlačítka
        addButton.addActionListener { addNewProject() }
        editButton.addActionListener { editSelectedProject() }
        deleteButton.addActionListener { deleteSelectedProject() }
        activateButton.addActionListener { activateSelectedProject() }
        defaultButton.addActionListener { setSelectedProjectAsDefault() }

        uploadButton.addActionListener { uploadProjectToRag() }

        // Počáteční stav tlačítek
        CoroutineScope(Dispatchers.Main).launch {
            updateButtonState()
        }
    }

    /**
     * Vytvoření panelu s tlačítky
     */
    private fun createButtonPanel(): JPanel {
        // Použijeme GridBagLayout místo FlowLayout pro lepší kontrolu nad rozložením
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.NONE

        // První řádek tlačítek
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(addButton, gbc)

        gbc.gridx = 1
        panel.add(editButton, gbc)

        gbc.gridx = 2
        panel.add(deleteButton, gbc)

        // Druhý řádek tlačítek
        gbc.gridx = 0
        gbc.gridy = 1
        panel.add(activateButton, gbc)

        gbc.gridx = 1
        panel.add(defaultButton, gbc)

        gbc.gridx = 2
        panel.add(uploadButton, gbc)

        return panel
    }

    /**
     * Aktualizace stavu tlačítek podle výběru
     */
    private suspend fun updateButtonState() {
        val isRowSelected = projectTable.selectedRow != -1

        editButton.isEnabled = isRowSelected
        deleteButton.isEnabled = isRowSelected
        uploadButton.isEnabled = isRowSelected // Změněno z false na isRowSelected, aby bylo aktivní při výběru řádku

        if (isRowSelected) {
            val selectedProject = projectTableModel.getProjectAt(projectTable.selectedRow)
            val activeProject = projectService.getActiveProject()

            // Tlačítko aktivace je povoleno, jen pokud projekt není aktivní
            activateButton.isEnabled = activeProject?.id != selectedProject.id

            // Tlačítko pro nastavení výchozího je povoleno, jen pokud projekt není výchozí
            defaultButton.isEnabled = !selectedProject.active
        } else {
            activateButton.isEnabled = false
            defaultButton.isEnabled = false
        }
    }

    /**
     * Načtení seznamu projektů
     */
    suspend fun loadProjects() {
        val projects = projectService.getAllProjects()
        projectTableModel.updateProjects(projects)
        updateButtonState()
    }

    /**
     * Načtení seznamu projektů s použitím coroutine
     */
    fun loadProjectsWithCoroutine() {
        CoroutineScope(Dispatchers.Main).launch {
            loadProjects()
        }
    }

    /**
     * Přidání nového projektu
     */
    private fun addNewProject() {
        val dialog = ProjectDialog(this, "Nový projekt")
        dialog.isVisible = true

        val result = dialog.result
        if (result != null) {
            val (name, path, description, isDefault) = result

            val newProject =
                Project(
                    name = name,
                    path = path,
                    description = description,
                    active = false, // Nastaveno služnou, pokud je potřeba
                )

            CoroutineScope(Dispatchers.Main).launch {
                projectService.saveProject(newProject, isDefault)
                loadProjects()
            }
        }
    }

    /**
     * Editace vybraného projektu
     */
    private fun editSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            val dialog =
                ProjectDialog(
                    this,
                    "Upravit projekt",
                    project.name,
                    project.path,
                    project.description ?: "",
                    project.active,
                )
            dialog.isVisible = true

            val result = dialog.result
            if (result != null) {
                val (name, path, description, isDefault) = result

                // Aktualizujeme projekt
                project.name = name
                project.path = path
                project.description = description

                // Uložíme projekt a případně nastavíme jako výchozí
                CoroutineScope(Dispatchers.Main).launch {
                    projectService.saveProject(project, isDefault)
                    loadProjects()
                }
            }
        }
    }

    /**
     * Odstranění vybraného projektu
     */
    private fun deleteSelectedProject() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)

            val confirm =
                JOptionPane.showConfirmDialog(
                    this,
                    "Opravdu chcete odstranit projekt '${project.name}'?",
                    "Odstranit projekt",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                )

            if (confirm == JOptionPane.YES_OPTION) {
                CoroutineScope(Dispatchers.Main).launch {
                    projectService.deleteProject(project)
                    loadProjects()
                }
            }
        }
    }

    /**
     * Aktivace vybraného projektu
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
     * Nastavení vybraného projektu jako výchozího
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
     * Zobrazení okna a načtení aktuálních dat
     */
    override fun setVisible(visible: Boolean) {
        if (visible) {
            loadProjectsWithCoroutine()
        }
        super.setVisible(visible)
    }

    /**
     * Načtení zdrojových kódů projektu do RAG
     */
    private fun uploadProjectToRag() {
        val selectedRow = projectTable.selectedRow
        if (selectedRow != -1) {
            val project = projectTableModel.getProjectAt(selectedRow)
            uploadButton.isEnabled = false
            uploadButton.text = "Probíhá načítání..." // Přidáno - informace o tom, že probíhá načítání

            CoroutineScope(Dispatchers.Default).launch {
                try {
                    projectService.uploadProjectSource(project)
                    withContext(Dispatchers.Main) {
                        JOptionPane.showMessageDialog(
                            this@ProjectSettingWindow,
                            "Načítání zdrojáků do RAG dokončeno.",
                            "Import dokončen",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                        uploadButton.text = "Načíst do RAG" // Obnovit původní text
                        uploadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        JOptionPane.showMessageDialog(
                            this@ProjectSettingWindow,
                            "Chyba při načítání: ${e.message}",
                            "Chyba importu",
                            JOptionPane.ERROR_MESSAGE,
                        )
                        uploadButton.text = "Načíst do RAG" // Obnovit původní text
                        uploadButton.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * Model tabulky projektů
     */
    private inner class ProjectTableModel(
        projects: List<Project>,
    ) : AbstractTableModel() {
        private val columns = arrayOf("ID", "Název", "Cesta", "Popis", "Výchozí", "Aktivní")
        private var projectList = projects.toMutableList()
        private var activeProjectId: Long? = null

        init {
            // Initialize the active project ID
            updateActiveProjectId()
        }

        fun updateProjects(projects: List<Project>) {
            projectList = projects.toMutableList()
            updateActiveProjectId()
            fireTableDataChanged()
        }

        private fun updateActiveProjectId() {
            CoroutineScope(Dispatchers.Main).launch {
                activeProjectId = projectService.getActiveProject()?.id
                fireTableDataChanged()
            }
        }

        fun getProjectAt(row: Int): Project = projectList[row]

        override fun getRowCount(): Int = projectList.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val project = projectList[rowIndex]

            return when (columnIndex) {
                0 -> project.id ?: 0
                1 -> project.name
                2 -> project.path
                3 -> project.description ?: ""
                4 -> project.active
                5 -> project.id == activeProjectId
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0 -> Long::class.java
                4, 5 -> Boolean::class.java
                else -> String::class.java
            }

        override fun isCellEditable(
            rowIndex: Int,
            columnIndex: Int,
        ): Boolean {
            return false // Všechny buňky jsou needitovatelné přímo v tabulce
        }
    }

    /**
     * Dialog pro přidání/editaci projektu
     */
    private class ProjectDialog(
        owner: JFrame,
        title: String,
        initialName: String = "",
        initialPath: String = "",
        initialDescription: String = "",
        initialDefault: Boolean = false,
    ) : JDialog(owner, title, true) {
        private val nameField = JTextField(initialName, 30)
        private val pathField = JTextField(initialPath, 30)
        private val browseButton = JButton("Procházet...")
        private val descriptionArea = JTextArea(initialDescription, 5, 30)
        private val defaultCheckbox = JCheckBox("Nastavit jako výchozí projekt", initialDefault)

        private val okButton = JButton("OK")
        private val cancelButton = JButton("Zrušit")

        var result: ProjectResult? = null

        init {
            // Základní nastavení dialogu
            setSize(500, 350)
            setLocationRelativeTo(owner)
            defaultCloseOperation = DISPOSE_ON_CLOSE

            // Nastavení komponent
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true

            // Akce pro tlačítka
            okButton.addActionListener { saveAndClose() }
            cancelButton.addActionListener { dispose() }
            browseButton.addActionListener { browsePath() }

            // Sestavení UI
            val panel = JPanel(BorderLayout(10, 10))
            panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            // Formulář
            val formPanel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = Insets(5, 5, 5, 5)

            // Název projektu
            gbc.gridx = 0
            gbc.gridy = 0
            formPanel.add(JLabel("Název:"), gbc)

            gbc.gridx = 1
            gbc.gridwidth = 2
            formPanel.add(nameField, gbc)

            // Cesta k projektu
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.gridwidth = 1
            formPanel.add(JLabel("Cesta:"), gbc)

            gbc.gridx = 1
            formPanel.add(pathField, gbc)

            gbc.gridx = 2
            gbc.fill = GridBagConstraints.NONE
            formPanel.add(browseButton, gbc)

            // Popis projektu
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.fill = GridBagConstraints.HORIZONTAL
            formPanel.add(JLabel("Popis:"), gbc)

            gbc.gridx = 1
            gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            formPanel.add(JScrollPane(descriptionArea), gbc)

            // Checkbox pro výchozí projekt
            gbc.gridx = 0
            gbc.gridy = 3
            gbc.gridwidth = 3
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weighty = 0.0
            formPanel.add(defaultCheckbox, gbc)

            panel.add(formPanel, BorderLayout.CENTER)

            // Panel tlačítek
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            contentPane = panel

            // Nastavení fokusu
            nameField.requestFocusInWindow()
        }

        /**
         * Otevře dialog pro výběr adresáře
         */
        private fun browsePath() {
            val fileChooser = JFileChooser(pathField.text.takeIf { it.isNotEmpty() })
            fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            fileChooser.dialogTitle = "Vyberte adresář projektu"

            val result = fileChooser.showOpenDialog(this)
            if (result == JFileChooser.APPROVE_OPTION) {
                pathField.text = fileChooser.selectedFile.absolutePath
            }
        }

        /**
         * Uloží data a zavře dialog
         */
        private fun saveAndClose() {
            val name = nameField.text.trim()
            val path = pathField.text.trim()

            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Název projektu nemůže být prázdný.",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }

            if (path.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Cesta k projektu nemůže být prázdná.",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }

            result =
                ProjectResult(
                    name = name,
                    path = path,
                    description = descriptionArea.text.trim(),
                    isDefault = defaultCheckbox.isSelected,
                )

            dispose()
        }

        /**
         * Data projektu vrácená dialogem
         */
        data class ProjectResult(
            val name: String,
            val path: String,
            val description: String,
            val isDefault: Boolean,
        )
    }
}
