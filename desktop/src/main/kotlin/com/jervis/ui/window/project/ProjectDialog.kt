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
    private val initialOverrides: ProjectOverridesDto? = null,
    // Optional integration context (used for editing an existing project)
    private val existingProjectId: String? = null,
    private val integrationSettingsService: com.jervis.service.IIntegrationSettingsService? = null,
    private val jiraSetupService: com.jervis.service.IJiraSetupService? = null,
    private val confluenceService: com.jervis.service.IConfluenceService? = null,
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
            toolTipText =
                "Optional: Path within client's mono-repository (e.g., 'services/backend-api'). Leave empty if project has its own Git repository."
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

    // Root panel for overrides tabs (Git/Jira/Confluence)
    private val overridesPanelRoot: JPanel = createProjectOverridesPanel()

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
        tabbedPane.addTab("Project Overrides", overridesPanelRoot)

        // Integration Tab (direct Jira/Confluence selectors) for existing projects
        if (existingProjectId != null && integrationSettingsService != null && jiraSetupService != null) {
            val integrationPanel =
                com.jervis.ui.component.ProjectIntegrationPanel(
                    existingProjectId,
                    integrationSettingsService,
                    jiraSetupService,
                    confluenceService,
                )
            tabbedPane.addTab("Integration", JScrollPane(integrationPanel))
        }

        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.add(okButton)
        buttonPanel.add(cancelButton)

        panel.add(tabbedPane, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        add(panel)

        // Set focus order using FocusTraversalPolicy to avoid deprecated nextFocusableComponent
        val focusOrder = listOf(nameField, descriptionArea, defaultCheckbox, okButton, cancelButton)
        this.focusTraversalPolicy =
            object : java.awt.FocusTraversalPolicy() {
                override fun getComponentAfter(
                    aContainer: java.awt.Container?,
                    aComponent: java.awt.Component?,
                ): java.awt.Component {
                    val idx = focusOrder.indexOf(aComponent)
                    val nextIdx = if (idx == -1) 0 else (idx + 1) % focusOrder.size
                    return focusOrder[nextIdx]
                }

                override fun getComponentBefore(
                    aContainer: java.awt.Container?,
                    aComponent: java.awt.Component?,
                ): java.awt.Component {
                    val idx = focusOrder.indexOf(aComponent)
                    val prevIdx = if (idx == -1) focusOrder.lastIndex else (idx - 1 + focusOrder.size) % focusOrder.size
                    return focusOrder[prevIdx]
                }

                override fun getFirstComponent(aContainer: java.awt.Container?): java.awt.Component = focusOrder.first()

                override fun getLastComponent(aContainer: java.awt.Container?): java.awt.Component = focusOrder.last()

                override fun getDefaultComponent(aContainer: java.awt.Container?): java.awt.Component = nameField
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
        panel.add(JLabel("Mono-Repo Path:"), gbc)
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

        val tabs = JTabbedPane()

        // Git Configuration Override
        tabs.addTab("Git", JScrollPane(gitOverridePanel))

        // Jira Override
        val jiraPanel = JPanel(GridBagLayout())
        val gbcJ =
            GridBagConstraints().apply {
                insets = Insets(8, 8, 8, 8)
                anchor = GridBagConstraints.LINE_START
            }
        var rowJ = 0
        val jiraKeyField = JTextField(20).apply { text = initialOverrides?.jiraProjectKey ?: "" }
        gbcJ.gridx = 0
        gbcJ.gridy = rowJ
        jiraPanel.add(JLabel("Jira Project Key:"), gbcJ)
        gbcJ.gridx = 1
        gbcJ.gridy = rowJ
        jiraPanel.add(jiraKeyField, gbcJ)

        // Confluence Override
        val confPanel = JPanel(GridBagLayout())
        val gbcC =
            GridBagConstraints().apply {
                insets = Insets(8, 8, 8, 8)
                anchor = GridBagConstraints.LINE_START
            }
        var rowC = 0
        val spaceKeyField = JTextField(20).apply { text = initialOverrides?.confluenceSpaceKey ?: "" }
        val rootPageField = JTextField(20).apply { text = initialOverrides?.confluenceRootPageId ?: "" }
        gbcC.gridx = 0
        gbcC.gridy = rowC
        confPanel.add(JLabel("Confluence Space Key:"), gbcC)
        gbcC.gridx = 1
        gbcC.gridy = rowC
        confPanel.add(spaceKeyField, gbcC)
        rowC++
        gbcC.gridx = 0
        gbcC.gridy = rowC
        confPanel.add(JLabel("Confluence Root Page ID:"), gbcC)
        gbcC.gridx = 1
        gbcC.gridy = rowC
        confPanel.add(rootPageField, gbcC)

        tabs.addTab("Jira", JScrollPane(jiraPanel))
        tabs.addTab("Confluence", JScrollPane(confPanel))

        panel.add(tabs, BorderLayout.CENTER)

        // Store fields in client properties for retrieval on save
        panel.putClientProperty("jiraKeyField", jiraKeyField)
        panel.putClientProperty("spaceKeyField", spaceKeyField)
        panel.putClientProperty("rootPageField", rootPageField)

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
        val jiraKey =
            (overridesPanelRoot.getClientProperty("jiraKeyField") as? JTextField)?.text?.trim()?.ifEmpty { null }
        val spaceKey =
            (overridesPanelRoot.getClientProperty("spaceKeyField") as? JTextField)?.text?.trim()?.ifEmpty { null }
        val rootPageId =
            (overridesPanelRoot.getClientProperty("rootPageField") as? JTextField)?.text?.trim()?.ifEmpty { null }

        val overrides =
            ProjectOverridesDto(
                gitRemoteUrl = gitOverridePanel.getGitRemoteUrl(),
                gitAuthType = gitOverridePanel.getGitAuthType(),
                gitConfig = gitOverridePanel.getGitConfig(),
                jiraProjectKey = jiraKey,
                confluenceSpaceKey = spaceKey,
                confluenceRootPageId = rootPageId,
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
