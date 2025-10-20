package com.jervis.ui.component

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.dto.GitConfigDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * Panel for overriding Git configuration at project level.
 * Allows projects to use a different Git remote URL, authentication type, credentials, and GPG settings.
 */
class ProjectGitOverridePanel(
    initialGitRemoteUrl: String? = null,
    initialGitAuthType: GitAuthTypeEnum? = null,
    initialGitConfig: GitConfigDto? = null,
) : JPanel(BorderLayout()) {
    // Override checkboxes
    private val overrideRemoteUrlCheckbox = JCheckBox("Override Git Remote URL")
    private val overrideAuthCheckbox = JCheckBox("Override Authentication")
    private val overrideGpgCheckbox = JCheckBox("Override GPG Settings")

    // Git Remote URL fields
    private val gitRemoteUrlField =
        JTextField(initialGitRemoteUrl ?: "").apply {
            preferredSize = Dimension(500, 30)
            toolTipText = "Git clone URL for this project (if different from client mono-repo)"
            isEnabled = initialGitRemoteUrl != null
        }

    // Authentication type
    private val authTypeCombo =
        JComboBox(GitAuthTypeEnum.entries.toTypedArray()).apply {
            selectedItem = initialGitAuthType ?: GitAuthTypeEnum.SSH_KEY
            isEnabled = initialGitAuthType != null
        }

    // SSH credential fields
    private val sshPrivateKeyArea =
        JTextArea(5, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "SSH private key for Git authentication"
        }

    private val sshPublicKeyArea =
        JTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "SSH public key (optional)"
        }

    private val sshPassphraseField =
        JPasswordField().apply {
            preferredSize = Dimension(300, 30)
            toolTipText = "Passphrase for SSH private key (if required)"
        }

    // HTTPS PAT credential fields
    private val httpsTokenArea =
        JTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Personal Access Token for HTTPS authentication"
        }

    // HTTPS Basic credential fields
    private val httpsUsernameField =
        JTextField().apply {
            preferredSize = Dimension(300, 30)
            toolTipText = "Username for HTTPS Basic authentication"
        }

    private val httpsPasswordField =
        JPasswordField().apply {
            preferredSize = Dimension(300, 30)
            toolTipText = "Password for HTTPS Basic authentication"
        }

    // GPG configuration fields
    private val gpgPrivateKeyArea =
        JTextArea(5, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "GPG private key for signing commits (overrides client settings)"
        }

    private val gpgKeyIdField =
        JTextField(initialGitConfig?.gpgKeyId ?: "").apply {
            preferredSize = Dimension(300, 30)
            toolTipText = "GPG Key ID (e.g., ABCD1234)"
        }

    private val gpgPassphraseField =
        JPasswordField().apply {
            preferredSize = Dimension(300, 30)
            toolTipText = "Passphrase for GPG private key (if required)"
        }

    private val requireGpgSignCheckbox =
        JCheckBox("Require GPG-signed commits", initialGitConfig?.requireGpgSign ?: false).apply {
            toolTipText = "If checked, all commits must be GPG-signed"
        }

    // Panels for different credential types
    private val credentialsCardPanel = JPanel(CardLayout())
    private val sshCredentialsPanel = JPanel(GridBagLayout())
    private val httpsPatCredentialsPanel = JPanel(GridBagLayout())
    private val httpsBasicCredentialsPanel = JPanel(GridBagLayout())
    private val noCredentialsPanel = JPanel()

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Set initial checkbox states
        overrideRemoteUrlCheckbox.isSelected = initialGitRemoteUrl != null
        overrideAuthCheckbox.isSelected = initialGitAuthType != null
        overrideGpgCheckbox.isSelected = initialGitConfig?.requireGpgSign == true

        // Enable/disable GPG fields based on checkbox
        val gpgEnabled = initialGitConfig?.requireGpgSign == true
        gpgPrivateKeyArea.isEnabled = gpgEnabled
        gpgKeyIdField.isEnabled = gpgEnabled
        gpgPassphraseField.isEnabled = gpgEnabled
        requireGpgSignCheckbox.isEnabled = gpgEnabled

        setupCredentialsPanels()
        setupLayout()
        setupEventHandlers()
        updateCredentialsVisibility()
    }

    private fun setupCredentialsPanels() {
        // SSH credentials panel
        val sshGbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
        var sshRow = 0

        sshGbc.gridx = 0
        sshGbc.gridy = sshRow
        sshGbc.anchor = GridBagConstraints.NORTHEAST
        sshGbc.fill = GridBagConstraints.NONE
        sshGbc.weightx = 0.0
        sshCredentialsPanel.add(JLabel("SSH Private Key:"), sshGbc)

        sshGbc.gridx = 1
        sshGbc.anchor = GridBagConstraints.LINE_START
        sshGbc.fill = GridBagConstraints.BOTH
        sshGbc.weightx = 1.0
        sshGbc.weighty = 0.4
        sshCredentialsPanel.add(JScrollPane(sshPrivateKeyArea), sshGbc)
        sshRow++

        sshGbc.gridx = 0
        sshGbc.gridy = sshRow
        sshGbc.anchor = GridBagConstraints.NORTHEAST
        sshGbc.fill = GridBagConstraints.NONE
        sshGbc.weightx = 0.0
        sshGbc.weighty = 0.0
        sshCredentialsPanel.add(JLabel("SSH Public Key:"), sshGbc)

        sshGbc.gridx = 1
        sshGbc.anchor = GridBagConstraints.LINE_START
        sshGbc.fill = GridBagConstraints.BOTH
        sshGbc.weightx = 1.0
        sshGbc.weighty = 0.3
        sshCredentialsPanel.add(JScrollPane(sshPublicKeyArea), sshGbc)
        sshRow++

        sshGbc.gridx = 0
        sshGbc.gridy = sshRow
        sshGbc.anchor = GridBagConstraints.LINE_END
        sshGbc.fill = GridBagConstraints.NONE
        sshGbc.weightx = 0.0
        sshGbc.weighty = 0.0
        sshCredentialsPanel.add(JLabel("SSH Passphrase:"), sshGbc)

        sshGbc.gridx = 1
        sshGbc.anchor = GridBagConstraints.LINE_START
        sshGbc.fill = GridBagConstraints.HORIZONTAL
        sshGbc.weightx = 1.0
        sshCredentialsPanel.add(sshPassphraseField, sshGbc)
        sshRow++

        // Load from File button
        sshGbc.gridx = 1
        sshGbc.gridy = sshRow
        sshGbc.anchor = GridBagConstraints.LINE_START
        sshGbc.fill = GridBagConstraints.NONE
        sshGbc.weightx = 0.0
        sshCredentialsPanel.add(
            JButton("Load from File...").apply {
                addActionListener { loadSshKeyFromFile() }
            },
            sshGbc,
        )

        // HTTPS PAT credentials panel
        val patGbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
        patGbc.gridx = 0
        patGbc.gridy = 0
        patGbc.anchor = GridBagConstraints.NORTHEAST
        patGbc.fill = GridBagConstraints.NONE
        patGbc.weightx = 0.0
        httpsPatCredentialsPanel.add(JLabel("Personal Access Token:"), patGbc)

        patGbc.gridx = 1
        patGbc.anchor = GridBagConstraints.LINE_START
        patGbc.fill = GridBagConstraints.BOTH
        patGbc.weightx = 1.0
        patGbc.weighty = 1.0
        httpsPatCredentialsPanel.add(JScrollPane(httpsTokenArea), patGbc)

        // HTTPS Basic credentials panel
        val basicGbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
        var basicRow = 0

        basicGbc.gridx = 0
        basicGbc.gridy = basicRow
        basicGbc.anchor = GridBagConstraints.LINE_END
        basicGbc.fill = GridBagConstraints.NONE
        basicGbc.weightx = 0.0
        httpsBasicCredentialsPanel.add(JLabel("Username:"), basicGbc)

        basicGbc.gridx = 1
        basicGbc.anchor = GridBagConstraints.LINE_START
        basicGbc.fill = GridBagConstraints.HORIZONTAL
        basicGbc.weightx = 1.0
        httpsBasicCredentialsPanel.add(httpsUsernameField, basicGbc)
        basicRow++

        basicGbc.gridx = 0
        basicGbc.gridy = basicRow
        basicGbc.anchor = GridBagConstraints.LINE_END
        basicGbc.fill = GridBagConstraints.NONE
        basicGbc.weightx = 0.0
        httpsBasicCredentialsPanel.add(JLabel("Password:"), basicGbc)

        basicGbc.gridx = 1
        basicGbc.anchor = GridBagConstraints.LINE_START
        basicGbc.fill = GridBagConstraints.HORIZONTAL
        basicGbc.weightx = 1.0
        httpsBasicCredentialsPanel.add(httpsPasswordField, basicGbc)

        // Add all panels to card layout
        credentialsCardPanel.add(noCredentialsPanel, "NONE")
        credentialsCardPanel.add(sshCredentialsPanel, "SSH_KEY")
        credentialsCardPanel.add(httpsPatCredentialsPanel, "HTTPS_PAT")
        credentialsCardPanel.add(httpsBasicCredentialsPanel, "HTTPS_BASIC")
    }

    private fun setupLayout() {
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
        var row = 0

        // Remote URL override section
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.NONE
        mainPanel.add(overrideRemoteUrlCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        mainPanel.add(JLabel("Git Remote URL:"), gbc)

        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        mainPanel.add(gitRemoteUrlField, gbc)
        row++

        // Separator
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.insets = Insets(20, 8, 8, 8)
        mainPanel.add(JPanel().apply { preferredSize = Dimension(1, 1) }, gbc)
        gbc.insets = Insets(8, 8, 8, 8)
        row++

        // Authentication override section
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.NONE
        mainPanel.add(overrideAuthCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        mainPanel.add(JLabel("Authentication Type:"), gbc)

        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        mainPanel.add(authTypeCombo, gbc)
        row++

        // Credentials card panel (dynamic based on auth type)
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 0.5
        mainPanel.add(credentialsCardPanel, gbc)
        row++

        // Separator
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.insets = Insets(20, 8, 8, 8)
        gbc.weighty = 0.0
        mainPanel.add(JPanel().apply { preferredSize = Dimension(1, 1) }, gbc)
        gbc.insets = Insets(8, 8, 8, 8)
        row++

        // GPG override section
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.NONE
        mainPanel.add(overrideGpgCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        mainPanel.add(requireGpgSignCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.NORTHEAST
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        mainPanel.add(JLabel("GPG Private Key:"), gbc)

        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 0.3
        mainPanel.add(JScrollPane(gpgPrivateKeyArea), gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        mainPanel.add(JLabel("GPG Key ID:"), gbc)

        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        mainPanel.add(gpgKeyIdField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        mainPanel.add(JLabel("GPG Passphrase:"), gbc)

        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        mainPanel.add(gpgPassphraseField, gbc)
        row++

        // Add spacer to push content to top
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 0.2
        mainPanel.add(JPanel(), gbc)

        add(mainPanel, BorderLayout.CENTER)
    }

    private fun setupEventHandlers() {
        overrideRemoteUrlCheckbox.addActionListener {
            val enabled = overrideRemoteUrlCheckbox.isSelected
            gitRemoteUrlField.isEnabled = enabled
            if (!enabled) {
                gitRemoteUrlField.text = ""
            }
        }

        overrideAuthCheckbox.addActionListener {
            val enabled = overrideAuthCheckbox.isSelected
            authTypeCombo.isEnabled = enabled
            updateCredentialsVisibility()
            if (!enabled) {
                clearCredentialFields()
            }
        }

        authTypeCombo.addActionListener {
            updateCredentialsVisibility()
        }

        overrideGpgCheckbox.addActionListener {
            val enabled = overrideGpgCheckbox.isSelected
            requireGpgSignCheckbox.isEnabled = enabled
            gpgPrivateKeyArea.isEnabled = enabled
            gpgKeyIdField.isEnabled = enabled
            gpgPassphraseField.isEnabled = enabled

            if (!enabled) {
                requireGpgSignCheckbox.isSelected = false
                gpgPrivateKeyArea.text = ""
                gpgKeyIdField.text = ""
                gpgPassphraseField.text = ""
            }
        }
    }

    private fun updateCredentialsVisibility() {
        val cardLayout = credentialsCardPanel.layout as CardLayout
        if (!overrideAuthCheckbox.isSelected) {
            cardLayout.show(credentialsCardPanel, "NONE")
            return
        }

        when (authTypeCombo.selectedItem as? GitAuthTypeEnum) {
            GitAuthTypeEnum.SSH_KEY -> cardLayout.show(credentialsCardPanel, "SSH_KEY")
            GitAuthTypeEnum.HTTPS_PAT -> cardLayout.show(credentialsCardPanel, "HTTPS_PAT")
            GitAuthTypeEnum.HTTPS_BASIC -> cardLayout.show(credentialsCardPanel, "HTTPS_BASIC")
            GitAuthTypeEnum.NONE, null -> cardLayout.show(credentialsCardPanel, "NONE")
        }
    }

    private fun clearCredentialFields() {
        sshPrivateKeyArea.text = ""
        sshPublicKeyArea.text = ""
        sshPassphraseField.text = ""
        httpsTokenArea.text = ""
        httpsUsernameField.text = ""
        httpsPasswordField.text = ""
    }

    /**
     * Returns the Git remote URL if override is enabled, null otherwise.
     */
    fun getGitRemoteUrl(): String? =
        if (overrideRemoteUrlCheckbox.isSelected) {
            gitRemoteUrlField.text.trim().takeIf { it.isNotBlank() }
        } else {
            null
        }

    /**
     * Returns the authentication type if override is enabled, null otherwise.
     */
    fun getGitAuthType(): GitAuthTypeEnum? =
        if (overrideAuthCheckbox.isSelected) {
            authTypeCombo.selectedItem as? GitAuthTypeEnum
        } else {
            null
        }

    /**
     * Returns the Git configuration if GPG override is enabled, null otherwise.
     */
    fun getGitConfig(): GitConfigDto? =
        if (overrideGpgCheckbox.isSelected) {
            GitConfigDto(
                gitUserName = null,
                gitUserEmail = null,
                commitMessageTemplate = null,
                requireGpgSign = requireGpgSignCheckbox.isSelected,
                gpgKeyId = gpgKeyIdField.text.trim().takeIf { it.isNotBlank() },
                requireLinearHistory = false,
                conventionalCommits = false,
                commitRules = emptyMap(),
            )
        } else {
            null
        }

    /**
     * Returns ProjectGitOverrideRequestDto with all Git override settings and credentials.
     * Returns null if no overrides are enabled.
     */
    fun toProjectGitOverrideRequest(): ProjectGitOverrideRequestDto? {
        if (!overrideRemoteUrlCheckbox.isSelected &&
            !overrideAuthCheckbox.isSelected &&
            !overrideGpgCheckbox.isSelected
        ) {
            return null
        }

        return ProjectGitOverrideRequestDto(
            gitRemoteUrl =
                if (overrideRemoteUrlCheckbox.isSelected) {
                    gitRemoteUrlField.text.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                },
            gitAuthType =
                if (overrideAuthCheckbox.isSelected) {
                    authTypeCombo.selectedItem as? GitAuthTypeEnum
                } else {
                    null
                },
            sshPrivateKey =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.SSH_KEY
                ) {
                    sshPrivateKeyArea.text.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                },
            sshPublicKey =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.SSH_KEY
                ) {
                    sshPublicKeyArea.text.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                },
            sshPassphrase =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.SSH_KEY
                ) {
                    String(sshPassphraseField.password).takeIf { it.isNotBlank() }
                } else {
                    null
                },
            httpsToken =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.HTTPS_PAT
                ) {
                    httpsTokenArea.text.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                },
            httpsUsername =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.HTTPS_BASIC
                ) {
                    httpsUsernameField.text.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                },
            httpsPassword =
                if (overrideAuthCheckbox.isSelected &&
                    authTypeCombo.selectedItem == GitAuthTypeEnum.HTTPS_BASIC
                ) {
                    String(httpsPasswordField.password).takeIf { it.isNotBlank() }
                } else {
                    null
                },
            gitConfig = getGitConfig(),
        )
    }

    /**
     * Validates that if overrides are enabled, required fields are filled.
     * @return true if validation passes, false otherwise
     */
    fun validateFields(): Boolean {
        if (overrideRemoteUrlCheckbox.isSelected) {
            if (gitRemoteUrlField.text.trim().isBlank()) {
                return false
            }
        }

        if (overrideAuthCheckbox.isSelected) {
            when (authTypeCombo.selectedItem as? GitAuthTypeEnum) {
                GitAuthTypeEnum.SSH_KEY -> {
                    if (sshPrivateKeyArea.text.trim().isBlank()) {
                        return false
                    }
                }

                GitAuthTypeEnum.HTTPS_PAT -> {
                    if (httpsTokenArea.text.trim().isBlank()) {
                        return false
                    }
                }

                GitAuthTypeEnum.HTTPS_BASIC -> {
                    if (httpsUsernameField.text.trim().isBlank() ||
                        String(httpsPasswordField.password).isBlank()
                    ) {
                        return false
                    }
                }

                else -> {}
            }
        }

        if (overrideGpgCheckbox.isSelected && requireGpgSignCheckbox.isSelected) {
            if (gpgPrivateKeyArea.text.trim().isBlank() || gpgKeyIdField.text.trim().isBlank()) {
                return false
            }
        }

        return true
    }

    /**
     * Load SSH private/public keys from filesystem.
     * Opens file chooser in ~/.ssh directory.
     */
    private fun loadSshKeyFromFile() {
        val fc = JFileChooser(java.io.File(System.getProperty("user.home"), ".ssh"))
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                sshPrivateKeyArea.text = fc.selectedFile.readText()
                // Try to find matching .pub file
                java.io.File(fc.selectedFile.parent, "${fc.selectedFile.name}.pub").takeIf { it.exists() }?.let {
                    sshPublicKeyArea.text = it.readText()
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to load SSH key: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
