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
    initialSshPrivateKey: String? = null,
    initialSshPublicKey: String? = null,
    initialSshPassphrase: String? = null,
    initialHttpsToken: String? = null,
    initialHttpsUsername: String? = null,
    initialHttpsPassword: String? = null,
    initialGpgPrivateKey: String? = null,
    initialGpgPublicKey: String? = null,
    initialGpgPassphrase: String? = null,
) : JPanel(BorderLayout()) {
    private val tabbedPane = JTabbedPane()

    // Panels and labels managed for dynamic visibility
    private lateinit var repositoryPanel: JPanel
    private lateinit var authPanel: JPanel
    private lateinit var globalPanel: JPanel
    private lateinit var workflowPanel: JPanel

    private lateinit var urlLabel: JLabel
    private lateinit var branchLabel: JLabel

    private val providerCombo =
        JComboBox(GitProviderEnum.entries.toTypedArray()).apply {
            selectedItem = initialProvider ?: GitProviderEnum.NONE
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
                                    GitProviderEnum.NONE -> "None"
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
            initialSshPrivateKey ?: "",
            10,
            50,
        ).apply {
            lineWrap = true
        }
    private val sshPublicKeyArea = JTextArea(initialSshPublicKey ?: "", 5, 50).apply { lineWrap = true }
    private val sshPassphraseField = JTextField(initialSshPassphrase ?: "", 30)
    private val httpsTokenArea = JTextArea(
        initialHttpsToken ?: "",
        5, 50
    ).apply {
        lineWrap = true
    }
    private val httpsUsernameField = JTextField(initialHttpsUsername ?: "", 30)
    private val httpsPasswordField = JTextField(initialHttpsPassword ?: "", 30)
    private val gpgPrivateKeyArea = JTextArea(
        initialGpgPrivateKey ?: "",
        10, 50
    ).apply {
        lineWrap = true
    }
    private val gpgPublicKeyArea = JTextArea(initialGpgPublicKey ?: "", 5, 50).apply { lineWrap = true }
    private val gpgKeyIdField = JTextField(initialGitConfig?.gpgKeyId ?: "", 30)
    private val gpgPassphraseField = JTextField(initialGpgPassphrase ?: "", 30)
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

        repositoryPanel = createRepositoryPanel()
        authPanel = createAuthPanel()
        globalPanel = createGlobalSettingsPanel()
        workflowPanel = createWorkflowPanel()

        tabbedPane.addTab("Repository", repositoryPanel)
        tabbedPane.addTab("Authentication", authPanel)
        tabbedPane.addTab("Global Settings", globalPanel)
        tabbedPane.addTab("Workflow", workflowPanel)
        add(tabbedPane, BorderLayout.CENTER)

        // React to provider changes to hide/show Git details when provider is NONE
        providerCombo.addActionListener { updateProviderUI() }
        // Initialize visibility based on the initial provider
        updateProviderUI()
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
            urlLabel = JLabel("Mono-Repo URL:")
            add(urlLabel, gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            repoUrlField.toolTipText = "Optional: Only for clients using a single mono-repository for multiple projects"
            add(repoUrlField, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            branchLabel = JLabel("Branch:*")
            add(branchLabel, gbc)
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

    private fun updateProviderUI() {
        val provider = providerCombo.selectedItem as GitProviderEnum
        val disabled = provider == GitProviderEnum.NONE

        // Hide URL and Branch fields when provider is NONE
        urlLabel.isVisible = !disabled
        branchLabel.isVisible = !disabled
        repoUrlField.isVisible = !disabled
        defaultBranchField.isVisible = !disabled

        // Show/hide other tabs by adding/removing them
        if (disabled) {
            val idxAuth = tabbedPane.indexOfComponent(authPanel)
            if (idxAuth >= 0) tabbedPane.remove(authPanel)
            val idxGlobal = tabbedPane.indexOfComponent(globalPanel)
            if (idxGlobal >= 0) tabbedPane.remove(globalPanel)
            val idxWorkflow = tabbedPane.indexOfComponent(workflowPanel)
            if (idxWorkflow >= 0) tabbedPane.remove(workflowPanel)
        } else {
            if (tabbedPane.indexOfComponent(authPanel) < 0) {
                tabbedPane.insertTab("Authentication", null, authPanel, null, 1)
            }
            if (tabbedPane.indexOfComponent(globalPanel) < 0) {
                val insertAt = if (tabbedPane.indexOfComponent(authPanel) >= 0) 2 else 1
                tabbedPane.insertTab("Global Settings", null, globalPanel, null, insertAt)
            }
            if (tabbedPane.indexOfComponent(workflowPanel) < 0) {
                val idxGlobalNow = tabbedPane.indexOfComponent(globalPanel)
                val idxAuthNow = tabbedPane.indexOfComponent(authPanel)
                val insertAt =
                    when {
                        idxGlobalNow >= 0 -> idxGlobalNow + 1
                        idxAuthNow >= 0 -> idxAuthNow + 1
                        else -> 1
                    }
                tabbedPane.insertTab("Workflow", null, workflowPanel, null, insertAt)
            }
        }

        tabbedPane.revalidate()
        tabbedPane.repaint()
    }

    fun validateFields(): Boolean {
        val provider = providerCombo.selectedItem as GitProviderEnum
        if (provider == GitProviderEnum.NONE) {
            return true
        }

        // Mono-repo URL is optional now - if not provided, projects must have their own Git URLs

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
        when (authTypeCombo.selectedItem as GitAuthTypeEnum) {
            GitAuthTypeEnum.SSH_KEY -> {
                if (sshPrivateKeyArea.text.isBlank()) {
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
            GitAuthTypeEnum.HTTPS_PAT -> {
                if (httpsTokenArea.text.isBlank()) {
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
            GitAuthTypeEnum.HTTPS_BASIC -> {
                val passwordText = httpsPasswordField.text
                if (httpsUsernameField.text.isBlank() || passwordText.isEmpty()) {
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
        val provider = providerCombo.selectedItem as GitProviderEnum
        if (provider == GitProviderEnum.NONE) {
            return GitSetupRequestDto(
                gitProvider = GitProviderEnum.NONE,
                monoRepoUrl = "",
                defaultBranch = defaultBranchField.text.trim().ifEmpty { "main" },
                gitAuthType = GitAuthTypeEnum.NONE,
                sshPrivateKey = null,
                sshPublicKey = null,
                sshPassphrase = null,
                httpsToken = null,
                httpsUsername = null,
                httpsPassword = null,
                gpgPrivateKey = null,
                gpgPublicKey = null,
                gpgKeyId = null,
                gpgPassphrase = null,
                gitConfig = null,
            )
        }

        val sshPrivateKeyText = sshPrivateKeyArea.text
        val sshPassphraseText = sshPassphraseField.text
        val httpsTokenText = httpsTokenArea.text
        val httpsPasswordText = httpsPasswordField.text
        val gpgPrivateKeyText = gpgPrivateKeyArea.text
        val gpgPassphraseText = gpgPassphraseField.text

        return GitSetupRequestDto(
            gitProvider = provider,
            monoRepoUrl = repoUrlField.text.trim(),
            defaultBranch = defaultBranchField.text.trim(),
            gitAuthType = authTypeCombo.selectedItem as GitAuthTypeEnum,
            sshPrivateKey = sshPrivateKeyText.takeIf { it.isNotBlank() },
            sshPublicKey = sshPublicKeyArea.text.takeIf { it.isNotBlank() },
            sshPassphrase = sshPassphraseText.takeIf { it.isNotBlank() },
            httpsToken = httpsTokenText.takeIf { it.isNotBlank() },
            httpsUsername = httpsUsernameField.text.takeIf { it.isNotBlank() },
            httpsPassword = httpsPasswordText.takeIf { it.isNotBlank() },
            gpgPrivateKey = gpgPrivateKeyText.takeIf { it.isNotBlank() },
            gpgPublicKey = gpgPublicKeyArea.text.takeIf { it.isNotBlank() },
            gpgKeyId = gpgKeyIdField.text.takeIf { it.isNotBlank() },
            gpgPassphrase = gpgPassphraseText.takeIf { it.isNotBlank() },
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
