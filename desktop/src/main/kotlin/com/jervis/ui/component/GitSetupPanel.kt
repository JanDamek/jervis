package com.jervis.ui.component

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.dto.GitConfigDto
import com.jervis.dto.GitSetupRequestDto
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.TitledBorder

/**
 * Panel for Git configuration setup.
 * Provides UI for selecting Git provider, entering credentials, and configuring workflow rules.
 */
class GitSetupPanel(
    initialProvider: GitProviderEnum? = null,
    initialRepoUrl: String? = null,
    initialBranch: String = "main",
    initialAuthType: GitAuthTypeEnum = GitAuthTypeEnum.SSH_KEY,
) : JPanel(BorderLayout()) {
    private val tabbedPane = JTabbedPane()

    private val providerCombo =
        JComboBox(GitProviderEnum.entries.toTypedArray()).apply {
            selectedItem = initialProvider ?: GitProviderEnum.GITHUB
            preferredSize = Dimension(300, 30)
        }

    private val repoUrlField =
        JTextField(initialRepoUrl ?: "").apply {
            preferredSize = Dimension(500, 30)
            toolTipText = "Git repository URL (e.g., git@github.com:user/repo.git)"
        }

    private val defaultBranchField =
        JTextField(initialBranch).apply {
            preferredSize = Dimension(200, 30)
            toolTipText = "Default branch name (main or master)"
        }

    private val authTypeCombo =
        JComboBox(GitAuthTypeEnum.entries.toTypedArray()).apply {
            selectedItem = initialAuthType
            preferredSize = Dimension(300, 30)
        }

    private val sshPrivateKeyArea =
        JTextArea(10, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Paste your SSH private key here"
        }

    private val sshPublicKeyArea =
        JTextArea(10, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Paste your SSH public key here"
        }

    private val sshPassphraseField =
        JPasswordField(30).apply {
            toolTipText = "SSH key passphrase (if applicable)"
        }

    private val httpsTokenArea =
        JTextArea(5, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Personal Access Token for HTTPS authentication"
        }

    private val httpsUsernameField =
        JTextField(30).apply {
            toolTipText = "Username for HTTPS Basic Auth"
        }

    private val httpsPasswordField =
        JPasswordField(30).apply {
            toolTipText = "Password for HTTPS Basic Auth"
        }

    private val gpgPrivateKeyArea =
        JTextArea(10, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "GPG private key for commit signing"
        }

    private val gpgKeyIdField =
        JTextField(30).apply {
            toolTipText = "GPG Key ID (e.g., ABCD1234)"
        }

    private val gpgPassphraseField =
        JPasswordField(30).apply {
            toolTipText = "GPG key passphrase"
        }

    private val requireGpgSignCheckbox =
        JCheckBox("Require GPG signature on commits", false).apply {
            toolTipText = "Force all commits to be GPG signed"
        }

    private val requireLinearHistoryCheckbox =
        JCheckBox("Require linear history", false).apply {
            toolTipText = "Enforce linear Git history (no merge commits)"
        }

    private val conventionalCommitsCheckbox =
        JCheckBox("Use Conventional Commits", true).apply {
            toolTipText = "Enforce conventional commit message format"
        }

    private val commitMessageTemplateArea =
        JTextArea(5, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Template for commit messages"
        }

    init {
        setupUI()
        setupAuthTypeListener()
    }

    private fun setupUI() {
        border = TitledBorder("Git Configuration")

        tabbedPane.addTab("Repository", createRepositoryPanel())
        tabbedPane.addTab("Authentication", createAuthPanel())
        tabbedPane.addTab("Workflow Rules", createWorkflowPanel())

        add(tabbedPane, BorderLayout.CENTER)
    }

    private fun createRepositoryPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        panel.add(JLabel("Git Provider:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(providerCombo, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Repository URL:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(repoUrlField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Default Branch:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(defaultBranchField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.CENTER
        panel.add(Box.createVerticalGlue(), gbc)

        return panel
    }

    private fun createAuthPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))

        val authTypePanel = JPanel(FlowLayout(FlowLayout.LEFT))
        authTypePanel.add(JLabel("Authentication Type:"))
        authTypePanel.add(authTypeCombo)
        panel.add(authTypePanel, BorderLayout.NORTH)

        val cardPanel = JPanel(CardLayout())
        cardPanel.add(createSshAuthPanel(), GitAuthTypeEnum.SSH_KEY.name)
        cardPanel.add(createHttpsPatPanel(), GitAuthTypeEnum.HTTPS_PAT.name)
        cardPanel.add(createHttpsBasicPanel(), GitAuthTypeEnum.HTTPS_BASIC.name)
        cardPanel.add(createGpgPanel(), "GPG")
        cardPanel.add(JPanel(), GitAuthTypeEnum.NONE.name)

        panel.add(cardPanel, BorderLayout.CENTER)

        authTypeCombo.addActionListener {
            val layout = cardPanel.layout as CardLayout
            layout.show(cardPanel, (authTypeCombo.selectedItem as GitAuthTypeEnum).name)
        }

        return panel
    }

    private fun createSshAuthPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("SSH Private Key:*"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 0.4
        panel.add(JScrollPane(sshPrivateKeyArea), gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        panel.add(JLabel("SSH Public Key:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 0.4
        panel.add(JScrollPane(sshPublicKeyArea), gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        panel.add(JLabel("Passphrase:"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(sshPassphraseField, gbc)
        row++

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val loadKeyButton =
            JButton("Load from File...").apply {
                addActionListener { loadSshKeyFromFile() }
            }
        buttonPanel.add(loadKeyButton)
        gbc.gridx = 1
        gbc.gridy = row
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(buttonPanel, gbc)

        return panel
    }

    private fun createHttpsPatPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("Personal Access Token:*"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        panel.add(JScrollPane(httpsTokenArea), gbc)

        return panel
    }

    private fun createHttpsBasicPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        panel.add(JLabel("Username:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(httpsUsernameField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Password:*"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(httpsPasswordField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.CENTER
        panel.add(Box.createVerticalGlue(), gbc)

        return panel
    }

    private fun createGpgPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("GPG Private Key:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 0.6
        panel.add(JScrollPane(gpgPrivateKeyArea), gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        panel.add(JLabel("GPG Key ID:"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(gpgKeyIdField, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Passphrase:"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(gpgPassphraseField, gbc)

        return panel
    }

    private fun createWorkflowPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(10, 10, 10, 10) }
        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(requireGpgSignCheckbox, gbc)
        row++

        gbc.gridy = row
        panel.add(requireLinearHistoryCheckbox, gbc)
        row++

        gbc.gridy = row
        panel.add(conventionalCommitsCheckbox, gbc)
        row++

        gbc.gridy = row
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("Commit Message Template:"), gbc)
        row++

        gbc.gridy = row
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        panel.add(JScrollPane(commitMessageTemplateArea), gbc)

        return panel
    }

    private fun setupAuthTypeListener() {
        authTypeCombo.addActionListener {
            updateAuthFieldsVisibility()
        }
        updateAuthFieldsVisibility()
    }

    private fun updateAuthFieldsVisibility() {
        val authType = authTypeCombo.selectedItem as GitAuthTypeEnum
        tabbedPane.setEnabledAt(1, authType != GitAuthTypeEnum.NONE)
    }

    private fun loadSshKeyFromFile() {
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Select SSH Private Key"
        fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val file = fileChooser.selectedFile
            try {
                val keyContent = file.readText()
                sshPrivateKeyArea.text = keyContent

                val pubKeyFile = File(file.parent, "${file.name}.pub")
                if (pubKeyFile.exists()) {
                    sshPublicKeyArea.text = pubKeyFile.readText()
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

    fun validateFields(): Boolean {
        if (repoUrlField.text.isBlank()) {
            JOptionPane.showMessageDialog(
                this,
                "Repository URL is required",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        if (defaultBranchField.text.isBlank()) {
            JOptionPane.showMessageDialog(
                this,
                "Default branch is required",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        val authType = authTypeCombo.selectedItem as GitAuthTypeEnum
        when (authType) {
            GitAuthTypeEnum.SSH_KEY -> {
                if (sshPrivateKeyArea.text.isBlank()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "SSH private key is required",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                    return false
                }
            }

            GitAuthTypeEnum.HTTPS_PAT -> {
                if (httpsTokenArea.text.isBlank()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Personal Access Token is required",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                    return false
                }
            }

            GitAuthTypeEnum.HTTPS_BASIC -> {
                if (httpsUsernameField.text.isBlank() || httpsPasswordField.password.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Username and password are required",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                    return false
                }
            }

            GitAuthTypeEnum.NONE -> {}
        }

        return true
    }

    fun toGitSetupRequest(): GitSetupRequestDto =
        GitSetupRequestDto(
            gitProvider = providerCombo.selectedItem as GitProviderEnum,
            monoRepoUrl = repoUrlField.text.trim(),
            defaultBranch = defaultBranchField.text.trim(),
            gitAuthType = authTypeCombo.selectedItem as GitAuthTypeEnum,
            sshPrivateKey = sshPrivateKeyArea.text.takeIf { it.isNotBlank() },
            sshPublicKey = sshPublicKeyArea.text.takeIf { it.isNotBlank() },
            sshPassphrase = String(sshPassphraseField.password).takeIf { it.isNotBlank() },
            httpsToken = httpsTokenArea.text.takeIf { it.isNotBlank() },
            httpsUsername = httpsUsernameField.text.takeIf { it.isNotBlank() },
            httpsPassword = String(httpsPasswordField.password).takeIf { it.isNotBlank() },
            gpgPrivateKey = gpgPrivateKeyArea.text.takeIf { it.isNotBlank() },
            gpgKeyId = gpgKeyIdField.text.takeIf { it.isNotBlank() },
            gpgPassphrase = String(gpgPassphraseField.password).takeIf { it.isNotBlank() },
            gitConfig =
                GitConfigDto(
                    commitMessageTemplate = commitMessageTemplateArea.text.takeIf { it.isNotBlank() },
                    requireGpgSign = requireGpgSignCheckbox.isSelected,
                    gpgKeyId = gpgKeyIdField.text.takeIf { it.isNotBlank() },
                    requireLinearHistory = requireLinearHistoryCheckbox.isSelected,
                    conventionalCommits = conventionalCommitsCheckbox.isSelected,
                    commitRules = emptyMap(),
                ),
        )
}
