package com.jervis.ui.component

import com.jervis.dto.ClientDto
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import com.jervis.service.IEmailAccountService
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
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class EmailConfigPanel(
    private val emailService: IEmailAccountService,
    private val client: ClientDto,
) : JPanel(BorderLayout()) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val providerCombo = JComboBox(arrayOf("GMAIL", "SEZNAM", "MICROSOFT", "IMAP"))
    private val displayNameField = JTextField(20)
    private val descriptionArea = JTextArea(2, 20)
    private val emailField = JTextField(20)
    private val usernameField = JTextField(20)
    private val passwordField = JPasswordField(20)
    private val serverHostField = JTextField(20)
    private val serverPortField = JTextField(5)
    private val useSslCheckbox = JCheckBox("Use SSL/TLS", true)

    private val imapFieldsPanel = JPanel(GridBagLayout())

    private val accountsTableModel =
        object : DefaultTableModel(arrayOf("ID", "Provider", "Email", "Display Name", "Description", "Status"), 0) {
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
        setupWebSocketListener()
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
        panel.border = BorderFactory.createTitledBorder("Email Account Configuration")

        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(5, 5, 5, 5)
            }

        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        panel.add(JLabel("Provider:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(providerCombo, gbc)
        providerCombo.addActionListener { updateFieldsVisibility() }

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        panel.add(JLabel("Display Name:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(displayNameField, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        panel.add(JLabel("Description:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        val descriptionScroll = JScrollPane(descriptionArea)
        descriptionScroll.preferredSize = Dimension(300, 50)
        panel.add(descriptionScroll, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        panel.add(JLabel("Email:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(emailField, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(imapFieldsPanel, gbc)

        setupImapFields()

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.CENTER
        val buttonsPanel = createButtonsPanel()
        panel.add(buttonsPanel, gbc)

        updateFieldsVisibility()

        return panel
    }

    private fun setupImapFields() {
        imapFieldsPanel.removeAll()

        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(5, 5, 5, 5)
            }

        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        imapFieldsPanel.add(JLabel("Username:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        imapFieldsPanel.add(usernameField, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        imapFieldsPanel.add(JLabel("Password:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        imapFieldsPanel.add(passwordField, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        imapFieldsPanel.add(JLabel("IMAP Server:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        imapFieldsPanel.add(serverHostField, gbc)

        row++
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        imapFieldsPanel.add(JLabel("Port:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        val portPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        portPanel.add(serverPortField)
        portPanel.add(useSslCheckbox)
        imapFieldsPanel.add(portPanel, gbc)
    }

    private fun updateFieldsVisibility() {
        val provider = providerCombo.selectedItem?.toString() ?: "IMAP"

        when (provider) {
            "SEZNAM" -> {
                serverHostField.text = "imap.seznam.cz"
                serverPortField.text = "993"
            }

            "GMAIL" -> {
                serverHostField.text = "imap.gmail.com"
                serverPortField.text = "993"
            }

            "MICROSOFT" -> {
                serverHostField.text = "outlook.office365.com"
                serverPortField.text = "993"
            }

            "IMAP" -> {
                if (serverHostField.text.isEmpty()) serverHostField.text = ""
                if (serverPortField.text.isEmpty()) serverPortField.text = "993"
            }
        }

        useSslCheckbox.isSelected = true

        revalidate()
        repaint()
    }

    private fun createButtonsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 5))

        val saveButton = JButton("Save Account")
        saveButton.addActionListener { saveAccount() }

        val validateButton = JButton("Test Connection")
        validateButton.addActionListener { validateAccount() }

        val cancelButton = JButton("Clear")
        cancelButton.addActionListener { clearForm() }

        panel.add(saveButton)
        panel.add(validateButton)
        panel.add(cancelButton)

        return panel
    }

    private fun createTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Configured Email Accounts")

        val scrollPane = JScrollPane(accountsTable)
        scrollPane.preferredSize = Dimension(800, 200)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val editButton = JButton("Edit")
        editButton.addActionListener { editSelectedAccount() }

        val deleteButton = JButton("Delete")
        deleteButton.addActionListener { deleteSelectedAccount() }

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadAccounts() }

        buttonsPanel.add(editButton)
        buttonsPanel.add(deleteButton)
        buttonsPanel.add(refreshButton)

        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(buttonsPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun saveAccount() {
        scope.launch {
            try {
                val provider = providerCombo.selectedItem?.toString() ?: "IMAP"
                val displayName = displayNameField.text.trim()
                val description = descriptionArea.text.trim().ifEmpty { null }
                val email = emailField.text.trim()

                if (displayName.isEmpty() || email.isEmpty()) {
                    showError("Display name and email are required")
                    return@launch
                }

                val request =
                    CreateOrUpdateEmailAccountRequestDto(
                        clientId = client.id,
                        projectId = null,
                        provider =
                            com.jervis.domain.email.EmailProviderEnum
                                .valueOf(provider),
                        displayName = displayName,
                        description = description,
                        email = email,
                        username = usernameField.text.trim().ifEmpty { null },
                        password = String(passwordField.password).ifEmpty { null },
                        serverHost = serverHostField.text.trim().ifEmpty { null },
                        serverPort = serverPortField.text.trim().toIntOrNull(),
                        useSsl = useSslCheckbox.isSelected,
                    )

                val savedAccount =
                    if (editingAccountId != null) {
                        emailService.updateEmailAccount(editingAccountId!!, request)
                    } else {
                        emailService.createEmailAccount(request)
                    }

                withContext(Dispatchers.Swing) {
                    showInfo("Account saved successfully: ${savedAccount.email}")
                    clearForm()
                    loadAccounts()
                }
            } catch (e: Exception) {
                showError("Failed to save account: ${e.message}")
            }
        }
    }

    private fun validateAccount() {
        scope.launch {
            try {
                val provider = providerCombo.selectedItem?.toString() ?: "IMAP"
                val displayName = displayNameField.text.trim()
                val email = emailField.text.trim()

                if (displayName.isEmpty() || email.isEmpty()) {
                    showError("Display name and email are required")
                    return@launch
                }

                if (usernameField.text.isBlank() || passwordField.password.isEmpty() || serverHostField.text.isBlank()) {
                    showError("Username, password, and IMAP server are required")
                    return@launch
                }

                val tempRequest =
                    CreateOrUpdateEmailAccountRequestDto(
                        clientId = client.id,
                        projectId = null,
                        provider =
                            com.jervis.domain.email.EmailProviderEnum
                                .valueOf(provider),
                        displayName = displayName,
                        description = null,
                        email = email,
                        username = usernameField.text.trim(),
                        password = String(passwordField.password),
                        serverHost = serverHostField.text.trim(),
                        serverPort = serverPortField.text.trim().toIntOrNull(),
                        useSsl = useSslCheckbox.isSelected,
                    )

                val tempAccount = emailService.createEmailAccount(tempRequest)

                try {
                    val validation: ValidateResponseDto =
                        emailService.validateEmailAccount(requireNotNull(tempAccount.id))
                    if (validation.ok) {
                        showInfo("Connection successful!\n${validation.message ?: ""}")
                    } else {
                        showError("Connection failed:\n${validation.message ?: "Unknown error"}")
                    }
                } finally {
                    emailService.deleteEmailAccount(requireNotNull(tempAccount.id))
                }
            } catch (e: Exception) {
                showError("Validation failed: ${e.message}")
            }
        }
    }

    private fun loadAccounts() {
        scope.launch {
            try {
                val accounts: List<EmailAccountDto> =
                    emailService.listEmailAccounts(clientId = client.id, projectId = null)

                withContext(Dispatchers.Swing) {
                    accountsTableModel.rowCount = 0

                    accounts.forEach { account ->
                        val status = if (account.hasPassword) "Configured" else "Not Configured"

                        accountsTableModel.addRow(
                            arrayOf(
                                account.id,
                                account.provider.name,
                                account.email,
                                account.displayName,
                                account.description ?: "",
                                status,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                showError("Failed to load accounts: ${e.message}")
            }
        }
    }

    private fun editSelectedAccount() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow == -1) {
            showError("Please select an account to edit")
            return
        }

        val accountId = accountsTableModel.getValueAt(selectedRow, 0).toString()

        scope.launch {
            try {
                val account = emailService.getEmailAccount(accountId)

                withContext(Dispatchers.Swing) {
                    if (account == null) {
                        showError("Account not found: $accountId")
                        return@withContext
                    }
                    editingAccountId = accountId
                    providerCombo.selectedItem = account.provider.name
                    displayNameField.text = account.displayName
                    descriptionArea.text = account.description ?: ""
                    emailField.text = account.email
                    usernameField.text = account.username ?: ""
                    serverHostField.text = account.serverHost ?: ""
                    serverPortField.text = account.serverPort?.toString() ?: ""
                    useSslCheckbox.isSelected = account.useSsl

                    updateFieldsVisibility()
                }
            } catch (e: Exception) {
                showError("Failed to load account: ${e.message}")
            }
        }
    }

    private fun deleteSelectedAccount() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow == -1) {
            showError("Please select an account to delete")
            return
        }

        val accountId = accountsTableModel.getValueAt(selectedRow, 0).toString()
        val email = accountsTableModel.getValueAt(selectedRow, 2).toString()

        val confirm =
            JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete email account: $email?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
            )

        if (confirm == JOptionPane.YES_OPTION) {
            scope.launch {
                try {
                    emailService.deleteEmailAccount(accountId)

                    withContext(Dispatchers.Swing) {
                        showInfo("Account deleted successfully")
                        loadAccounts()
                    }
                } catch (e: Exception) {
                    showError("Failed to delete account: ${e.message}")
                }
            }
        }
    }

    private fun clearForm() {
        editingAccountId = null
        displayNameField.text = ""
        descriptionArea.text = ""
        emailField.text = ""
        usernameField.text = ""
        passwordField.text = ""
        serverHostField.text = ""
        serverPortField.text = "993"
        useSslCheckbox.isSelected = true
        providerCombo.selectedIndex = 0
        updateFieldsVisibility()
    }

    private fun setupWebSocketListener() {
    }

    private fun showInfo(message: String) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(this, message, "Information", JOptionPane.INFORMATION_MESSAGE)
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
