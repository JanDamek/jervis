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
 * Shows effective Jira project key and allows setting/clearing an override.
 */
class ProjectIntegrationPanel(
    private val projectId: String,
    private val integrationService: IIntegrationSettingsService,
    private val jiraService: IJiraSetupService,
) : JPanel(GridBagLayout()) {
    private val statusEffectiveJira = JLabel("–")
    private val overrideJiraField = JTextField(16)
    private val chooseJiraButton = JButton("Choose…")
    private val saveOverridesButton = JButton("Save Overrides")
    private val clearJiraOverrideButton = JButton("Clear Jira Override")

    private var currentClientId: String? = null

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

        // Effective Jira project
        addRow("Effective Jira Project:", statusEffectiveJira)

        // Override Jira project (field + choose button)
        val jiraOverridePanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(overrideJiraField)
                add(chooseJiraButton)
            }
        addRow("Override Jira Project Key:", jiraOverridePanel)

        // Actions
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        val actions =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(saveOverridesButton)
                add(clearJiraOverrideButton)
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

        saveOverridesButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                val jiraKey = overrideJiraField.text.trim().ifBlank { null }
                runCatching {
                    integrationService.setProjectOverrides(
                        ProjectIntegrationOverridesDto(
                            projectId = projectId,
                            jiraProjectKey = jiraKey,
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
    }

    private fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { integrationService.getProjectStatus(projectId) }
                .onSuccess { s ->
                    withContext(Dispatchers.Main) {
                        statusEffectiveJira.text = s.effectiveJiraProjectKey ?: "–"
                        overrideJiraField.text = s.overrideJiraProjectKey ?: ""
                        currentClientId = s.clientId
                        chooseJiraButton.isEnabled = !currentClientId.isNullOrBlank()
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
