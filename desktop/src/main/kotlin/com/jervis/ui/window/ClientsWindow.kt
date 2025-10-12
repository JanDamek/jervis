package com.jervis.ui.window

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ClientProjectLinkDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IIndexingService
import com.jervis.service.IProjectService
import com.jervis.ui.component.ClientSettingsComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
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
    private val projectService: IProjectService,
    private val linkService: IClientProjectLinkService,
    private val indexingService: IIndexingService,
) : JFrame("Client Management") {
    private val clientsList = JList<ClientDocument>()
    private val listModel = javax.swing.DefaultListModel<ClientDocument>()

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
    private val reindexBtn = JButton("Reindex Client Projects")

    private val projectsList = JList<ProjectDocument>()
    private val projectsModel = javax.swing.DefaultListModel<ProjectDocument>()
    private val removeProjectBtn = JButton("Remove from Client")
    private val toggleDisableForClientBtn = JButton("Toggle Disable for Client")
    private val toggleAnonymizeForClientBtn = JButton("Toggle Anonymization for Client")
    private val toggleHistoricalForClientBtn = JButton("Toggle Historical for Client")

    private var selectedClientId: ObjectId? = null
    private var clientDependencies: MutableSet<ObjectId> = mutableSetOf()
    private var currentClient: ClientDocument? = null
    private var linksByProject: Map<ObjectId, ClientProjectLinkDocument> = emptyMap()

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
                    val c = value as? ClientDocument
                    label.text = c?.let { it.name } ?: ""
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

        addRow("Název:*", nameField)
        addRow("Popis:", descriptionField)
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
                add(reindexBtn)
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
        reindexBtn.addActionListener { reindexClientProjects() }
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

    private fun fillClientForm(c: ClientDocument) {
        nameField.text = c.name
        descriptionField.text = c.description ?: ""
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
            JOptionPane.showMessageDialog(this, "Název je povinný.", "Validace", JOptionPane.WARNING_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val id = selectedClientId
                if (id == null) {
                    val c =
                        ClientDocument(
                            name = name,
                            description = desc,
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
                            description = desc,
                            isDisabled = disabled,
                            dependsOnProjects = clientDependencies.toList(),
                        )
                    withContext(Dispatchers.IO) { clientService.update(id, updated) }
                    currentClient = updated
                }
                loadClients()
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

    private fun loadProjectsForClient(clientId: ObjectId) {
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
                        val p = value as? ProjectDocument
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
                    "Nejsou dostupné žádné projekty.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return@launch
            }

            val dialog = JDialog(this@ClientsWindow, "Přiřadit existující projekty", true)
            val listModel = javax.swing.DefaultListModel<ProjectDocument>()
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
                        val p = value as? ProjectDocument
                        if (p != null) {
                            val assigned = p.clientId?.toHexString()
                            val suffix =
                                if (assigned != null && assigned != clientId.toHexString()) " — přiřazeno jinému klientovi" else ""
                            label.text = "${p.name}$suffix"
                        }
                        return label
                    }
                }
            val ok = JButton("Přiřadit")
            val cancel = JButton("Zrušit")
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
                            "Některé vybrané projekty jsou již přiřazené jinému klientovi. Opravdu je chcete přeřadit?",
                            "Potvrzení",
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
            val dialog = JDialog(this@ClientsWindow, "Závislosti klienta (vyberte projekty)", true)
            val listModel = javax.swing.DefaultListModel<ProjectDocument>()
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
                        val p = value as? ProjectDocument
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
                val updatedProject = project.copy(clientId = GLOBAL_ID)
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
        add("Název:*", nameField, 0, 0)
        add("Slug:*", slugField, 0, 1)
        add("Cesta (legacy):", pathField, 0, 2)
        add("Repo primaryUrl:*", urlField, 0, 3)
        add("Popis:", descField, 0, 4)

        val ok = JButton("Vytvořit")
        val cancel = JButton("Zrušit")
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
            val path = pathField.text.trim()
            val url = urlField.text.trim()
            val desc = descField.text.trim().ifEmpty { null }
            val regex = Regex("^[a-z0-9-]+$")
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Název je povinný.",
                    "Validace",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }
            if (!regex.matches(slug)) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Slug musí odpovídat ^[a-z0-9-]+$",
                    "Validace",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }
            if (url.isBlank()) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Repo primaryUrl je povinné.",
                    "Validace",
                    JOptionPane.WARNING_MESSAGE,
                )
                return@addActionListener
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val project =
                        ProjectDocument(
                            clientId = clientId,
                            name = name,
                            description = desc,
                            projectPath = path,
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

        val result = dialog.result
        if (result != null) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withContext(Dispatchers.IO) { clientService.create(result) }
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

        val dialog = ClientSettingsDialog(this, client)
        dialog.isVisible = true

        val result = dialog.result
        if (result != null) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withContext(Dispatchers.IO) { clientService.update(client.id, result) }
                    currentClient = result
                    fillClientForm(result)
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

    private fun reindexClientProjects() {
        val clientId =
            selectedClientId ?: run {
                JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return
            }

        val currentClient =
            this.currentClient ?: run {
                JOptionPane.showMessageDialog(this, "Klient nebyl nalezen.", "Chyba", JOptionPane.ERROR_MESSAGE)
                return
            }

        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Opravdu chcete reindexovat všechny projekty klienta '${currentClient.name}'?\nTato operace může trvat několik minut.",
                "Potvrzení reindexace",
                JOptionPane.YES_NO_OPTION,
            )

        if (confirm != JOptionPane.YES_OPTION) return

        // Disable the button to prevent multiple clicks
        reindexBtn.isEnabled = false
        reindexBtn.text = "Probíhá reindexace..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val links = linkService.listForClient(clientId)
                    val allProjects = projectService.getAllProjects()
                    val clientProjects =
                        allProjects.filter { project ->
                            links.any { link -> link.projectId == project.id && !link.isDisabled }
                        }
                    indexingService.indexProjectsForClient(clientProjects, currentClient.name)
                }

                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Reindexace projektů klienta '${currentClient.name}' byla úspěšně dokončena.",
                    "Reindexace dokončena",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this@ClientsWindow,
                    "Chyba při reindexaci: ${e.message}",
                    "Chyba reindexace",
                    JOptionPane.ERROR_MESSAGE,
                )
            } finally {
                // Re-enable the button
                reindexBtn.isEnabled = true
                reindexBtn.text = "Reindexace projektů klienta"
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
                toolTipText = "Název klienta (povinné)"
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

        private val guidelinesPanel = ClientSettingsComponents.createGuidelinesPanel()
        private val reviewPolicyPanel = ClientSettingsComponents.createReviewPolicyPanel()
        private val formattingPanel = ClientSettingsComponents.createFormattingPanel()
        private val secretsPolicyPanel = ClientSettingsComponents.createSecretsPolicyPanel()
        private val anonymizationPanel = ClientSettingsComponents.createAnonymizationPanel()
        private val inspirationPolicyPanel = ClientSettingsComponents.createInspirationPolicyPanel()
        private val clientToolsPanel = ClientSettingsComponents.createClientToolsPanel()

        private val okButton = JButton("Vytvořit klienta")
        private val cancelButton = JButton("Zrušit")

        var result: ClientDocument? = null

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

            // Guidelines Tab
            tabbedPane.addTab("Coding Guidelines", JScrollPane(guidelinesPanel))

            // Review Policy Tab
            tabbedPane.addTab("Review Policy", JScrollPane(reviewPolicyPanel))

            // Formatting Tab
            tabbedPane.addTab("Formatting", JScrollPane(formattingPanel))

            // Secrets Policy Tab
            tabbedPane.addTab("Secrets Policy", JScrollPane(secretsPolicyPanel))

            // Anonymization Tab
            tabbedPane.addTab("Anonymization", JScrollPane(anonymizationPanel))

            // Inspiration Policy Tab
            tabbedPane.addTab("Inspiration Policy", JScrollPane(inspirationPolicyPanel))

            // Client Tools Tab
            tabbedPane.addTab("Client Tools", JScrollPane(clientToolsPanel))

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
            panel.add(JLabel("Název:*"), gbc)
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

            // Validation
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(this, "Název je povinný.", "Validace", JOptionPane.WARNING_MESSAGE)
                return
            }

            try {
                val newClient =
                    ClientDocument(
                        name = name,
                        description = description,
                        defaultCodingGuidelines = guidelinesPanel.getGuidelines(),
                        defaultReviewPolicy = reviewPolicyPanel.getReviewPolicy(),
                        defaultFormatting = formattingPanel.getFormatting(),
                        defaultSecretsPolicy = secretsPolicyPanel.getSecretsPolicy(),
                        defaultAnonymization = anonymizationPanel.getAnonymization(),
                        defaultInspirationPolicy = inspirationPolicyPanel.getInspirationPolicy(),
                        tools = clientToolsPanel.getClientTools(),
                        isDisabled = isDisabled,
                    )

                result = newClient
                dispose()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Chyba při validaci nastavení: ${e.message}",
                    "Chyba",
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
        private val client: ClientDocument,
    ) : JDialog(owner, "Nastavení klienta: ${client.name}", true) {
        private val guidelinesPanel = ClientSettingsComponents.createGuidelinesPanel(client.defaultCodingGuidelines)
        private val reviewPolicyPanel = ClientSettingsComponents.createReviewPolicyPanel(client.defaultReviewPolicy)
        private val formattingPanel = ClientSettingsComponents.createFormattingPanel(client.defaultFormatting)
        private val secretsPolicyPanel = ClientSettingsComponents.createSecretsPolicyPanel(client.defaultSecretsPolicy)
        private val anonymizationPanel = ClientSettingsComponents.createAnonymizationPanel(client.defaultAnonymization)
        private val inspirationPolicyPanel =
            ClientSettingsComponents.createInspirationPolicyPanel(client.defaultInspirationPolicy)
        private val clientToolsPanel = ClientSettingsComponents.createClientToolsPanel(client.tools)

        private val okButton = JButton("OK")
        private val cancelButton = JButton("Cancel")

        var result: ClientDocument? = null

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

            // Guidelines Tab
            tabbedPane.addTab("Coding Guidelines", JScrollPane(guidelinesPanel))

            // Review Policy Tab
            tabbedPane.addTab("Review Policy", JScrollPane(reviewPolicyPanel))

            // Formatting Tab
            tabbedPane.addTab("Formatting", JScrollPane(formattingPanel))

            // Secrets Policy Tab
            tabbedPane.addTab("Secrets Policy", JScrollPane(secretsPolicyPanel))

            // Anonymization Tab
            tabbedPane.addTab("Anonymization", JScrollPane(anonymizationPanel))

            // Inspiration Policy Tab
            tabbedPane.addTab("Inspiration Policy", JScrollPane(inspirationPolicyPanel))

            // Client Tools Tab
            tabbedPane.addTab("Client Tools", JScrollPane(clientToolsPanel))

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
                val updatedClient =
                    client.copy(
                        defaultCodingGuidelines = guidelinesPanel.getGuidelines(),
                        defaultReviewPolicy = reviewPolicyPanel.getReviewPolicy(),
                        defaultFormatting = formattingPanel.getFormatting(),
                        defaultSecretsPolicy = secretsPolicyPanel.getSecretsPolicy(),
                        defaultAnonymization = anonymizationPanel.getAnonymization(),
                        defaultInspirationPolicy = inspirationPolicyPanel.getInspirationPolicy(),
                        tools = clientToolsPanel.getClientTools(),
                        updatedAt = java.time.Instant.now(),
                    )

                result = updatedClient
                dispose()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Chyba při validaci nastavení: ${e.message}",
                    "Chyba",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
