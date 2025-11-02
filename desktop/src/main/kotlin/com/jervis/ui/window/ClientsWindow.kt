package com.jervis.ui.window

import com.jervis.common.Constants.Companion.GLOBAL_ID_STRING
import com.jervis.dto.ClientDto
import com.jervis.dto.ClientProjectLinkDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.ProjectDto
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IEmailAccountService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IProjectService
import com.jervis.ui.component.ConfluenceSettingsPanel
import com.jervis.ui.component.EmailConfigPanel
import com.jervis.ui.component.GitSetupPanel
import com.jervis.ui.component.JiraSetupPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Swing window for managing Clients and adding Projects to a selected Client.
 */
class ClientsWindow(
    private val clientService: IClientService,
    private val gitConfigurationService: IGitConfigurationService,
    private val projectService: IProjectService,
    private val linkService: IClientProjectLinkService,
    private val emailAccountService: IEmailAccountService,
    private val jiraSetupService: com.jervis.service.IJiraSetupService,
    private val integrationSettingsService: com.jervis.service.IIntegrationSettingsService,
) : JFrame("Client Management") {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val clientsList = JList<ClientDto>()
    private val listModel = javax.swing.DefaultListModel<ClientDto>()

    private val nameField = JTextField()
    private val descriptionField = JTextField()
    private val anonymizeCheckbox = JCheckBox("Client disabled")

    private val saveBtn = JButton("Save Client")
    private val settingsBtn = JButton("Client Settings…")
    private val newClientWithSettingsBtn = JButton("New Client with Settings…")
    private val newBtn = JButton("New Client")
    private val deleteBtn = JButton("Delete Client")
    private val addProjectBtn = JButton("Add New Project…")
    private val assignExistingBtn = JButton("Assign Existing Projects…")
    private val dependenciesBtn = JButton("Dependencies…")

    private val projectsList = JList<ProjectDto>()
    private val projectsModel = javax.swing.DefaultListModel<ProjectDto>()
    private val removeProjectBtn = JButton("Remove from Client")
    private val toggleDisableForClientBtn = JButton("Toggle Disable for Client")
    private val toggleAnonymizeForClientBtn = JButton("Toggle Anonymization for Client")
    private val toggleHistoricalForClientBtn = JButton("Toggle Historical for Client")

    private var selectedClientId: String? = null
    private var clientDependencies: MutableSet<String> = mutableSetOf()
    private var currentClient: ClientDto? = null
    private var linksByProject: Map<String, ClientProjectLinkDto> = emptyMap()

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(900, 520)
        layout = BorderLayout(10, 10)

        val mainPanel =
            JPanel(BorderLayout(10, 10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            }

        // Left: clients list
        val leftPanel = JPanel(BorderLayout(6, 6))
        val leftHeader = JPanel(BorderLayout())
        leftHeader.add(JLabel("Clients").apply { horizontalAlignment = SwingConstants.LEFT }, BorderLayout.WEST)
        val leftButtons =
            JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(newBtn)
                add(newClientWithSettingsBtn)
                add(deleteBtn)
            }
        leftHeader.add(leftButtons, BorderLayout.EAST)
        leftPanel.add(leftHeader, BorderLayout.NORTH)

        clientsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        clientsList.model = listModel
        clientsList.cellRenderer =
            object : javax.swing.DefaultListCellRenderer() {
                init {
                    // Ensure renderer is opaque to prevent background from painting over text
                    isOpaque = true
                }

                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val label =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                    val c = value as? ClientDto
                    label.text = c?.name ?: ""
                    return label
                }
            }
        clientsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val sel = clientsList.selectedValue
                if (sel != null) {
                    selectedClientId = sel.id
                    currentClient = sel
                    fillClientForm(sel)
                    loadProjectsForClient(sel.id)
                    projectsList.repaint()
                }
            }
        }
        leftPanel.add(JScrollPane(clientsList), BorderLayout.CENTER)

        // Right: detail and projects
        val rightPanel = JPanel(BorderLayout(10, 10))

        // Client detail form
        val formPanel = JPanel(GridBagLayout())
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(6, 8, 6, 8)
                fill = GridBagConstraints.HORIZONTAL
                gridx = 0
                gridy = 0
            }

        fun addRow(
            label: String,
            field: JTextField,
        ) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.LINE_END
            formPanel.add(JLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            formPanel.add(field.apply { preferredSize = Dimension(420, 28) }, gbc)
            gbc.gridy++
        }

        addRow("Name:*", nameField)
        addRow("Description:", descriptionField)
        // Anonymization toggle
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.NONE
        formPanel.add(anonymizeCheckbox, gbc)
        gbc.gridy++

        val formButtons =
            JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(saveBtn)
                add(settingsBtn)
                add(addProjectBtn)
                add(assignExistingBtn)
                add(dependenciesBtn)
            }

        val formWrapper =
            JPanel(BorderLayout(6, 6)).apply {
                border = EmptyBorder(8, 8, 8, 8)
                add(formPanel, BorderLayout.CENTER)
                add(formButtons, BorderLayout.SOUTH)
            }

        // Projects section
        val projectsPanel =
            JPanel(BorderLayout(6, 6)).apply {
                border = EmptyBorder(8, 8, 8, 8)
            }
        val projHeader = JPanel(BorderLayout())
        projHeader.add(JLabel("Projects of Selected Client"), BorderLayout.WEST)
        val projBtns =
            JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(removeProjectBtn)
                add(toggleDisableForClientBtn)
                add(toggleAnonymizeForClientBtn)
                add(toggleHistoricalForClientBtn)
            }
        projHeader.add(projBtns, BorderLayout.EAST)
        projectsList.model = projectsModel

        projectsPanel.add(projHeader, BorderLayout.NORTH)
        projectsPanel.add(JScrollPane(projectsList), BorderLayout.CENTER)

        rightPanel.add(formWrapper, BorderLayout.NORTH)
        rightPanel.add(projectsPanel, BorderLayout.CENTER)

        mainPanel.add(leftPanel, BorderLayout.WEST)
        mainPanel.add(rightPanel, BorderLayout.CENTER)
        add(mainPanel, BorderLayout.CENTER)

        // Wire actions
        newBtn.addActionListener {
            clearClientForm()
            clientsList.clearSelection()
            selectedClientId =
                null
            clientDependencies.clear()
            anonymizeCheckbox.isSelected = false
        }
        newClientWithSettingsBtn.addActionListener { createNewClientWithSettings() }
        deleteBtn.addActionListener { deleteSelectedClient() }
        saveBtn.addActionListener { saveClient() }
        settingsBtn.addActionListener { showClientSettingsDialog() }
        addProjectBtn.addActionListener { showAddProjectDialog() }
        assignExistingBtn.addActionListener { showAssignExistingProjectsDialog() }
        dependenciesBtn.addActionListener { showClientDependenciesDialog() }
        removeProjectBtn.addActionListener { removeSelectedProjectFromClient() }
        toggleDisableForClientBtn.addActionListener { toggleDisableForClientForSelectedProject() }
        toggleAnonymizeForClientBtn.addActionListener { toggleAnonymizeForClientForSelectedProject() }
        toggleHistoricalForClientBtn.addActionListener { toggleHistoricalForClientForSelectedProject() }

        // Load data
        loadClients()
    }

    private fun loadClients() {
        CoroutineScope(Dispatchers.Main).launch {
            val clients = withContext(Dispatchers.IO) { clientService.list() }
            listModel.clear()
            clients.forEach { listModel.addElement(it) }
        }
    }

    private fun isSshUrl(url: String?): Boolean = url?.let { it.startsWith("ssh://") || it.contains("@") } == true

    private fun fillClientForm(c: ClientDto) {
        nameField.text = c.name
        descriptionField.text = c.fullDescription ?: ""
        anonymizeCheckbox.toolTipText = "If checked, the client is temporarily disabled."
        anonymizeCheckbox.isSelected = c.isDisabled
        clientDependencies = c.dependsOnProjects.toMutableSet()
    }

    private fun clearClientForm() {
        nameField.text = ""
        descriptionField.text = ""
        anonymizeCheckbox.isSelected = false
        clientDependencies.clear()
        currentClient = null
        projectsModel.clear()
    }

    private fun saveClient() {
        val name = nameField.text.trim()
        val desc = descriptionField.text.trim().ifEmpty { null }
        val disabled = anonymizeCheckbox.isSelected
        if (name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Name is required.", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val id = selectedClientId
                val savedClient: ClientDto =
                    if (id == null) {
                        val c =
                            ClientDto(
                                name = name,
                                fullDescription = desc,
                                isDisabled = disabled,
                                dependsOnProjects = clientDependencies.toList(),
                            )
                        withContext(Dispatchers.IO) { clientService.create(c) }
                    } else {
                        val existing = withContext(Dispatchers.IO) { clientService.getClientById(id) }
                        if (existing == null) {
                            JOptionPane.showMessageDialog(
                                this@ClientsWindow,
                                "Client not found.",
                                "Error",
                                JOptionPane.ERROR_MESSAGE,
                            )
                            return@launch
                        }
                        val updated =
                            existing.copy(
                                name = name,
                                fullDescription = desc,
                                isDisabled = disabled,
                                dependsOnProjects = clientDependencies.toList(),
                            )
                        withContext(Dispatchers.IO) { clientService.update(updated) }
                    }

                // Full reload from backend after edit
                loadClients()
                // Reselect the saved client and refresh its details and projects
                selectedClientId = savedClient.id
                currentClient =
                    withContext(Dispatchers.IO) { clientService.getClientById(savedClient.id) } ?: savedClient
                // Update form and linked projects list
                fillClientForm(currentClient!!)
                loadProjectsForClient(savedClient.id)

                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Client saved.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Error saving client: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun deleteSelectedClient() {
        val sel =
            clientsList.selectedValue ?: run {
                JOptionPane.showMessageDialog(this, "Please select a client.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Really delete client '${sel.name}'?",
                "Confirmation",
                JOptionPane.YES_NO_OPTION,
            )
        if (confirm != JOptionPane.YES_OPTION) return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { clientService.delete(sel.id) }
                clearClientForm()
                projectsModel.clear()
                selectedClientId = null
                loadClients()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Delete error: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun loadProjectsForClient(clientId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val all = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            val forClient = all.filter { it.clientId == clientId }
            // load link flags for this client
            linksByProject =
                withContext(Dispatchers.IO) {
                    linkService.listForClient(clientId).associateBy { it.projectId }
                }
            projectsModel.clear()
            forClient.forEach { p ->
                projectsModel.addElement(p)
            }
            projectsList.model = projectsModel
            projectsList.cellRenderer =
                object : javax.swing.DefaultListCellRenderer() {
                    init {
                        // Ensure renderer is opaque to prevent background from painting over text
                        isOpaque = true
                    }

                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): java.awt.Component {
                        val label =
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        val p = value as? ProjectDto
                        if (p != null) {
                            val markers = mutableListOf<String>()
                            if (p.isDisabled) markers.add("DISABLED")
                            val link = linksByProject[p.id]
                            if (link != null) {
                                if (link.isDisabled) markers.add("CLIENT OFF")
                                markers.add(if (link.anonymizationEnabled) "ANON" else "ANON OFF")
                                if (link.historical) markers.add("HIST")
                            }
                            val suffix = if (markers.isNotEmpty()) " [" + markers.joinToString(", ") + "]" else ""
                            label.text = "${p.name}$suffix"
                        } else {
                            label.text = ""
                        }
                        return label
                    }
                }
        }
    }

    private fun showAssignExistingProjectsDialog() {
        val clientId =
            selectedClientId ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Please select a client first.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        CoroutineScope(Dispatchers.Main).launch {
            val allProjects = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            if (allProjects.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "No projects available.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return@launch
            }

            val dialog = JDialog(this@ClientsWindow, "Assign existing projects", true)
            val listModel = javax.swing.DefaultListModel<ProjectDto>()
            allProjects.forEach { listModel.addElement(it) }
            val list = JList(listModel)
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

            list.cellRenderer =
                object : javax.swing.DefaultListCellRenderer() {
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
                    ): java.awt.Component {
                        val label =
                            super.getListCellRendererComponent(
                                listComp,
                                value,
                                index,
                                isSelected,
                                cellHasFocus,
                            ) as JLabel
                        val p = value as? ProjectDto
                        if (p != null) {
                            val assigned = p.clientId
                            val suffix =
                                if (assigned != null && assigned != clientId) " — assigned to a different client" else ""
                            label.text = "${p.name}$suffix"
                        }
                        return label
                    }
                }
            val ok = JButton("Assign")
            val cancel = JButton("Cancel")
            ok.addActionListener {
                val selected = list.selectedValuesList
                if (selected.isEmpty()) {
                    dialog.dispose()
                    return@addActionListener
                }
                val conflicting = selected.any { it.clientId != null && it.clientId != clientId }
                if (conflicting) {
                    val res =
                        JOptionPane.showConfirmDialog(
                            dialog,
                            "Some selected projects are already assigned to a different client. Do you want to reassign them?",
                            "Confirmation",
                            JOptionPane.YES_NO_OPTION,
                        )
                    if (res != JOptionPane.YES_OPTION) return@addActionListener
                }
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        for (p in selected) {
                            val updated = p.copy(clientId = clientId)
                            withContext(Dispatchers.IO) { projectService.saveProject(updated, false) }
                        }
                        loadProjectsForClient(clientId)
                        JOptionPane.showMessageDialog(
                            this@ClientsWindow,
                            "Projekty přiřazeny.",
                            "Info",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(
                            this@ClientsWindow,
                            "Chyba přiřazení: ${e.message}",
                            "Chyba",
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
            dialog.setLocationRelativeTo(this@ClientsWindow)
            dialog.isVisible = true
        }
    }

    private fun showClientDependenciesDialog() {
        CoroutineScope(Dispatchers.Main).launch {
            val allProjects = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            val dialog = JDialog(this@ClientsWindow, "Client dependencies (select projects)", true)
            val listModel = javax.swing.DefaultListModel<ProjectDto>()
            allProjects.forEach { listModel.addElement(it) }
            val list = JList(listModel)
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

            list.cellRenderer =
                object : javax.swing.DefaultListCellRenderer() {
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
                    ): java.awt.Component {
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
            // Preselect existing dependencies
            val preselectIndices =
                allProjects
                    .mapIndexedNotNull { idx, p -> if (clientDependencies.contains(p.id)) idx else null }
                    .toIntArray()
            list.selectedIndices = preselectIndices

            val ok = JButton("OK")
            val cancel = JButton("Zrušit")
            ok.addActionListener {
                val selected = list.selectedValuesList
                clientDependencies = selected.map { it.id }.toMutableSet()
                dialog.dispose()
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Závislosti aktualizovány, pro uložení stiskněte 'Uložit klienta'.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
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
            dialog.setLocationRelativeTo(this@ClientsWindow)
            dialog.isVisible = true
        }
    }

    private fun removeSelectedProjectFromClient() {
        val clientId =
            selectedClientId ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Please select a client first.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        val project =
            projectsList.selectedValue ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Vyberte projekt ze seznamu.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Unassign project from client
                val updatedProject = project.copy(clientId = GLOBAL_ID_STRING)
                withContext(Dispatchers.IO) {
                    projectService.saveProject(updatedProject, false)
                    // Delete link settings for this client-project pair
                    linkService.delete(clientId, project.id)
                }
                loadProjectsForClient(clientId)
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Project removed from client.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Removal error: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun toggleDisableForClientForSelectedProject() {
        val client =
            currentClient ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val project =
            projectsList.selectedValue ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Vyberte projekt ze seznamu.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleDisabled(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Chyba změny stavu: ${e.message}",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun toggleAnonymizeForClientForSelectedProject() {
        val client =
            currentClient ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val project =
            projectsList.selectedValue ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Vyberte projekt ze seznamu.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleAnonymization(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Chyba změny stavu: ${e.message}",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun toggleHistoricalForClientForSelectedProject() {
        val client =
            currentClient ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val project =
            projectsList.selectedValue ?: run {
                JOptionPane.showMessageDialog(
                    this,
                    "Vyberte projekt ze seznamu.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleHistorical(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Chyba změny stavu: ${e.message}",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    private fun showAddProjectDialog() {
        val clientId =
            selectedClientId ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }
        val dialog = JDialog(this, "Nový projekt pro klienta", true)

        val nameField = JTextField(30)
        val slugField = JTextField(30)
        val pathField = JTextField(30)
        val urlField = JTextField(30)
        val descField = JTextField(30)

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(6, 8, 6, 8) }

        fun add(
            label: String,
            comp: JTextField,
            x: Int,
            y: Int,
        ) {
            gbc.gridx = x
            gbc.gridy = y
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(label), gbc)
            gbc.gridx = x + 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            form.add(comp.apply { preferredSize = Dimension(420, 28) }, gbc)
        }
        add("Name:*", nameField, 0, 0)
        add("Slug:*", slugField, 0, 1)
        add("Path (legacy):", pathField, 0, 2)
        add("Repo primaryUrl:*", urlField, 0, 3)
        add("Description:", descField, 0, 4)

        val ok = JButton("Create")
        val cancel = JButton("Cancel")
        val buttons =
            JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(ok)
                add(cancel)
            }

        val panel =
            JPanel(BorderLayout(8, 8)).apply {
                border = EmptyBorder(12, 12, 12, 12)
                add(form, BorderLayout.CENTER)
                add(buttons, BorderLayout.SOUTH)
            }
        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(this)

        ok.addActionListener {
            val name = nameField.text.trim()
            val slug = slugField.text.trim()
            val url = urlField.text.trim()
            val desc = descField.text.trim().ifEmpty { null }
            val regex = Regex("^[a-z0-9-]+$")
            if (name.isBlank()) {
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
            if (url.isBlank()) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Repo primaryUrl is required.",
                    "Validation",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val project =
                        ProjectDto(
                            id = GLOBAL_ID_STRING,
                            clientId = clientId,
                            name = name,
                            description = desc,
                            isActive = false,
                        )
                    withContext(Dispatchers.IO) { projectService.saveProject(project, false) }
                    loadProjectsForClient(clientId)
                    JOptionPane.showMessageDialog(
                        this@ClientsWindow,
                        "Projekt vytvořen.",
                        "Info",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        dialog,
                        "Chyba vytvoření projektu: ${e.message}",
                        "Chyba",
                        JOptionPane.ERROR_MESSAGE,
                    )
                } finally {
                    dialog.dispose()
                }
            }
        }
        cancel.addActionListener { dialog.dispose() }

        dialog.isVisible = true
    }

    private fun createNewClientWithSettings() {
        val dialog = NewClientWithSettingsDialog(this)
        dialog.isVisible = true

        val gitSetupRequest = dialog.gitSetupRequest
        val result = dialog.result
        if (result != null && gitSetupRequest != null) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    logger.info { "Creating new client with Git configuration" }
                    logger.debug { "  Client name: ${result.name}" }
                    logger.debug { "  Provider: ${gitSetupRequest.gitProvider}" }
                    logger.debug { "  Auth Type: ${gitSetupRequest.gitAuthType}" }
                    logger.debug { "  Has SSH Key: ${gitSetupRequest.sshPrivateKey != null}" }

                    // First create the client
                    val createdClient =
                        withContext(Dispatchers.IO) {
                            clientService.create(result)
                        }

                    logger.info { "Client created with ID: ${createdClient.id}" }

                    // Optionally test SSH connectivity when URL is known
                    if (isSshUrl(gitSetupRequest.monoRepoUrl)) {
                        val testResponse =
                            withContext(Dispatchers.IO) {
                                gitConfigurationService.testConnection(createdClient.id, gitSetupRequest)
                            }
                        val success = (testResponse.body?.get("success") as? Boolean) == true
                        if (!success) {
                            JOptionPane.showMessageDialog(
                                this@ClientsWindow,
                                "SSH connection test failed. Please verify the repository URL and credentials.",
                                "Git Connection",
                                JOptionPane.ERROR_MESSAGE,
                            )
                            return@launch
                        }
                    }

                    // Then setup Git configuration with credentials
                    withContext(Dispatchers.IO) {
                        gitConfigurationService.setupGitConfiguration(createdClient.id, gitSetupRequest)
                    }

                    logger.info { "Git configuration saved for new client" }

                    loadClients()
                    JOptionPane.showMessageDialog(
                        this@ClientsWindow,
                        "Klient '${result.name}' byl úspěšně vytvořen s kompletním nastavením.",
                        "Úspěch",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        this@ClientsWindow,
                        "Chyba při vytváření klienta: ${e.message}",
                        "Chyba",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun showClientSettingsDialog() {
        val client =
            currentClient ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }

        // Load existing credentials first
        var existingCredentials: GitCredentialsDto? = null
        CoroutineScope(Dispatchers.Main).launch {
            try {
                existingCredentials =
                    withContext(Dispatchers.IO) {
                        gitConfigurationService.getGitCredentials(client.id)
                    }

                // Create and show dialog with credentials
                val dialog =
                    ClientSettingsDialog(
                        this@ClientsWindow,
                        client,
                        existingCredentials,
                        emailAccountService,
                        jiraSetupService,
                        integrationSettingsService,
                    )
                dialog.isVisible = true
                handleDialogResult(client, dialog)
            } catch (e: Exception) {
                logger.warn { "Failed to load credentials: ${e.message}" }
                // Create dialog without credentials on error
                val dialog =
                    ClientSettingsDialog(
                        this@ClientsWindow,
                        client,
                        null,
                        emailAccountService,
                        jiraSetupService,
                        integrationSettingsService,
                    )
                dialog.isVisible = true
                handleDialogResult(client, dialog)
            }
        }
    }

    private fun handleDialogResult(
        client: ClientDto,
        dialog: ClientSettingsDialog,
    ) {
        val gitSetupRequest = dialog.gitSetupRequest
        val result = dialog.result
        if (result != null && gitSetupRequest != null) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    logger.info { "Saving Git configuration for client ${client.id}" }
                    logger.debug { "  Provider: ${gitSetupRequest.gitProvider}" }
                    logger.debug { "  Auth Type: ${gitSetupRequest.gitAuthType}" }
                    logger.debug { "  Has SSH Key: ${gitSetupRequest.sshPrivateKey != null}" }
                    logger.debug { "  Has HTTPS Token: ${gitSetupRequest.httpsToken != null}" }
                    logger.debug { "  Has GPG Key: ${gitSetupRequest.gpgPrivateKey != null}" }

                    // Optionally test SSH connectivity when URL is known
                    if (isSshUrl(gitSetupRequest.monoRepoUrl)) {
                        val testResponse =
                            withContext(Dispatchers.IO) {
                                gitConfigurationService.testConnection(client.id, gitSetupRequest)
                            }
                        val success = (testResponse.body?.get("success") as? Boolean) == true
                        if (!success) {
                            JOptionPane.showMessageDialog(
                                this@ClientsWindow,
                                "SSH connection test failed. Please verify the repository URL and credentials.",
                                "Git Connection",
                                JOptionPane.ERROR_MESSAGE,
                            )
                            return@launch
                        }
                    }

                    // First setup Git configuration with credentials
                    withContext(Dispatchers.IO) {
                        gitConfigurationService.setupGitConfiguration(client.id, gitSetupRequest)
                    }

                    logger.info { "Git configuration saved successfully" }

                    // Then update other client settings
                    val finalClient =
                        withContext(Dispatchers.IO) {
                            clientService.update(result.copy(id = client.id))
                        }

                    currentClient = finalClient
                    fillClientForm(finalClient)
                    JOptionPane.showMessageDialog(
                        this@ClientsWindow,
                        "Nastavení klienta bylo uloženo.",
                        "Info",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        this@ClientsWindow,
                        "Chyba při ukládání nastavení: ${e.message}",
                        "Chyba",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    /**
     * Dialog for creating a new client with comprehensive settings
     */
    private class NewClientWithSettingsDialog(
        owner: JFrame,
    ) : JDialog(owner, "Nový klient s kompletním nastavením", true) {
        private val nameField =
            JTextField().apply {
                preferredSize = Dimension(400, 30)
                toolTipText = "Client name (required)"
            }

        private val descriptionField =
            JTextField().apply {
                preferredSize = Dimension(400, 30)
                toolTipText = "Popis klienta (nepovinné)"
            }

        private val isDisabledCheckbox =
            JCheckBox("Klient zakázán").apply {
                toolTipText = "Pokud je zaškrtnuto, klient je dočasně zakázán"
            }

        private val gitSetupPanel = GitSetupPanel()

        private val okButton = JButton("Create Client")
        private val cancelButton = JButton("Zrušit")

        var result: ClientDto? = null
        var gitSetupRequest: com.jervis.dto.GitSetupRequestDto? = null

        init {
            preferredSize = Dimension(850, 750)
            minimumSize = Dimension(800, 700)
            isResizable = true
            defaultCloseOperation = DISPOSE_ON_CLOSE

            setupLayout()
            setupEventHandlers()
        }

        private fun setupLayout() {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(16, 16, 16, 16)

            val tabbedPane = javax.swing.JTabbedPane()

            // Basic Information Tab
            val basicPanel = createBasicInfoPanel()
            tabbedPane.addTab("Základní informace", basicPanel)

            // Git Configuration Tab
            tabbedPane.addTab("Git Configuration", JScrollPane(gitSetupPanel))

            // Button panel
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)

            panel.add(tabbedPane, BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            add(panel)
        }

        private fun createBasicInfoPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
            var row = 0

            // Name
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Name:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(nameField, gbc)
            row++

            // Description
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Popis:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(descriptionField, gbc)
            row++

            // Disabled checkbox
            gbc.gridx = 1
            gbc.gridy = row
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

        private fun setupEventHandlers() {
            okButton.addActionListener { saveAndClose() }
            cancelButton.addActionListener { dispose() }

            // ESC key handling
            addKeyListener(
                object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                            dispose()
                        }
                    }
                },
            )

            isFocusable = true
        }

        private fun saveAndClose() {
            val name = nameField.text.trim()
            val description = descriptionField.text.trim().ifEmpty { null }
            val isDisabled = isDisabledCheckbox.isSelected

            if (name.isBlank()) {
                JOptionPane.showMessageDialog(this, "Name is required.", "Validation", JOptionPane.WARNING_MESSAGE)
                return
            }

            if (!gitSetupPanel.validateFields()) {
                return
            }

            try {
                val gitRequest = gitSetupPanel.toGitSetupRequest()

                val newClient =
                    ClientDto(
                        id = GLOBAL_ID_STRING,
                        name = name,
                        fullDescription = description,
                        gitProvider = gitRequest.gitProvider,
                        gitAuthType = gitRequest.gitAuthType,
                        monoRepoUrl = gitRequest.monoRepoUrl,
                        defaultBranch = gitRequest.defaultBranch,
                        gitConfig = gitRequest.gitConfig,
                        isDisabled = isDisabled,
                    )

                gitSetupRequest = gitRequest
                result = newClient
                dispose()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Error validating settings: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    /**
     * Dialog for comprehensive client settings configuration
     */
    private class ClientSettingsDialog(
        owner: JFrame,
        private val client: ClientDto,
        private val existingCredentials: GitCredentialsDto?,
        private val emailAccountService: IEmailAccountService,
        private val jiraSetupService: com.jervis.service.IJiraSetupService,
        private val integrationSettingsService: com.jervis.service.IIntegrationSettingsService,
    ) : JDialog(owner, "Client Settings: ${client.name}", true) {
        private val gitSetupPanel =
            GitSetupPanel(
                initialProvider = client.gitProvider,
                initialRepoUrl = client.monoRepoUrl,
                initialBranch = client.defaultBranch,
                initialAuthType = client.gitAuthType ?: com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY,
                initialGitConfig = client.gitConfig,
                initialSshPrivateKey = existingCredentials?.sshPrivateKey,
                initialSshPublicKey = existingCredentials?.sshPublicKey,
                initialSshPassphrase = existingCredentials?.sshPassphrase,
                initialHttpsToken = existingCredentials?.httpsToken,
                initialHttpsUsername = existingCredentials?.httpsUsername,
                initialHttpsPassword = existingCredentials?.httpsPassword,
                initialGpgPrivateKey = existingCredentials?.gpgPrivateKey,
                initialGpgPublicKey = existingCredentials?.gpgPublicKey,
                initialGpgPassphrase = existingCredentials?.gpgPassphrase,
            )

        private val emailConfigPanel by lazy { EmailConfigPanel(emailAccountService, client) }
        private val jiraPanel by lazy { JiraSetupPanel(client.id, jiraSetupService) }
        private val confluencePanel by lazy { ConfluenceSettingsPanel(client.id, integrationSettingsService) }
        private val okButton = JButton("OK")
        private val cancelButton = JButton("Cancel")

        var result: ClientDto? = null
        var gitSetupRequest: com.jervis.dto.GitSetupRequestDto? = null

        init {
            preferredSize = Dimension(800, 700)
            minimumSize = Dimension(750, 650)
            isResizable = true
            defaultCloseOperation = DISPOSE_ON_CLOSE

            setupLayout()
            setupEventHandlers()
        }

        private fun setupLayout() {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(16, 16, 16, 16)

            val tabbedPane = javax.swing.JTabbedPane()

            // Git Configuration Tab
            tabbedPane.addTab("Git Configuration", JScrollPane(gitSetupPanel))

            // Email Accounts Tab
            tabbedPane.addTab("Email Accounts", JScrollPane(emailConfigPanel))

            // Jira Setup Tab
            tabbedPane.addTab("Jira", JScrollPane(jiraPanel))

            // Confluence Settings Tab
            tabbedPane.addTab("Confluence", JScrollPane(confluencePanel))

            // Button panel
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)

            panel.add(tabbedPane, BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            add(panel)
        }

        private fun setupEventHandlers() {
            okButton.addActionListener { saveAndClose() }
            cancelButton.addActionListener { dispose() }

            // ESC key handling
            addKeyListener(
                object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                            dispose()
                        }
                    }
                },
            )

            isFocusable = true
        }

        private fun saveAndClose() {
            try {
                if (!gitSetupPanel.validateFields()) {
                    return
                }

                val gitRequest = gitSetupPanel.toGitSetupRequest()

                val updatedClient =
                    client.copy(
                        gitProvider = gitRequest.gitProvider,
                        gitAuthType = gitRequest.gitAuthType,
                        monoRepoUrl = gitRequest.monoRepoUrl,
                        defaultBranch = gitRequest.defaultBranch,
                        gitConfig = gitRequest.gitConfig,
                    )

                gitSetupRequest = gitRequest
                result = updatedClient
                dispose()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Error validating settings: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
