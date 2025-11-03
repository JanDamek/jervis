package com.jervis.ui.component

import com.jervis.dto.ClientDto
import com.jervis.dto.ConfluenceAccountCreateDto
import com.jervis.dto.ConfluenceAccountUpdateDto
import com.jervis.service.IConfluenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

class ConfluenceConfigPanel(
    private val confluenceService: IConfluenceService,
    private val client: ClientDto,
) : JPanel(BorderLayout()) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val cloudIdField = JTextField(20)
    private val siteNameField = JTextField(20)
    private val siteUrlField = JTextField(30)
    private val accessTokenField = JTextField(30)
    private val refreshTokenField = JTextField(30)
    private val spaceKeysArea = JTextArea(3, 30)
    private val isActiveCheckbox = JCheckBox("Active", true)

    private val accountsTableModel =
        object : DefaultTableModel(
            arrayOf("ID", "Site Name", "URL", "Spaces", "Active", "Last Synced", "Pages"),
            0,
        ) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ) = false
        }
    private val accountsTable = JTable(accountsTableModel)

    private var editingAccountId: String? = null

    init {
        setupUI()
        loadAccounts()
    }

    private fun setupUI() {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        val formPanel = createFormPanel()
        val tablePanel = createTablePanel()

        add(formPanel, BorderLayout.NORTH)
        add(tablePanel, BorderLayout.CENTER)
    }

    private fun createFormPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Confluence Account Configuration")

        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.HORIZONTAL
        var row = 0

        addField(panel, gbc, row++, "Cloud ID:", cloudIdField)
        addField(panel, gbc, row++, "Site Name:", siteNameField)
        addField(panel, gbc, row++, "Site URL:", siteUrlField)
        addField(panel, gbc, row++, "Access Token:", accessTokenField)
        addField(panel, gbc, row++, "Refresh Token:", refreshTokenField)

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("Space Keys (one per line):"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.weighty = 0.3
        gbc.fill = GridBagConstraints.BOTH
        spaceKeysArea.lineWrap = true
        spaceKeysArea.wrapStyleWord = true
        panel.add(JScrollPane(spaceKeysArea), gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        panel.add(isActiveCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(createButtonPanel(), gbc)

        return panel
    }

    private fun addField(
        panel: JPanel,
        gbc: GridBagConstraints,
        row: Int,
        label: String,
        field: JTextField,
    ) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JLabel(label), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.EAST
        panel.add(field, gbc)
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))

        val saveButton =
            JButton("Save").apply {
                addActionListener { saveAccount() }
            }

        val clearButton =
            JButton("Clear").apply {
                addActionListener { clearForm() }
            }

        val oauthButton =
            JButton("OAuth Setup").apply {
                addActionListener { showOAuthInstructions() }
            }

        panel.add(saveButton)
        panel.add(clearButton)
        panel.add(oauthButton)

        return panel
    }

    private fun createTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Confluence Accounts")

        accountsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selectedRow = accountsTable.selectedRow
                if (selectedRow >= 0) {
                    loadAccountForEdit(selectedRow)
                }
            }
        }

        val scrollPane = JScrollPane(accountsTable)
        scrollPane.preferredSize = Dimension(800, 300)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        val deleteButton =
            JButton("Delete").apply {
                addActionListener { deleteSelectedAccount() }
            }

        val syncButton =
            JButton("Manual Sync").apply {
                addActionListener { triggerSyncForSelected() }
            }

        val refreshButton =
            JButton("Refresh").apply {
                addActionListener { loadAccounts() }
            }

        buttonPanel.add(deleteButton)
        buttonPanel.add(syncButton)
        buttonPanel.add(refreshButton)

        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun saveAccount() {
        scope.launch {
            try {
                val cloudId = cloudIdField.text.trim()
                val siteName = siteNameField.text.trim()
                val siteUrl = siteUrlField.text.trim()
                val accessToken = accessTokenField.text.trim()
                val spaceKeys =
                    spaceKeysArea.text
                        .split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                if (cloudId.isEmpty() || siteName.isEmpty() || siteUrl.isEmpty() || accessToken.isEmpty()) {
                    withContext(Dispatchers.Swing) {
                        JOptionPane.showMessageDialog(
                            this@ConfluenceConfigPanel,
                            "Please fill in required fields: Cloud ID, Site Name, URL, Access Token",
                            "Validation Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                    return@launch
                }

                val editingId = editingAccountId

                if (editingId != null) {
                    val updateRequest =
                        ConfluenceAccountUpdateDto(
                            spaceKeys = spaceKeys,
                            isActive = isActiveCheckbox.isSelected,
                        )
                    confluenceService.updateAccount(editingId, updateRequest)

                    withContext(Dispatchers.Swing) {
                        JOptionPane.showMessageDialog(
                            this@ConfluenceConfigPanel,
                            "Confluence account updated successfully!",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                    }
                } else {
                    val createRequest =
                        ConfluenceAccountCreateDto(
                            clientId = client.id,
                            projectId = null,
                            cloudId = cloudId,
                            siteName = siteName,
                            siteUrl = siteUrl,
                            accessToken = accessToken,
                            refreshToken =
                                refreshTokenField.text
                                    .trim()
                                    .takeIf { it.isNotEmpty() },
                            tokenExpiresAt = Instant.now().plusSeconds(3600),
                            spaceKeys = spaceKeys,
                        )

                    confluenceService.createAccount(createRequest)

                    withContext(Dispatchers.Swing) {
                        JOptionPane.showMessageDialog(
                            this@ConfluenceConfigPanel,
                            "Confluence account created successfully!",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                    }
                }

                clearForm()
                loadAccounts()
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(
                        this@ConfluenceConfigPanel,
                        "Error: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun clearForm() {
        editingAccountId = null
        cloudIdField.text = ""
        siteNameField.text = ""
        siteUrlField.text = ""
        accessTokenField.text = ""
        refreshTokenField.text = ""
        spaceKeysArea.text = ""
        isActiveCheckbox.isSelected = true
        cloudIdField.isEnabled = true
        siteNameField.isEnabled = true
        siteUrlField.isEnabled = true
        accessTokenField.isEnabled = true
        refreshTokenField.isEnabled = true
    }

    private fun loadAccounts() {
        scope.launch {
            try {
                val accounts = confluenceService.listAccounts(clientId = client.id)

                withContext(Dispatchers.Swing) {
                    accountsTableModel.rowCount = 0
                    accounts.forEach { account ->
                        accountsTableModel.addRow(
                            arrayOf(
                                account.id,
                                account.siteName,
                                account.siteUrl,
                                account.spaceKeys.joinToString(", "),
                                if (account.isActive) "Yes" else "No",
                                account.lastSuccessfulSyncAt?.toString() ?: "Never",
                                "${account.stats?.indexedPages ?: 0} / ${account.stats?.totalPages ?: 0}",
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(
                        this@ConfluenceConfigPanel,
                        "Error loading accounts: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun loadAccountForEdit(row: Int) {
        val accountId = accountsTableModel.getValueAt(row, 0) as String

        scope.launch {
            try {
                val account = confluenceService.getAccount(accountId) ?: return@launch

                withContext(Dispatchers.Swing) {
                    editingAccountId = account.id
                    cloudIdField.text = account.cloudId
                    siteNameField.text = account.siteName
                    siteUrlField.text = account.siteUrl
                    spaceKeysArea.text = account.spaceKeys.joinToString("\n")
                    isActiveCheckbox.isSelected = account.isActive

                    cloudIdField.isEnabled = false
                    siteNameField.isEnabled = false
                    siteUrlField.isEnabled = false
                    accessTokenField.isEnabled = false
                    refreshTokenField.isEnabled = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(
                        this@ConfluenceConfigPanel,
                        "Error loading account: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun deleteSelectedAccount() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select an account to delete",
                "No Selection",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete this Confluence account?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
            )

        if (confirm == JOptionPane.YES_OPTION) {
            val accountId = accountsTableModel.getValueAt(selectedRow, 0) as String

            scope.launch {
                try {
                    confluenceService.deleteAccount(accountId)
                    loadAccounts()

                    withContext(Dispatchers.Swing) {
                        JOptionPane.showMessageDialog(
                            this@ConfluenceConfigPanel,
                            "Account deleted successfully",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE,
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Swing) {
                        JOptionPane.showMessageDialog(
                            this@ConfluenceConfigPanel,
                            "Error deleting account: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
        }
    }

    private fun triggerSyncForSelected() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select an account to sync",
                "No Selection",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val accountId = accountsTableModel.getValueAt(selectedRow, 0) as String

        scope.launch {
            try {
                confluenceService.triggerSync(accountId)

                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(
                        this@ConfluenceConfigPanel,
                        "Manual sync triggered. Pages will be indexed in background.",
                        "Sync Started",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                }

                kotlinx.coroutines.delay(2000)
                loadAccounts()
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    JOptionPane.showMessageDialog(
                        this@ConfluenceConfigPanel,
                        "Error triggering sync: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun showOAuthInstructions() {
        val instructions =
            """
            Confluence OAuth Setup Instructions:

            1. Go to: https://developer.atlassian.com/console/myapps/
            2. Create a new OAuth 2.0 app
            3. Enable these scopes:
               - read:confluence-content.all
               - read:confluence-space.summary
               - read:confluence-props
            4. Note your Cloud ID, Site Name, and Site URL
            5. Generate an API token or use OAuth flow
            6. Paste credentials here

            For API tokens: https://id.atlassian.com/manage-profile/security/api-tokens
            """.trimIndent()

        JOptionPane.showMessageDialog(
            this,
            instructions,
            "Confluence OAuth Setup",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }
}
