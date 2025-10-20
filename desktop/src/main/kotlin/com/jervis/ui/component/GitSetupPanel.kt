package com.jervis.ui.component

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.dto.GitConfigDto
import com.jervis.dto.GitSetupRequestDto
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.TitledBorder

class GitSetupPanel(
    initialProvider: GitProviderEnum? = null,
    initialRepoUrl: String? = null,
    initialBranch: String = "main",
    initialAuthType: GitAuthTypeEnum = GitAuthTypeEnum.SSH_KEY,
    initialGitConfig: GitConfigDto? = null,
    private val hasSshPrivateKey: Boolean = false,
    initialSshPublicKey: String? = null,
    private val hasSshPassphrase: Boolean = false,
    private val hasHttpsToken: Boolean = false,
    initialHttpsUsername: String? = null,
    private val hasHttpsPassword: Boolean = false,
    private val hasGpgPrivateKey: Boolean = false,
    initialGpgPublicKey: String? = null,
    private val hasGpgPassphrase: Boolean = false,
) : JPanel(BorderLayout()) {
    private val tabbedPane = JTabbedPane()
    private val providerCombo =
        JComboBox(GitProviderEnum.entries.toTypedArray()).apply {
            selectedItem = initialProvider ?: GitProviderEnum.GITHUB
            preferredSize = Dimension(300, 30)
            renderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is GitProviderEnum) {
                            text =
                                when (value) {
                                    GitProviderEnum.GITHUB -> "GitHub"
                                    GitProviderEnum.GITLAB -> "GitLab"
                                    GitProviderEnum.BITBUCKET -> "Bitbucket"
                                    GitProviderEnum.AZURE_DEVOPS -> "Azure DevOps"
                                    GitProviderEnum.GITEA -> "Gitea"
                                    GitProviderEnum.CUSTOM -> "Custom"
                                }
                        }
                        return this
                    }
                }
        }

    private val repoUrlField = JTextField(initialRepoUrl ?: "", 40)
    private val defaultBranchField = JTextField(initialBranch, 20)
    private val authTypeCombo =
        JComboBox(GitAuthTypeEnum.entries.toTypedArray()).apply {
            selectedItem = initialAuthType
            renderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is GitAuthTypeEnum) {
                            text =
                                when (value) {
                                    GitAuthTypeEnum.SSH_KEY -> "SSH Key"
                                    GitAuthTypeEnum.HTTPS_PAT -> "HTTPS PAT"
                                    GitAuthTypeEnum.HTTPS_BASIC -> "HTTPS Basic"
                                    GitAuthTypeEnum.NONE -> "None"
                                }
                        }
                        return this
                    }
                }
        }

    private val sshPrivateKeyArea =
        JTextArea(
            if (hasSshPrivateKey) "*** (exists - leave empty to keep)" else "",
            10,
            50,
        ).apply {
            lineWrap = true
            if (hasSshPrivateKey) {
                foreground = java.awt.Color.GRAY
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusGained(e: java.awt.event.FocusEvent) {
                            if (text == "*** (exists - leave empty to keep)") {
                                text = ""
                                foreground = java.awt.Color.BLACK
                    }
                }
            }
                    )
        }
    }
    private val sshPublicKeyArea = JTextArea(initialSshPublicKey ?: "", 5, 50).apply { lineWrap = true }
    private val sshPassphraseField = JPasswordField(30).apply {
        if (hasSshPassphrase) {
            text = "*** (exists - leave empty to keep)"
            foreground = java.awt.Color.GRAY
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (String(password) == "*** (exists - leave empty to keep)") {
                        text = ""
                        foreground = java.awt.Color.BLACK
                    }
                }
            })
        }
    }
    private val httpsTokenArea = JTextArea(
        if (hasHttpsToken) "*** (exists - leave empty to keep)" else "",
        5, 50
    ).apply {
        lineWrap = true
        if (hasHttpsToken) {
            foreground = java.awt.Color.GRAY
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (text == "*** (exists - leave empty to keep)") {
                        text = ""
                        foreground = java.awt.Color.BLACK
                    }
                }
            })
        }
    }
    private val httpsUsernameField = JTextField(initialHttpsUsername ?: "", 30)
    private val httpsPasswordField = JPasswordField(30).apply {
        if (hasHttpsPassword) {
            text = "*** (exists - leave empty to keep)"
            foreground = java.awt.Color.GRAY
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (String(password) == "*** (exists - leave empty to keep)") {
                        text = ""
                        foreground = java.awt.Color.BLACK
                    }
                }
            })
        }
    }
    private val gpgPrivateKeyArea = JTextArea(
        if (hasGpgPrivateKey) "*** (exists - leave empty to keep)" else "",
        10, 50
    ).apply {
        lineWrap = true
        if (hasGpgPrivateKey) {
            foreground = java.awt.Color.GRAY
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (text == "*** (exists - leave empty to keep)") {
                        text = ""
                        foreground = java.awt.Color.BLACK
                    }
                }
            })
        }
    }
    private val gpgPublicKeyArea = JTextArea(initialGpgPublicKey ?: "", 5, 50).apply { lineWrap = true }
    private val gpgKeyIdField = JTextField(initialGitConfig?.gpgKeyId ?: "", 30)
    private val gpgPassphraseField = JPasswordField(30).apply {
        if (hasGpgPassphrase) {
            text = "*** (exists - leave empty to keep)"
            foreground = java.awt.Color.GRAY
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (String(password) == "*** (exists - leave empty to keep)") {
                        text = ""
                        foreground = java.awt.Color.BLACK
                    }
                }
            })
        }
    }
    private val gitUserNameField = JTextField(initialGitConfig?.gitUserName ?: "", 30)
    private val gitUserEmailField = JTextField(initialGitConfig?.gitUserEmail ?: "", 30)
    private val requireGpgSignCheckbox = JCheckBox("Require GPG signatures", initialGitConfig?.requireGpgSign ?: false)
    private val requireLinearHistoryCheckbox =
        JCheckBox("Require linear history", initialGitConfig?.requireLinearHistory ?: false)
    private val conventionalCommitsCheckbox =
        JCheckBox("Use Conventional Commits", initialGitConfig?.conventionalCommits ?: true)
    private val commitMessageTemplateArea =
        JTextArea(initialGitConfig?.commitMessageTemplate ?: "", 5, 50).apply { lineWrap = true }

    init {
        setupUI()
    }

    private fun setupUI() {
        border = TitledBorder("Git Configuration")
        tabbedPane.addTab("Repository", createRepositoryPanel())
        tabbedPane.addTab("Authentication", createAuthPanel())
        tabbedPane.addTab("Global Settings", createGlobalSettingsPanel())
        tabbedPane.addTab("Workflow", createWorkflowPanel())
        add(tabbedPane, BorderLayout.CENTER)
    }

    private fun createRepositoryPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            add(JLabel("Provider:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(providerCombo, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("URL:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(repoUrlField, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Branch:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(defaultBranchField, gbc)
        }

    private fun createAuthPanel() =
        JPanel(BorderLayout()).apply {
            val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            topPanel.add(JLabel("Auth Type:"))
            topPanel.add(authTypeCombo)
            add(topPanel, BorderLayout.NORTH)

            val cardPanel = JPanel(CardLayout())
            cardPanel.add(createSshPanel(), "SSH_KEY")
            cardPanel.add(createHttpsPatPanel(), "HTTPS_PAT")
            cardPanel.add(createHttpsBasicPanel(), "HTTPS_BASIC")
            cardPanel.add(JLabel("No authentication"), "NONE")
            add(JScrollPane(cardPanel), BorderLayout.CENTER)

            // Ensure the correct auth card is visible on initial render
            (cardPanel.layout as CardLayout).show(cardPanel, (authTypeCombo.selectedItem as GitAuthTypeEnum).name)

            authTypeCombo.addActionListener {
                (cardPanel.layout as CardLayout).show(cardPanel, (authTypeCombo.selectedItem as GitAuthTypeEnum).name)
            }
        }

    private fun createSshPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.NORTHWEST
            add(JLabel("Private Key:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 0.5
            add(JScrollPane(sshPrivateKeyArea), gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.weighty = 0.0
            add(JLabel("Public Key:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 0.3
            add(JScrollPane(sshPublicKeyArea), gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(JLabel("Passphrase:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(sshPassphraseField, gbc)
            row++

            gbc.gridx = 1
            gbc.gridy = row
            add(
                JButton("Load from File...").apply {
                    addActionListener { loadSshKey() }
                },
                gbc,
            )
        }

    private fun createHttpsPatPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.anchor = GridBagConstraints.NORTHWEST
            add(JLabel("Token:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            add(JScrollPane(httpsTokenArea), gbc)
        }

    private fun createHttpsBasicPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.anchor = GridBagConstraints.LINE_END
            add(JLabel("Username:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(httpsUsernameField, gbc)

            gbc.gridx = 0
            gbc.gridy = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Password:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(httpsPasswordField, gbc)
        }

    private fun createGlobalSettingsPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
            var row = 0

            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            add(JLabel("Git User Name:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitUserNameField, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Git User Email:*"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitUserEmailField, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            add(JSeparator(), gbc)
            row++

            gbc.gridy = row
            add(JLabel("GPG Signing (Optional)"), gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.NORTHWEST
            add(JLabel("Private Key:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 0.5
            add(JScrollPane(gpgPrivateKeyArea), gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NORTHWEST
            gbc.weighty = 0.3
            add(JLabel("Public Key:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.BOTH
            add(JScrollPane(gpgPublicKeyArea), gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(JLabel("Key ID:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(gpgKeyIdField, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            add(JLabel("Passphrase:"), gbc)
            gbc.gridx = 1
            add(gpgPassphraseField, gbc)
        }

    private fun createWorkflowPanel() =
        JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply { insets = Insets(10, 10, 10, 10) }
            var row = 0

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(requireGpgSignCheckbox, gbc)
            row++
            gbc.gridy = row
            add(requireLinearHistoryCheckbox, gbc)
            row++
            gbc.gridy = row
            add(conventionalCommitsCheckbox, gbc)
            row++
            gbc.gridy = row
            add(JLabel("Commit Template:"), gbc)
            row++
            gbc.gridy = row
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            add(JScrollPane(commitMessageTemplateArea), gbc)
        }

    private fun loadSshKey() {
        val fc = JFileChooser(File(System.getProperty("user.home"), ".ssh"))
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                sshPrivateKeyArea.text = fc.selectedFile.readText()
                File(fc.selectedFile.parent, "${fc.selectedFile.name}.pub").takeIf { it.exists() }?.let {
                    sshPublicKeyArea.text = it.readText()
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this, "Failed to load: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    fun validateFields(): Boolean {
        if (repoUrlField.text.isBlank()) {
            JOptionPane.showMessageDialog(this, "Repository URL is required", "Error", JOptionPane.ERROR_MESSAGE)
            return false
        }
        if (gitUserNameField.text.isBlank() || gitUserEmailField.text.isBlank()) {
            JOptionPane.showMessageDialog(
                this,
                "Git user name and email are required",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            tabbedPane.selectedIndex = 2
            return false
        }
        val authType = authTypeCombo.selectedItem as GitAuthTypeEnum
        when (authType) {
            GitAuthTypeEnum.SSH_KEY -> {
                val sshKeyText = sshPrivateKeyArea.text
                if (sshKeyText.isBlank() || sshKeyText == "*** (exists - leave empty to keep)") {
                    if (!hasSshPrivateKey) {
                        JOptionPane.showMessageDialog(
                            this,
                            "SSH private key is required",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                        tabbedPane.selectedIndex = 1
                        return false
                    }
                }
            }

            GitAuthTypeEnum.HTTPS_PAT -> {
                val tokenText = httpsTokenArea.text
                if (tokenText.isBlank() || tokenText == "*** (exists - leave empty to keep)") {
                    if (!hasHttpsToken) {
                        JOptionPane.showMessageDialog(
                            this,
                            "HTTPS token is required",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                        tabbedPane.selectedIndex = 1
                        return false
                    }
                }
            }

            GitAuthTypeEnum.HTTPS_BASIC -> {
                val passwordText = String(httpsPasswordField.password)
                if (httpsUsernameField.text.isBlank() ||
                    (passwordText.isEmpty() || passwordText == "*** (exists - leave empty to keep)") && !hasHttpsPassword
                ) {
                    JOptionPane.showMessageDialog(this, "Username/password required", "Error", JOptionPane.ERROR_MESSAGE)
                    tabbedPane.selectedIndex = 1
                    return false
                }
            }

            GitAuthTypeEnum.NONE -> {}
        }
        return true
    }

    fun toGitSetupRequest(): GitSetupRequestDto {
        // Helper function to get credential value - returns null to preserve existing if placeholder is present
        fun getCredentialValue(text: String, hasExisting: Boolean): String? {
            return when {
                text.isBlank() || text == "*** (exists - leave empty to keep)" ->
                    if (hasExisting) null else null

                else -> text
            }
        }

        val sshPrivateKeyText = sshPrivateKeyArea.text
        val sshPassphraseText = String(sshPassphraseField.password)
        val httpsTokenText = httpsTokenArea.text
        val httpsPasswordText = String(httpsPasswordField.password)
        val gpgPrivateKeyText = gpgPrivateKeyArea.text
        val gpgPassphraseText = String(gpgPassphraseField.password)

        return GitSetupRequestDto(
            gitProvider = providerCombo.selectedItem as GitProviderEnum,
            monoRepoUrl = repoUrlField.text.trim(),
            defaultBranch = defaultBranchField.text.trim(),
            gitAuthType = authTypeCombo.selectedItem as GitAuthTypeEnum,
            sshPrivateKey = getCredentialValue(sshPrivateKeyText, hasSshPrivateKey),
            sshPublicKey = sshPublicKeyArea.text.takeIf { it.isNotBlank() },
            sshPassphrase = getCredentialValue(sshPassphraseText, hasSshPassphrase),
            httpsToken = getCredentialValue(httpsTokenText, hasHttpsToken),
            httpsUsername = httpsUsernameField.text.takeIf { it.isNotBlank() },
            httpsPassword = getCredentialValue(httpsPasswordText, hasHttpsPassword),
            gpgPrivateKey = getCredentialValue(gpgPrivateKeyText, hasGpgPrivateKey),
            gpgPublicKey = gpgPublicKeyArea.text.takeIf { it.isNotBlank() },
            gpgKeyId = gpgKeyIdField.text.takeIf { it.isNotBlank() },
            gpgPassphrase = getCredentialValue(gpgPassphraseText, hasGpgPassphrase),
            gitConfig =
                GitConfigDto(
                    gitUserName = gitUserNameField.text.trim(),
                    gitUserEmail = gitUserEmailField.text.trim(),
                    commitMessageTemplate = commitMessageTemplateArea.text.takeIf { it.isNotBlank() },
                    requireGpgSign = requireGpgSignCheckbox.isSelected,
                    gpgKeyId = gpgKeyIdField.text.takeIf { it.isNotBlank() },
                    requireLinearHistory = requireLinearHistoryCheckbox.isSelected,
                    conventionalCommits = conventionalCommitsCheckbox.isSelected,
                    commitRules = emptyMap(),
                ),
        )
    }
}
