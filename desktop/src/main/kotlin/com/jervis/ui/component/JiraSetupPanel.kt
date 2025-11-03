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
    private val projectLabel = JLabel("Primary project: –")
    private val boardLabel = JLabel("Main board: –")
    private val userLabel = JLabel("Preferred user: –")

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

    private val projectKeyField = JTextField(16)
    private val chooseProjectButton = JButton("Choose…")
    private val setProjectButton = JButton("Set Primary Project")

    private val boardIdField = JTextField(10)
    private val chooseBoardButton = JButton("Choose…")
    private val setBoardButton = JButton("Set Main Board")

    private val preferredUserField = JTextField(24)
    private val setPreferredUserButton = JButton("Set Preferred User")

    init {
        layoutUI()
        setControlsEnabled(false)
        bindHandlers()
        refreshStatus()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        projectKeyField.isEnabled = enabled
        chooseProjectButton.isEnabled = enabled
        setProjectButton.isEnabled = enabled
        boardIdField.isEnabled = enabled
        chooseBoardButton.isEnabled = enabled
        setBoardButton.isEnabled = enabled
        preferredUserField.isEnabled = enabled
        setPreferredUserButton.isEnabled = enabled
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

        // Primary project
        gbc.gridx = 0
        gbc.gridy = row
        val projectPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Project Key:"))
                add(projectKeyField)
                add(chooseProjectButton)
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
                add(chooseBoardButton)
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
        fun info(message: String) =
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    this,
                    message,
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }

        chooseProjectButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val items = jiraSetupService.listProjects(clientId)
                    withContext(Dispatchers.Main) {
                        if (items.isEmpty()) {
                            showError("No projects available. Make sure the token has access.")
                            return@withContext
                        }
                        val names = items.map { it.name + " (" + it.key + ")" }.toTypedArray()
                        val selection =
                            JOptionPane.showInputDialog(
                                this@JiraSetupPanel,
                                "Select Jira project:",
                                "Choose Project",
                                JOptionPane.QUESTION_MESSAGE,
                                null,
                                names,
                                names.first(),
                            ) as? String
                        if (selection != null) {
                            val index = names.indexOf(selection)
                            if (index >= 0) {
                                val chosen = items[index]
                                projectKeyField.text = chosen.key
                            }
                        }
                    }
                }.onFailure { e -> showError("Failed to load projects: ${e.message}") }
            }
        }

        chooseBoardButton.addActionListener {
            CoroutineScope(Dispatchers.IO).launch {
                val projectKey = projectKeyField.text.trim().ifBlank { null }
                runCatching {
                    val boards = jiraSetupService.listBoards(clientId, projectKey)
                    withContext(Dispatchers.Main) {
                        if (boards.isEmpty()) {
                            showError("No boards found${projectKey?.let { " for project $it" } ?: ""}.")
                            return@withContext
                        }
                        val names = boards.map { it.name + " (" + it.id + ")" }.toTypedArray()
                        val selection =
                            JOptionPane.showInputDialog(
                                this@JiraSetupPanel,
                                "Select Jira board:",
                                "Choose Board",
                                JOptionPane.QUESTION_MESSAGE,
                                null,
                                names,
                                names.first(),
                            ) as? String
                        if (selection != null) {
                            val index = names.indexOf(selection)
                            if (index >= 0) {
                                val chosen = boards[index]
                                boardIdField.text = chosen.id.toString()
                            }
                        }
                    }
                }.onFailure { e -> showError("Failed to load boards: ${e.message}") }
            }
        }

        testSaveButton.addActionListener {
            val rawTenant = tenantField.text.trim()
            val tenant = normalizeTenant(rawTenant)
            if (tenant != rawTenant) {
                tenantField.text = tenant
            }
            val email = emailField.text.trim()
            val token = apiTokenField.text.trim()
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
                        info("API token saved and validated. You can now choose Project and Board.")
                    }
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
                    setControlsEnabled(status.connected)
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

private fun normalizeTenant(input: String): String =
    input
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")
