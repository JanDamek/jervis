package com.jervis.ui.window

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.entity.mongo.Repo
import com.jervis.entity.mongo.Anonymization
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.client.ClientProjectLinkService
import com.jervis.entity.mongo.ClientProjectLinkDocument
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
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val linkService: ClientProjectLinkService,
) : JFrame("Správa klientů") {

    private val clientsList = JList<ClientDocument>()
    private val listModel = javax.swing.DefaultListModel<ClientDocument>()

    private val nameField = JTextField()
    private val slugField = JTextField()
    private val descriptionField = JTextField()
    private val anonymizeCheckbox = JCheckBox("Klient zakázán")

    private val saveBtn = JButton("Uložit klienta")
    private val newBtn = JButton("Nový klient")
    private val deleteBtn = JButton("Smazat klienta")
    private val addProjectBtn = JButton("Přidat nový projekt…")
    private val assignExistingBtn = JButton("Přiřadit existující projekty…")
    private val dependenciesBtn = JButton("Závislosti…")

    private val projectsList = JList<ProjectDocument>()
    private val projectsModel = javax.swing.DefaultListModel<ProjectDocument>()
    private val removeProjectBtn = JButton("Odebrat z klienta")
    private val toggleDisableForClientBtn = JButton("Přepnout zakázání u klienta")
    private val toggleAnonymizeForClientBtn = JButton("Přepnout anonymizaci u klienta")
    private val toggleHistoricalForClientBtn = JButton("Přepnout historical u klienta")

    private var selectedClientId: ObjectId? = null
    private var clientDependencies: MutableSet<ObjectId> = mutableSetOf()
    private var currentClient: ClientDocument? = null
    private var linksByProject: Map<ObjectId, ClientProjectLinkDocument> = emptyMap()

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(900, 520)
        layout = BorderLayout(10, 10)

        val mainPanel = JPanel(BorderLayout(10, 10)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

        // Left: clients list
        val leftPanel = JPanel(BorderLayout(6, 6))
        val leftHeader = JPanel(BorderLayout())
        leftHeader.add(JLabel("Klienti").apply { horizontalAlignment = SwingConstants.LEFT }, BorderLayout.WEST)
        val leftButtons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(newBtn)
            add(deleteBtn)
        }
        leftHeader.add(leftButtons, BorderLayout.EAST)
        leftPanel.add(leftHeader, BorderLayout.NORTH)

        clientsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        clientsList.model = listModel
        clientsList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val c = value as? ClientDocument
                label.text = c?.let { "${it.name} (${it.slug})" } ?: ""
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
        val gbc = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
        }

        fun addRow(label: String, field: JTextField) {
            gbc.gridx = 0; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.LINE_END
            formPanel.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.LINE_START; gbc.fill = GridBagConstraints.HORIZONTAL
            formPanel.add(field.apply { preferredSize = Dimension(420, 28) }, gbc)
            gbc.gridy++
        }

        addRow("Název:*", nameField)
        addRow("Slug:*", slugField)
        addRow("Popis:", descriptionField)
        // Anonymization toggle
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.LINE_START; gbc.fill = GridBagConstraints.NONE
        formPanel.add(anonymizeCheckbox, gbc)
        gbc.gridy++

        val formButtons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(saveBtn)
            add(addProjectBtn)
            add(assignExistingBtn)
            add(dependenciesBtn)
        }

        val formWrapper = JPanel(BorderLayout(6, 6)).apply {
            border = EmptyBorder(8, 8, 8, 8)
            add(formPanel, BorderLayout.CENTER)
            add(formButtons, BorderLayout.SOUTH)
        }

        // Projects section
        val projectsPanel = JPanel(BorderLayout(6, 6)).apply {
            border = EmptyBorder(8, 8, 8, 8)
        }
        val projHeader = JPanel(BorderLayout())
        projHeader.add(JLabel("Projekty vybraného klienta"), BorderLayout.WEST)
        val projBtns = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
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
        newBtn.addActionListener { clearClientForm(); clientsList.clearSelection(); selectedClientId = null; clientDependencies.clear(); anonymizeCheckbox.isSelected = false }
        deleteBtn.addActionListener { deleteSelectedClient() }
        saveBtn.addActionListener { saveClient() }
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

    private fun fillClientForm(c: ClientDocument) {
        nameField.text = c.name
        slugField.text = c.slug
        descriptionField.text = c.description ?: ""
        anonymizeCheckbox.toolTipText = "Pokud je zaškrtnuto, klient je dočasně zakázán."
        anonymizeCheckbox.isSelected = c.isDisabled
        clientDependencies = c.dependsOnProjects.toMutableSet()
    }

    private fun clearClientForm() {
        nameField.text = ""
        slugField.text = ""
        descriptionField.text = ""
        anonymizeCheckbox.isSelected = false
        clientDependencies.clear()
        currentClient = null
        projectsModel.clear()
    }

    private fun saveClient() {
        val name = nameField.text.trim()
        val slug = slugField.text.trim()
        val desc = descriptionField.text.trim().ifEmpty { null }
        val disabled = anonymizeCheckbox.isSelected
        val regex = Regex("^[a-z0-9-]+$")
        if (name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Název je povinný.", "Validace", JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!regex.matches(slug)) {
            JOptionPane.showMessageDialog(this, "Slug musí odpovídat ^[a-z0-9-]+$", "Validace", JOptionPane.WARNING_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val id = selectedClientId
                if (id == null) {
                    val c = ClientDocument(
                        name = name,
                        slug = slug,
                        description = desc,
                        isDisabled = disabled,
                        dependsOnProjects = clientDependencies.toList(),
                    )
                    withContext(Dispatchers.IO) { clientService.create(c) }
                } else {
                    val existing = withContext(Dispatchers.IO) { clientService.get(id) }
                    if (existing == null) {
                        JOptionPane.showMessageDialog(this@ClientsWindow, "Klient nenalezen.", "Chyba", JOptionPane.ERROR_MESSAGE)
                        return@launch
                    }
                    val updated = existing.copy(
                        name = name,
                        slug = slug,
                        description = desc,
                        isDisabled = disabled,
                        dependsOnProjects = clientDependencies.toList(),
                    )
                    withContext(Dispatchers.IO) { clientService.update(id, updated) }
                    currentClient = updated
                }
                loadClients()
                JOptionPane.showMessageDialog(this@ClientsWindow, "Klient uložen.", "Info", JOptionPane.INFORMATION_MESSAGE)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba uložení klienta: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun deleteSelectedClient() {
        val sel = clientsList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte prosím klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val confirm = JOptionPane.showConfirmDialog(this, "Opravdu smazat klienta '${sel.name}'?", "Potvrzení", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { clientService.delete(sel.id) }
                clearClientForm()
                projectsModel.clear()
                selectedClientId = null
                loadClients()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba mazání: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun loadProjectsForClient(clientId: ObjectId) {
        CoroutineScope(Dispatchers.Main).launch {
            val all = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            val forClient = all.filter { it.clientId == clientId }
            // load link flags for this client
            linksByProject = withContext(Dispatchers.IO) {
                linkService.listForClient(clientId).associateBy { it.projectId }
            }
            projectsModel.clear()
            forClient.forEach { p ->
                projectsModel.addElement(p)
            }
            projectsList.model = projectsModel
            projectsList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                    val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
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
                        label.text = "${p.name} (${p.slug})$suffix"
                    } else {
                        label.text = ""
                    }
                    return label
                }
            }
        }
    }

    private fun showAssignExistingProjectsDialog() {
        val clientId = selectedClientId ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val allProjects = withContext(Dispatchers.IO) { projectService.getAllProjects() }
            if (allProjects.isEmpty()) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Nejsou dostupné žádné projekty.", "Info", JOptionPane.INFORMATION_MESSAGE)
                return@launch
            }

            val dialog = JDialog(this@ClientsWindow, "Přiřadit existující projekty", true)
            val listModel = javax.swing.DefaultListModel<ProjectDocument>()
            allProjects.forEach { listModel.addElement(it) }
            val list = JList(listModel)
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(listComp: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                    val label = super.getListCellRendererComponent(listComp, value, index, isSelected, cellHasFocus) as JLabel
                    val p = value as? ProjectDocument
                    if (p != null) {
                        val assigned = p.clientId?.toHexString()
                        val suffix = if (assigned != null && assigned != clientId.toHexString()) " — přiřazeno jinému klientovi" else ""
                        label.text = "${p.name} (${p.slug})$suffix"
                    }
                    return label
                }
            }
            val ok = JButton("Přiřadit")
            val cancel = JButton("Zrušit")
            ok.addActionListener {
                val selected = list.selectedValuesList
                if (selected.isEmpty()) { dialog.dispose(); return@addActionListener }
                val conflicting = selected.any { it.clientId != null && it.clientId != clientId }
                if (conflicting) {
                    val res = JOptionPane.showConfirmDialog(dialog, "Některé vybrané projekty jsou již přiřazené jinému klientovi. Opravdu je chcete přeřadit?", "Potvrzení", JOptionPane.YES_NO_OPTION)
                    if (res != JOptionPane.YES_OPTION) return@addActionListener
                }
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        for (p in selected) {
                            val updated = p.copy(clientId = clientId)
                            withContext(Dispatchers.IO) { projectService.saveProject(updated, false) }
                        }
                        loadProjectsForClient(clientId)
                        JOptionPane.showMessageDialog(this@ClientsWindow, "Projekty přiřazeny.", "Info", JOptionPane.INFORMATION_MESSAGE)
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba přiřazení: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
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
            list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(listComp: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                    val label = super.getListCellRendererComponent(listComp, value, index, isSelected, cellHasFocus) as JLabel
                    val p = value as? ProjectDocument
                    if (p != null) label.text = "${p.name} (${p.slug})"
                    return label
                }
            }
            // Preselect existing dependencies
            val preselectIndices = allProjects.mapIndexedNotNull { idx, p -> if (clientDependencies.contains(p.id)) idx else null }.toIntArray()
            list.selectedIndices = preselectIndices

            val ok = JButton("OK")
            val cancel = JButton("Zrušit")
            ok.addActionListener {
                val selected = list.selectedValuesList
                clientDependencies = selected.map { it.id }.toMutableSet()
                dialog.dispose()
                JOptionPane.showMessageDialog(this@ClientsWindow, "Závislosti aktualizovány, pro uložení stiskněte 'Uložit klienta'.", "Info", JOptionPane.INFORMATION_MESSAGE)
            }
            cancel.addActionListener { dialog.dispose() }

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = EmptyBorder(12, 12, 12, 12)
            panel.add(JScrollPane(list), BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(ok); add(cancel) }
            panel.add(btns, BorderLayout.SOUTH)

            dialog.contentPane = panel
            dialog.setSize(520, 420)
            dialog.setLocationRelativeTo(this@ClientsWindow)
            dialog.isVisible = true
        }
    }

    private fun removeSelectedProjectFromClient() {
        val clientId = selectedClientId ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectsList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte projekt ze seznamu.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Unassign project from client
                val updatedProject = project.copy(clientId = null)
                withContext(Dispatchers.IO) {
                    projectService.saveProject(updatedProject, false)
                    // Delete link settings for this client-project pair
                    linkService.delete(clientId, project.id)
                }
                loadProjectsForClient(clientId)
                JOptionPane.showMessageDialog(this@ClientsWindow, "Projekt odebrán z klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba odebrání: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun toggleDisableForClientForSelectedProject() {
        val client = currentClient ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectsList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte projekt ze seznamu.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleDisabled(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba změny stavu: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun toggleAnonymizeForClientForSelectedProject() {
        val client = currentClient ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectsList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte projekt ze seznamu.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleAnonymization(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba změny stavu: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun toggleHistoricalForClientForSelectedProject() {
        val client = currentClient ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte nejprve klienta.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val project = projectsList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Vyberte projekt ze seznamu.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { linkService.toggleHistorical(client.id, project.id) }
                loadProjectsForClient(client.id)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this@ClientsWindow, "Chyba změny stavu: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun showAddProjectDialog() {
        val clientId = selectedClientId ?: run {
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
        fun add(label: String, comp: JTextField, x: Int, y: Int) {
            gbc.gridx = x; gbc.gridy = y; gbc.anchor = GridBagConstraints.LINE_END; gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(label), gbc)
            gbc.gridx = x + 1; gbc.anchor = GridBagConstraints.LINE_START; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(comp.apply { preferredSize = Dimension(420, 28) }, gbc)
        }
        add("Název:*", nameField, 0, 0)
        add("Slug:*", slugField, 0, 1)
        add("Cesta (legacy):", pathField, 0, 2)
        add("Repo primaryUrl:*", urlField, 0, 3)
        add("Popis:", descField, 0, 4)

        val ok = JButton("Vytvořit")
        val cancel = JButton("Zrušit")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(ok); add(cancel)
        }

        val panel = JPanel(BorderLayout(8, 8)).apply {
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
            if (name.isBlank()) { JOptionPane.showMessageDialog(dialog, "Název je povinný.", "Validace", JOptionPane.WARNING_MESSAGE); return@addActionListener }
            if (!regex.matches(slug)) { JOptionPane.showMessageDialog(dialog, "Slug musí odpovídat ^[a-z0-9-]+$", "Validace", JOptionPane.WARNING_MESSAGE); return@addActionListener }
            if (url.isBlank()) { JOptionPane.showMessageDialog(dialog, "Repo primaryUrl je povinné.", "Validace", JOptionPane.WARNING_MESSAGE); return@addActionListener }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val project = ProjectDocument(
                        clientId = clientId,
                        name = name,
                        slug = slug,
                        description = desc,
                        path = path,
                        repo = Repo(primaryUrl = url),
                        isActive = false,
                    )
                    withContext(Dispatchers.IO) { projectService.saveProject(project, false) }
                    loadProjectsForClient(clientId)
                    JOptionPane.showMessageDialog(this@ClientsWindow, "Projekt vytvořen.", "Info", JOptionPane.INFORMATION_MESSAGE)
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(dialog, "Chyba vytvoření projektu: ${e.message}", "Chyba", JOptionPane.ERROR_MESSAGE)
                } finally {
                    dialog.dispose()
                }
            }
        }
        cancel.addActionListener { dialog.dispose() }

        dialog.isVisible = true
    }
}
