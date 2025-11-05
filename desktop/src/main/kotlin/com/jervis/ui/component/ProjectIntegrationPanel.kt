package com.jervis.ui.component

import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import com.jervis.service.IIntegrationSettingsService
import com.jervis.service.IJiraSetupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Project-level Integration panel focused on Jira override selection.
 * Shows effective Jira project and board, and allows setting/clearing overrides.
 */
class ProjectIntegrationPanel(
    private val projectId: String,
    private val integrationService: IIntegrationSettingsService,
    private val jiraService: IJiraSetupService,
    private val confluenceService: com.jervis.service.IConfluenceService? = null,
) : JPanel(GridBagLayout()) {
    private val statusEffectiveJira = JLabel("–")
    private val statusEffectiveJiraBoard = JLabel("–")
    private val statusEffectiveConfluenceSpace = JLabel("–")
    private val statusEffectiveConfluenceRoot = JLabel("–")

    private val overrideJiraField = JTextField(16)
    private val chooseJiraButton = JButton("Choose…")

    private val overrideJiraBoardField = JTextField(12)
    private val chooseBoardButton = JButton("Choose Board…")

    private val overrideConfluenceSpaceField = JTextField(16)
    private val chooseSpaceButton = JButton("Choose Space…")
    private val overrideConfluenceRootPageField = JTextField(16)
    private val chooseRootPageButton = JButton("Choose Root Page…")

    private val saveOverridesButton = JButton("Save Overrides")
    private val clearJiraOverrideButton = JButton("Clear Jira Override")
    private val clearBoardOverrideButton = JButton("Clear Board Override")
    private val clearConfluenceOverridesButton = JButton("Clear Confluence Overrides")

    private var currentClientId: String? = null
    private var currentEffectiveProjectKey: String? = null
    private var currentConfluenceAccountId: String? = null

    init {
        layoutUI()
        bind()
        refresh()
    }

    private fun layoutUI() {
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.NONE
            }
        var row = 0

        fun addRow(
            label: String,
            comp: java.awt.Component,
        ) {
            gbc.gridx = 0
            gbc.gridy = row
            add(JLabel(label), gbc)
            gbc.gridx = 1
            gbc.gridy = row
            add(comp, gbc)
            row++
        }

        // Effective
        addRow("Effective Jira Project:", statusEffectiveJira)
        addRow("Effective Jira Board:", statusEffectiveJiraBoard)
        addRow("Effective Confluence Space:", statusEffectiveConfluenceSpace)
        addRow("Effective Confluence Root Page:", statusEffectiveConfluenceRoot)

        // Override Jira project (field + choose button)
        val jiraOverridePanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(overrideJiraField)
                add(chooseJiraButton)
            }
        addRow("Override Jira Project Key:", jiraOverridePanel)

        // Override Jira board (field + choose button)
        val boardOverridePanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(overrideJiraBoardField)
                add(chooseBoardButton)
            }
        addRow("Override Jira Board ID:", boardOverridePanel)

        // Confluence overrides
        val confSpacePanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(overrideConfluenceSpaceField)
                add(chooseSpaceButton)
            }
        addRow("Override Confluence Space Key:", confSpacePanel)

        val confRootPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(overrideConfluenceRootPageField)
                add(chooseRootPageButton)
            }
        addRow("Override Confluence Root Page ID:", confRootPanel)

        // Actions
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        val actions =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(saveOverridesButton)
                add(clearJiraOverrideButton)
                add(clearBoardOverrideButton)
                add(clearConfluenceOverridesButton)
            }
        add(actions, gbc)
    }

    private fun bind() {
        chooseJiraButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                val clientId = currentClientId
                if (clientId.isNullOrBlank()) {
                    showError("Assign a client to this project first.")
                    return@launch
                }
                runCatching { jiraService.listProjects(clientId) }
                    .onSuccess { items ->
                        withContext(Dispatchers.Main) {
                            if (items.isEmpty()) {
                                showError("No projects available. Make sure the client Jira connection is valid and has access.")
                                return@withContext
                            }
                            val names = items.map { "${it.name} (${it.key})" }.toTypedArray()
                            val selection =
                                JOptionPane.showInputDialog(
                                    this@ProjectIntegrationPanel,
                                    "Select Jira project:",
                                    "Choose Project",
                                    JOptionPane.QUESTION_MESSAGE,
                                    null,
                                    names,
                                    names.first(),
                                ) as? String
                            if (selection != null) {
                                val idx = names.indexOf(selection)
                                if (idx >= 0) overrideJiraField.text = items[idx].key
                            }
                        }
                    }.onFailure { e -> showError("Failed to load projects: ${e.message}") }
            }
        }

        chooseBoardButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                val clientId = currentClientId
                if (clientId.isNullOrBlank()) {
                    showError("Assign a client to this project first.")
                    return@launch
                }
                val filterProject = currentEffectiveProjectKey ?: overrideJiraField.text.trim().ifBlank { null }
                runCatching { jiraService.listBoards(clientId, filterProject) }
                    .onSuccess { items ->
                        withContext(Dispatchers.Main) {
                            if (items.isEmpty()) {
                                showError("No boards available${if (filterProject != null) " for project $filterProject" else ""}.")
                                return@withContext
                            }
                            val names = items.map { "${it.name} (#${it.id})" }.toTypedArray()
                            val selection =
                                JOptionPane.showInputDialog(
                                    this@ProjectIntegrationPanel,
                                    "Select Jira board:",
                                    "Choose Board",
                                    JOptionPane.QUESTION_MESSAGE,
                                    null,
                                    names,
                                    names.first(),
                                ) as? String
                            if (selection != null) {
                                val idx = names.indexOf(selection)
                                if (idx >= 0) overrideJiraBoardField.text = items[idx].id.toString()
                            }
                        }
                    }.onFailure { e -> showError("Failed to load boards: ${e.message}") }
            }
        }

        saveOverridesButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                val jiraKey = overrideJiraField.text.trim().ifBlank { null }
                val boardIdStr = overrideJiraBoardField.text.trim().ifBlank { null }
                val confSpace = overrideConfluenceSpaceField.text.trim().ifBlank { null }
                val confRoot = overrideConfluenceRootPageField.text.trim().ifBlank { null }
                runCatching {
                    integrationService.setProjectOverrides(
                        ProjectIntegrationOverridesDto(
                            projectId = projectId,
                            jiraProjectKey = jiraKey,
                            jiraBoardId = boardIdStr,
                            confluenceSpaceKey = confSpace,
                            confluenceRootPageId = confRoot,
                        ),
                    )
                }.onFailure { e -> showError("Failed to save overrides: ${e.message}") }
                refresh()
            }
        }

        clearJiraOverrideButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    // Empty string means explicit clear to null on server side
                    integrationService.setProjectOverrides(
                        ProjectIntegrationOverridesDto(
                            projectId = projectId,
                            jiraProjectKey = "",
                        ),
                    )
                }.onFailure { e -> showError("Failed to clear Jira override: ${e.message}") }
                refresh()
            }
        }

        clearBoardOverrideButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    integrationService.setProjectOverrides(
                        ProjectIntegrationOverridesDto(
                            projectId = projectId,
                            jiraBoardId = "",
                        ),
                    )
                }.onFailure { e -> showError("Failed to clear Board override: ${e.message}") }
                refresh()
            }
        }

        clearConfluenceOverridesButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    // Empty strings signal explicit clear
                    integrationService.setProjectOverrides(
                        ProjectIntegrationOverridesDto(
                            projectId = projectId,
                            confluenceSpaceKey = "",
                            confluenceRootPageId = "",
                        ),
                    )
                }.onFailure { e -> showError("Failed to clear Confluence overrides: ${e.message}") }
                refresh()
            }
        }

        chooseSpaceButton.addActionListener {
            val svc = confluenceService
            if (svc == null) {
                showError("Confluence service not available in this build.")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { svc.listAccounts(projectId = projectId) }
                    .onSuccess { accounts ->
                        withContext(Dispatchers.Main) {
                            if (accounts.isEmpty()) {
                                showError("No Confluence accounts found for this project.")
                                return@withContext
                            }
                            val options = mutableListOf<Pair<String, String>>()
                            accounts.forEach { acc ->
                                if (acc.spaceKeys.isEmpty()) {
                                    options.add("${acc.siteName} – (no spaces configured)" to "")
                                } else {
                                    acc.spaceKeys.forEach { sk -> options.add("${acc.siteName} – $sk" to acc.id) }
                                }
                            }
                            val labels = options.map { it.first }.toTypedArray()
                            val selection =
                                JOptionPane.showInputDialog(
                                    this@ProjectIntegrationPanel,
                                    "Select Confluence space:",
                                    "Choose Space",
                                    JOptionPane.QUESTION_MESSAGE,
                                    null,
                                    labels,
                                    labels.firstOrNull(),
                                ) as? String
                            if (selection != null) {
                                val idx = labels.indexOf(selection)
                                if (idx >= 0) {
                                    val label = options[idx].first
                                    val accountId = options[idx].second
                                    val space = label.substringAfter(" – ", "").trim()
                                    if (space.isNotBlank() && accountId.isNotBlank()) {
                                        currentConfluenceAccountId = accountId
                                        overrideConfluenceSpaceField.text = space
                                    } else {
                                        showError("Selected account has no spaces configured.")
                                    }
                                }
                            }
                        }
                    }.onFailure { e -> showError("Failed to list Confluence accounts: ${e.message}") }
            }
        }

        chooseRootPageButton.addActionListener {
            val svc = confluenceService
            if (svc == null) {
                showError("Confluence service not available in this build.")
                return@addActionListener
            }
            val spaceKey = overrideConfluenceSpaceField.text.trim()
            if (spaceKey.isBlank()) {
                showError("Select a Space first.")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                // Determine account
                val accountId =
                    currentConfluenceAccountId ?: run {
                        val accounts = runCatching { svc.listAccounts(projectId = projectId) }.getOrElse { emptyList() }
                        accounts.firstOrNull()?.id
                    }
                if (accountId.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { showError("No Confluence account available for this project.") }
                    return@launch
                }
                runCatching { svc.listPages(accountId, spaceKey, state = null) }
                    .onSuccess { pages ->
                        withContext(Dispatchers.Main) {
                            if (pages.isEmpty()) {
                                showError("No pages found in space $spaceKey.")
                                return@withContext
                            }
                            val labels = pages.map { "${it.title} (#${it.pageId})" }.toTypedArray()
                            val sel =
                                JOptionPane.showInputDialog(
                                    this@ProjectIntegrationPanel,
                                    "Select root page:",
                                    "Choose Root Page",
                                    JOptionPane.QUESTION_MESSAGE,
                                    null,
                                    labels,
                                    labels.firstOrNull(),
                                ) as? String
                            if (sel != null) {
                                val idx = labels.indexOf(sel)
                                if (idx >= 0) {
                                    overrideConfluenceRootPageField.text = pages[idx].pageId
                                }
                            }
                        }
                    }.onFailure { e -> showError("Failed to list pages: ${e.message}") }
            }
        }
    }

    private fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { integrationService.getProjectStatus(projectId) }
                .onSuccess { s ->
                    withContext(Dispatchers.Main) {
                        statusEffectiveJira.text = s.effectiveJiraProjectKey ?: "–"
                        statusEffectiveJiraBoard.text = s.effectiveJiraBoardId?.toString() ?: "–"
                        statusEffectiveConfluenceSpace.text = s.effectiveConfluenceSpaceKey ?: "–"
                        statusEffectiveConfluenceRoot.text = s.effectiveConfluenceRootPageId ?: "–"

                        overrideJiraField.text = s.overrideJiraProjectKey ?: ""
                        overrideJiraBoardField.text = s.overrideJiraBoardId?.toString() ?: ""
                        overrideConfluenceSpaceField.text = s.overrideConfluenceSpaceKey ?: ""
                        overrideConfluenceRootPageField.text = s.overrideConfluenceRootPageId ?: ""

                        currentClientId = s.clientId
                        currentEffectiveProjectKey = s.effectiveJiraProjectKey
                        chooseJiraButton.isEnabled = !currentClientId.isNullOrBlank()
                        chooseBoardButton.isEnabled = !currentClientId.isNullOrBlank()
                    }
                }.onFailure { e -> showError("Failed to load project integration status: ${e.message}") }
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
