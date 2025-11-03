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
import javax.swing.JPasswordField
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
    private val projectLabel = JLabel("Primary project: –")
    private val boardLabel = JLabel("Main board: –")
    private val userLabel = JLabel("Preferred user: –")

    // Connection settings
    private val connectionTypeCombo = javax.swing.JComboBox(arrayOf("Atlassian Cloud"))
    private val tenantField = JTextField(24)
    private val emailField = JTextField(24)
    private val apiTokenField = JPasswordField(24)

    private val testSaveButton = JButton("Test & Save API Token")
    private val helpButton = JButton("Where to get API token")
    private val refreshButton = JButton("Refresh Status")

    private val projectKeyField = JTextField(16)
    private val setProjectButton = JButton("Set Primary Project")

    private val boardIdField = JTextField(10)
    private val setBoardButton = JButton("Set Main Board")

    private val preferredUserField = JTextField(24)
    private val setPreferredUserButton = JButton("Set Preferred User")

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
        addRow(projectLabel)
        addRow(boardLabel)
        addRow(userLabel)

        // Connection config row
        gbc.gridx = 0
        gbc.gridy = row
        val connPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Connection:"))
                add(connectionTypeCombo)
                add(JLabel("Tenant (your-domain.atlassian.net):"))
                add(tenantField)
                add(JLabel("Email:"))
                add(emailField)
                add(JLabel("API token:"))
                add(apiTokenField)
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

        // Primary project
        gbc.gridx = 0
        gbc.gridy = row
        val projectPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Project Key:"))
                add(projectKeyField)
                add(setProjectButton)
            }
        add(projectPanel, gbc)
        row++

        // Main board
        gbc.gridx = 0
        gbc.gridy = row
        val boardPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Board Id:"))
                add(boardIdField)
                add(setBoardButton)
            }
        add(boardPanel, gbc)
        row++

        // Preferred user
        gbc.gridx = 0
        gbc.gridy = row
        val userPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Preferred accountId:"))
                add(preferredUserField)
                add(setPreferredUserButton)
            }
        add(userPanel, gbc)
    }

    private fun bindHandlers() {
        testSaveButton.addActionListener {
            val tenant = tenantField.text.trim()
            val email = emailField.text.trim()
            val token = String(apiTokenField.password)
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
                }.onFailure { e -> showError("Failed to save API token: ${e.message}") }
                refreshStatus()
            }
        }
        helpButton.addActionListener {
            openInBrowser("https://id.atlassian.com/manage-profile/security/api-tokens")
        }
        refreshButton.addActionListener { refreshStatus() }
        setProjectButton.addActionListener {
            val key = projectKeyField.text.trim()
            if (key.isBlank()) {
                showError("Project key must not be blank")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    jiraSetupService.setPrimaryProject(
                        com.jervis.dto.jira
                            .JiraProjectSelectionDto(clientId, key),
                    )
                }.onFailure { e -> showError("Failed to set primary project: ${e.message}") }
                refreshStatus()
            }
        }
        setBoardButton.addActionListener {
            val id = boardIdField.text.trim().toLongOrNull()
            if (id == null) {
                showError("Board id must be a number")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    jiraSetupService.setMainBoard(
                        com.jervis.dto.jira
                            .JiraBoardSelectionDto(clientId, id),
                    )
                }.onFailure { e -> showError("Failed to set main board: ${e.message}") }
                refreshStatus()
            }
        }
        setPreferredUserButton.addActionListener {
            val accountId = preferredUserField.text.trim()
            if (accountId.isBlank()) {
                showError("AccountId must not be blank")
                return@addActionListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    jiraSetupService.setPreferredUser(
                        com.jervis.dto.jira
                            .JiraUserSelectionDto(clientId, accountId),
                    )
                }.onFailure { e -> showError("Failed to set preferred user: ${e.message}") }
                refreshStatus()
            }
        }
    }

    fun refreshStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val status = jiraSetupService.getStatus(clientId)
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Status: ${if (status.connected) "Connected" else "Disconnected"}"
                    tenantLabel.text = "Tenant: ${status.tenant ?: "–"}"
                    projectLabel.text = "Primary project: ${status.primaryProject ?: "–"}"
                    boardLabel.text = "Main board: ${status.mainBoard?.toString() ?: "–"}"
                    userLabel.text = "Preferred user: ${status.preferredUser ?: "–"}"
                }
            }.onFailure { e ->
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
