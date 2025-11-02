package com.jervis.ui.component

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.service.IIntegrationSettingsService
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
 * Desktop UI panel for Confluence defaults at client level (space key, root page ID).
 */
class ConfluenceSettingsPanel(
    private val clientId: String,
    private val integrationService: IIntegrationSettingsService,
) : JPanel(GridBagLayout()) {
    private val statusLabel = JLabel("Status: loading…")
    private val spaceKeyField = JTextField(20)
    private val rootPageIdField = JTextField(20)
    private val saveButton = JButton("Save Client Defaults")
    private val refreshButton = JButton("Refresh Status")

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

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        add(statusLabel, gbc)
        gbc.gridwidth = 1
        row++

        addRow("Space Key:", spaceKeyField)
        addRow("Root Page ID:", rootPageIdField)

        gbc.gridx = 0
        gbc.gridy = row
        val actions =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(saveButton)
                add(refreshButton)
            }
        add(actions, gbc)
    }

    private fun bind() {
        saveButton.addActionListener {
            val space = spaceKeyField.text.trim().ifEmpty { null }
            val root = rootPageIdField.text.trim().ifEmpty { null }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    integrationService.setClientConfluenceDefaults(ClientConfluenceDefaultsDto(clientId, space, root))
                }.onFailure { e -> showError("Failed to save Confluence defaults: ${e.message}") }
                refresh()
            }
        }
        refreshButton.addActionListener { refresh() }
    }

    private fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val s = integrationService.getClientStatus(clientId)
                withContext(Dispatchers.Main) {
                    statusLabel.text =
                        "Jira: ${if (s.jiraConnected) "Connected to ${s.jiraTenant}" else "Disconnected"} | Confluence defaults loaded"
                    spaceKeyField.text = s.confluenceSpaceKey ?: ""
                    rootPageIdField.text = s.confluenceRootPageId ?: ""
                }
            }.onFailure { e -> showError("Failed to load status: ${e.message}") }
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE) }
    }
}
