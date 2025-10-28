package com.jervis.ui.window.project

import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.dto.ProjectOverridesDto
import com.jervis.ui.component.ProjectGitOverridePanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/**
 * Dialog for adding/editing project
 */
class ProjectDialog(
    owner: JFrame,
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialProjectPath: String = "",
    initialLanguages: String = "",
    initialInspirationOnly: Boolean = false,
    initialIncludeGlobs: String = "**/*.kt, **/*.java, **/*.md",
    initialExcludeGlobs: String = "**/build/**, **/.git/**, **/*.min.*",
    initialMaxFileSizeMB: Int = 5,
    initialIsDisabled: Boolean = false,
    initialDefault: Boolean = false,
    initialOverrides: ProjectOverridesDto? = null,
) : JDialog(owner, title, true) {
    private val nameField =
        JTextField(initialName).apply {
            preferredSize = Dimension(480, 30) // Min 480px width as required
            toolTipText = "Project display name."
        }

    private val descriptionArea =
        JTextArea(initialDescription.ifEmpty { "Optional project description" }, 4, 50).apply {
            // Add placeholder functionality
            if (initialDescription.isEmpty()) {
                foreground = Color.GRAY
                addFocusListener(
                    object : FocusAdapter() {
                        override fun focusGained(e: FocusEvent) {
                            if (text == "Optional project description") {
                                text = ""
                                foreground = Color.BLACK
                            }
                        }

                        override fun focusLost(e: FocusEvent) {
                            if (text.trim().isEmpty()) {
                                text = "Optional project description"
                                foreground = Color.GRAY
                            }
                        }
                    },
                )
            }
        }
    private val projectPathField =
        JTextField(initialProjectPath).apply {
            preferredSize = Dimension(480, 30)
            toolTipText = "Path within client mono-repository (e.g., services/auth-service)."
        }
    private val languagesField =
        JTextField(initialLanguages).apply {
            preferredSize = Dimension(480, 30)
            toolTipText = "Programming languages used in project (comma-separated)."
        }
    private val inspirationOnlyCheckbox =
        JCheckBox("Inspiration only", initialInspirationOnly).apply {
            toolTipText = "If checked, this project is used only for inspiration and not active development."
        }
    private val includeGlobsField =
        JTextField(initialIncludeGlobs).apply {
            preferredSize = Dimension(480, 30)
            toolTipText = "File patterns to include in indexing (comma-separated glob patterns)."
        }
    private val excludeGlobsField =
        JTextField(initialExcludeGlobs).apply {
            preferredSize = Dimension(480, 30)
            toolTipText = "File patterns to exclude from indexing (comma-separated glob patterns)."
        }
    private val maxFileSizeMBField =
        JTextField(initialMaxFileSizeMB.toString()).apply {
            preferredSize = Dimension(480, 30)
            toolTipText = "Maximum file size in MB to include in indexing."
        }
    private val isDisabledCheckbox =
        JCheckBox("Project disabled", initialIsDisabled).apply {
            toolTipText = "If checked, this project is globally disabled."
        }
    private val defaultCheckbox = JCheckBox("Set as default project", initialDefault)

    // Project Override Panels
    private val gitOverridePanel =
        ProjectGitOverridePanel(
            initialGitRemoteUrl = initialOverrides?.gitRemoteUrl,
            initialGitAuthType = initialOverrides?.gitAuthType,
            initialGitConfig = initialOverrides?.gitConfig,
            initialSshPrivateKey = null,
            initialSshPublicKey = null,
            initialSshPassphrase = null,
            initialHttpsToken = null,
            initialHttpsUsername = null,
            initialHttpsPassword = null,
            initialGpgPrivateKey = null,
            initialGpgPublicKey = null,
            initialGpgPassphrase = null,
        )

    private val okButton = JButton("OK")
    private val cancelButton = JButton("Cancel")

    var result: ProjectResult? = null
    var gitOverrideRequest: ProjectGitOverrideRequestDto? = null

    init {
        // Basic dialog setup
        preferredSize = Dimension(720, 650)
        minimumSize = Dimension(700, 600)
        isResizable = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        // Component setup
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true

        // Button actions
        okButton.addActionListener { saveAndClose() }
        cancelButton.addActionListener { dispose() }

        // Build UI with tabs
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(16, 16, 16, 16)

        val tabbedPane = JTabbedPane()

        // Basic Information Tab
        val basicPanel = createBasicInfoPanel()
        tabbedPane.addTab("Basic Information", basicPanel)

        // Indexing Tab
        val indexingPanel = createIndexingPanel()
        tabbedPane.addTab("Indexing", indexingPanel)

        // Advanced Tab
        val advancedPanel = createAdvancedPanel()
        tabbedPane.addTab("Advanced", advancedPanel)

        // Project Overrides Tab
        val overridesPanel = createProjectOverridesPanel()
        tabbedPane.addTab("Project Overrides", overridesPanel)

        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.add(okButton)
        buttonPanel.add(cancelButton)

        panel.add(tabbedPane, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        add(panel)

        // Set focus order: nameField → pathField → browseButton → descriptionArea → defaultCheckbox → okButton → cancelButton
        val focusOrder =
            arrayOf(nameField, descriptionArea, defaultCheckbox, okButton, cancelButton)
        for (i in 0 until focusOrder.size - 1) {
            focusOrder[i].nextFocusableComponent = focusOrder[i + 1]
        }

        // Add ESC key handling - ESC acts as Cancel
        addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        dispose() // Close dialog without saving
                    }
                }
            },
        )

        // Make sure the dialog can receive key events
        isFocusable = true
    }

    private fun createBasicInfoPanel(): JPanel = createBasicInfoPanelInternal()

    private fun createBasicInfoPanelInternal(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(8, 8, 8, 8)

        var row = 0

        // Project name (required)
        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Name:*"), gbc)
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(nameField, gbc)
        row++

        // Project path in mono-repo
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Project path:"), gbc)
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(projectPathField, gbc)
        row++

        // Project description (optional)
        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Description:"), gbc)
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        panel.add(JScrollPane(descriptionArea), gbc)
        row++

        // Languages
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        panel.add(JLabel("Languages:"), gbc)
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(languagesField, gbc)
        row++

        // Checkboxes
        gbc.gridx = 1
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(defaultCheckbox, gbc)

        return panel
    }

    private fun createIndexingPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(8, 8, 8, 8)

        var row = 0

        // Include Globs
        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Include Patterns:"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(includeGlobsField, gbc)
        row++

        // Exclude Globs
        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Exclude Patterns:"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(excludeGlobsField, gbc)
        row++

        // Max File Size
        gbc.gridx = 0
        gbc.gridy = row
        gbc.anchor = GridBagConstraints.LINE_END
        gbc.fill =
            GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("Max File Size (MB):"), gbc)
        gbc.gridx = 1
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(maxFileSizeMBField, gbc)
        row++

        // Add spacer to push content to top
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        return panel
    }

    private fun createAdvancedPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(8, 8, 8, 8)

        var row = 0

        // Checkboxes
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.NONE
        panel.add(inspirationOnlyCheckbox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.LINE_START
        gbc.fill =
            GridBagConstraints.NONE
        panel.add(isDisabledCheckbox, gbc)
        row++

        // Add spacer to push content to top
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        return panel
    }

    private fun createProjectOverridesPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // Git Configuration Override Tab (only one override now, no tabs needed)
        panel.add(JScrollPane(gitOverridePanel), BorderLayout.CENTER)

        return panel
    }

    private fun saveAndClose() {
        val name = nameField.text.trim()
        val description =
            if (descriptionArea.text.trim() == "Optional project description") "" else descriptionArea.text.trim()
        val projectPath = projectPathField.text.trim().ifEmpty { null }
        val languages =
            languagesField.text
                .trim()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val inspirationOnly = inspirationOnlyCheckbox.isSelected
        val includeGlobs =
            includeGlobsField.text
                .trim()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val excludeGlobs =
            excludeGlobsField.text
                .trim()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val maxFileSizeMB =
            try {
                maxFileSizeMBField.text.trim().toInt()
            } catch (_: NumberFormatException) {
                5 // default value
            }
        val isDisabled = isDisabledCheckbox.isSelected
        val isDefault = defaultCheckbox.isSelected

        // Validate required fields
        if (name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Project name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        if (maxFileSizeMB <= 0) {
            JOptionPane.showMessageDialog(
                this,
                "Max file size must be a positive number.",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        // Validate Git override fields
        if (!gitOverridePanel.validateFields()) {
            JOptionPane.showMessageDialog(
                this,
                "Git configuration validation failed. Check your Git override settings.",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        // Collect override data from panels
        val overrides =
            ProjectOverridesDto(
                gitRemoteUrl = gitOverridePanel.getGitRemoteUrl(),
                gitAuthType = gitOverridePanel.getGitAuthType(),
                gitConfig = gitOverridePanel.getGitConfig(),
            )

        // Collect Git override request with credentials
        gitOverrideRequest = gitOverridePanel.toProjectGitOverrideRequest()

        result =
            ProjectResult(
                name,
                description,
                projectPath,
                languages,
                inspirationOnly,
                includeGlobs,
                excludeGlobs,
                maxFileSizeMB,
                isDisabled,
                isDefault,
                overrides,
            )
        dispose()
    }
}
