package com.jervis.ui.component

import com.jervis.dto.jira.JiraApiTokenSaveRequestDto
import com.jervis.dto.jira.JiraApiTokenTestRequestDto
import com.jervis.service.IJiraSetupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Desktop UI panel for Jira Cloud setup at client level.
 * Uses IJiraSetupService HTTP interface (common) and works with WebSocket prompts too.
 */
class JiraSetupPanel(
    private val clientId: String,
    private val jiraSetupService: IJiraSetupService,
) : JPanel(GridBagLayout()) {
    private val logger = KotlinLogging.logger {}

    private val statusLabel = JLabel("Status: loading…")
    private val tenantLabel = JLabel("Tenant: –")

    // Connection settings
    private val connectionTypeCombo = javax.swing.JComboBox(arrayOf("Atlassian Cloud"))
    private val tenantField =
        JTextField(24).apply {
            toolTipText =
                "Enter Atlassian Cloud tenant host only, e.g. your-domain.atlassian.net (no https://, no path)"
        }
    private val emailField = JTextField(24)
    private val apiTokenField = JTextField(24)

    private val testSaveButton = JButton("Test & Save API Token")
    private val helpButton = JButton("Where to get API token")
    private val refreshButton = JButton("Refresh Status")

    init {
        layoutUI()
        bindHandlers()
        refreshStatus()
    }

    private fun layoutUI() {
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                anchor = GridBagConstraints.LINE_START
            }
        var row = 0

        fun addRow(label: JLabel) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            add(label, gbc)
            row++
        }

        addRow(statusLabel)
        addRow(tenantLabel)

        // Connection config row
        gbc.gridx = 0
        gbc.gridy = row
        val connPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Connection:"))
                add(connectionTypeCombo)
                add(JLabel("Tenant host (your-domain.atlassian.net, host only):"))
                add(tenantField)
                add(JLabel("Email:"))
                add(emailField)
                add(JLabel("API token:"))
                add(apiTokenField)
                add(
                    JLabel("(Enter host only, e.g., your-domain.atlassian.net — no https://, no path)").apply {
                        foreground = java.awt.Color.GRAY
                    },
                )
            }
        add(connPanel, gbc)
        row++

        // Actions row: auth + refresh
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        val actions =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(testSaveButton)
                add(helpButton)
                add(refreshButton)
            }
        add(actions, gbc)
        row++
    }

    private fun bindHandlers() {
        fun info(message: String) =
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    this,
                    message,
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }

        testSaveButton.addActionListener {
            val rawTenant = tenantField.text.trim()
            val tenant = normalizeTenant(rawTenant)
            if (tenant != rawTenant) {
                tenantField.text = tenant
            }
            val email = emailField.text.trim()
            val tokenRaw = apiTokenField.text.trim()
            val token = tokenRaw
            if (tenant.isBlank() || email.isBlank() || token.isBlank()) {
                showError("Tenant, Email and API token are required")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val test = jiraSetupService.testApiToken(JiraApiTokenTestRequestDto(tenant, email, token))
                    withContext(Dispatchers.Main) {
                        if (!test.success) {
                            showError("Token test failed: ${test.message ?: "Unauthorized"}")
                            return@withContext
                        }
                    }
                    jiraSetupService.saveApiToken(JiraApiTokenSaveRequestDto(clientId, tenant, email, token))
                    withContext(Dispatchers.Main) {
                        info("API token saved and validated.")
                    }
                }.onFailure { e -> showError("Failed to save API token: ${e.message}") }
                refreshStatus()
            }
        }
        helpButton.addActionListener {
            openInBrowser("https://id.atlassian.com/manage-profile/security/api-tokens")
        }
        refreshButton.addActionListener { refreshStatus() }
    }

    fun refreshStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = jiraSetupService.getStatus(clientId)
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Status: ${if (status.connected) "Connected" else "Disconnected"}"
                    tenantLabel.text = "Tenant: ${status.tenant ?: "–"}"

                    // Always reflect saved connection settings in the editable fields
                    tenantField.text = status.tenant ?: ""
                    emailField.text = status.email ?: ""
                }
            } catch (e: Exception) {
                showError("Failed to load status: ${e.message}")
            }
        }
    }

    private fun openInBrowser(url: String) {
        runCatching {
            java.awt.Desktop
                .getDesktop()
                .browse(URI(url))
        }.onFailure { e ->
            logger.warn(e) { "Failed to open browser for $url" }
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}

private fun normalizeTenant(input: String): String =
    input
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")
