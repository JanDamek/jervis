package com.jervis.ui.component

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.EmailConn
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.GitConn
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.JiraConn
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import com.jervis.domain.client.SlackConn
import com.jervis.domain.client.TeamsConn
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.border.TitledBorder

/**
 * Shared UI components for client settings that can be reused in both client and project dialogs.
 * These components handle the complex configuration objects like Guidelines, ReviewPolicy, etc.
 */
object ClientSettingsComponents {
    /**
     * Creates a panel for Guidelines configuration
     */
    fun createGuidelinesPanel(guidelines: Guidelines = Guidelines()): GuidelinesPanel = GuidelinesPanel(guidelines)

    /**
     * Creates a panel for ReviewPolicy configuration
     */
    fun createReviewPolicyPanel(reviewPolicy: ReviewPolicy = ReviewPolicy()): ReviewPolicyPanel = ReviewPolicyPanel(reviewPolicy)

    /**
     * Creates a panel for Formatting configuration
     */
    fun createFormattingPanel(formatting: Formatting = Formatting()): FormattingPanel = FormattingPanel(formatting)

    /**
     * Creates a panel for SecretsPolicy configuration
     */
    fun createSecretsPolicyPanel(secretsPolicy: SecretsPolicy = SecretsPolicy()): SecretsPolicyPanel = SecretsPolicyPanel(secretsPolicy)

    /**
     * Creates a panel for Anonymization configuration
     */
    fun createAnonymizationPanel(anonymization: Anonymization = Anonymization()): AnonymizationPanel = AnonymizationPanel(anonymization)

    /**
     * Creates a panel for InspirationPolicy configuration
     */
    fun createInspirationPolicyPanel(inspirationPolicy: InspirationPolicy = InspirationPolicy()): InspirationPolicyPanel =
        InspirationPolicyPanel(inspirationPolicy)

    /**
     * Creates a panel for ClientTools configuration
     */
    fun createClientToolsPanel(clientTools: ClientTools = ClientTools()): ClientToolsPanel = ClientToolsPanel(clientTools)

    class GuidelinesPanel(
        initialGuidelines: Guidelines = Guidelines(),
    ) : JPanel(GridBagLayout()) {
        private val codeStyleDocUrlField =
            JTextField(initialGuidelines.codeStyleDocUrl ?: "").apply {
                preferredSize = Dimension(400, 30)
                toolTipText = "URL to code style documentation"
            }

        private val commitMessageConventionField =
            JTextField(initialGuidelines.commitMessageConvention).apply {
                preferredSize = Dimension(400, 30)
                toolTipText = "Commit message convention (e.g., conventional-commits)"
            }

        private val branchingModelField =
            JTextField(initialGuidelines.branchingModel).apply {
                preferredSize = Dimension(400, 30)
                toolTipText = "Branching model (e.g., git-flow, github-flow)"
            }

        private val testCoverageSpinner =
            JSpinner(SpinnerNumberModel(initialGuidelines.testCoverageTarget, 0, 100, 5)).apply {
                preferredSize = Dimension(100, 30)
                toolTipText = "Target test coverage percentage"
            }

        init {
            border = TitledBorder("Coding Guidelines")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Code Style Doc URL
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Code Style Doc URL:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(codeStyleDocUrlField, gbc)
            row++

            // Commit Message Convention
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Commit Convention:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(commitMessageConventionField, gbc)
            row++

            // Branching Model
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Branching Model:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(branchingModelField, gbc)
            row++

            // Test Coverage Target
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Test Coverage Target (%):"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx =
                0.0
            add(testCoverageSpinner, gbc)
            row++

            // Add spacer
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 1.0
            add(JPanel(), gbc)
        }

        fun getGuidelines(): Guidelines =
            Guidelines(
                codeStyleDocUrl = codeStyleDocUrlField.text.trim().ifEmpty { null },
                commitMessageConvention = commitMessageConventionField.text.trim(),
                branchingModel = branchingModelField.text.trim(),
                testCoverageTarget = testCoverageSpinner.value as Int,
            )
    }

    class ReviewPolicyPanel(
        initialReviewPolicy: ReviewPolicy = ReviewPolicy(),
    ) : JPanel(GridBagLayout()) {
        private val requireCodeOwnerCheckbox =
            JCheckBox("Require Code Owner", initialReviewPolicy.requireCodeOwner).apply {
                toolTipText = "Require code owner approval for changes"
            }

        private val minApprovalsSpinner =
            JSpinner(SpinnerNumberModel(initialReviewPolicy.minApprovals, 0, 10, 1)).apply {
                preferredSize = Dimension(100, 30)
                toolTipText = "Minimum number of approvals required"
            }

        private val reviewersHintsArea =
            JTextArea(initialReviewPolicy.reviewersHints.joinToString("\n"), 4, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "List of suggested reviewers (one per line)"
            }

        init {
            border = TitledBorder("Review Policy")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Require Code Owner
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(requireCodeOwnerCheckbox, gbc)
            row++

            // Min Approvals
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Min Approvals:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx =
                0.0
            add(minApprovalsSpinner, gbc)
            row++

            // Reviewers Hints
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Reviewers Hints:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 1.0
            add(JScrollPane(reviewersHintsArea), gbc)
        }

        fun getReviewPolicy(): ReviewPolicy {
            val reviewersHints =
                reviewersHintsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return ReviewPolicy(
                requireCodeOwner = requireCodeOwnerCheckbox.isSelected,
                minApprovals = minApprovalsSpinner.value as Int,
                reviewersHints = reviewersHints,
            )
        }
    }

    class FormattingPanel(
        initialFormatting: Formatting = Formatting(),
    ) : JPanel(GridBagLayout()) {
        private val formatterField =
            JTextField(initialFormatting.formatter).apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Code formatter (e.g., ktlint, prettier)"
            }

        private val versionField =
            JTextField(initialFormatting.version ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Formatter version (optional)"
            }

        private val lineWidthSpinner =
            JSpinner(SpinnerNumberModel(initialFormatting.lineWidth, 80, 200, 10)).apply {
                preferredSize = Dimension(100, 30)
                toolTipText = "Maximum line width"
            }

        private val tabWidthSpinner =
            JSpinner(SpinnerNumberModel(initialFormatting.tabWidth, 1, 8, 1)).apply {
                preferredSize = Dimension(100, 30)
                toolTipText = "Tab width in spaces"
            }

        private val rulesArea =
            JTextArea(formatRulesForDisplay(initialFormatting.rules), 6, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Custom formatting rules (key=value, one per line)"
            }

        init {
            border = TitledBorder("Formatting")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Formatter
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Formatter:*"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(formatterField, gbc)
            row++

            // Version
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Version:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(versionField, gbc)
            row++

            // Line Width
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Line Width:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx =
                0.0
            add(lineWidthSpinner, gbc)
            row++

            // Tab Width
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Tab Width:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx =
                0.0
            add(tabWidthSpinner, gbc)
            row++

            // Rules
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Custom Rules:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 1.0
            add(JScrollPane(rulesArea), gbc)
        }

        private fun formatRulesForDisplay(rules: Map<String, String>): String = rules.map { "${it.key}=${it.value}" }.joinToString("\n")

        private fun parseRulesFromDisplay(text: String): Map<String, String> =
            text
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("=") }
                .associate { line ->
                    val parts = line.split("=", limit = 2)
                    parts[0].trim() to parts.getOrNull(1)?.trim().orEmpty()
                }

        fun getFormatting(): Formatting =
            Formatting(
                formatter = formatterField.text.trim(),
                version = versionField.text.trim().ifEmpty { null },
                lineWidth = lineWidthSpinner.value as Int,
                tabWidth = tabWidthSpinner.value as Int,
                rules = parseRulesFromDisplay(rulesArea.text),
            )
    }

    class SecretsPolicyPanel(
        initialSecretsPolicy: SecretsPolicy = SecretsPolicy(),
    ) : JPanel(GridBagLayout()) {
        private val bannedPatternsArea =
            JTextArea(initialSecretsPolicy.bannedPatterns.joinToString("\n"), 6, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Banned patterns for secrets detection (regex, one per line)"
            }

        private val cloudUploadAllowedCheckbox =
            JCheckBox("Allow Cloud Upload", initialSecretsPolicy.cloudUploadAllowed).apply {
                toolTipText = "Allow uploading code to cloud services"
            }

        private val allowPIICheckbox =
            JCheckBox("Allow PII", initialSecretsPolicy.allowPII).apply {
                toolTipText = "Allow personally identifiable information in code"
            }

        init {
            border = TitledBorder("Secrets Policy")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Banned Patterns
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Banned Patterns:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 1.0
            add(JScrollPane(bannedPatternsArea), gbc)
            row++

            // Checkboxes
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(cloudUploadAllowedCheckbox, gbc)
            row++

            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(allowPIICheckbox, gbc)
        }

        fun getSecretsPolicy(): SecretsPolicy {
            val bannedPatterns =
                bannedPatternsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return SecretsPolicy(
                bannedPatterns = bannedPatterns,
                cloudUploadAllowed = cloudUploadAllowedCheckbox.isSelected,
                allowPII = allowPIICheckbox.isSelected,
            )
        }
    }

    class AnonymizationPanel(
        initialAnonymization: Anonymization = Anonymization(),
    ) : JPanel(GridBagLayout()) {
        private val enabledCheckbox =
            JCheckBox("Enable Anonymization", initialAnonymization.enabled).apply {
                toolTipText = "Enable anonymization of sensitive data"
            }

        private val rulesArea =
            JTextArea(initialAnonymization.rules.joinToString("\n"), 8, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Anonymization rules (regex -> replacement, one per line)"
            }

        init {
            border = TitledBorder("Anonymization")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Enabled checkbox
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(enabledCheckbox, gbc)
            row++

            // Rules
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Rules:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 1.0
            add(JScrollPane(rulesArea), gbc)
        }

        fun getAnonymization(): Anonymization {
            val rules =
                rulesArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return Anonymization(
                enabled = enabledCheckbox.isSelected,
                rules = rules,
            )
        }
    }

    class InspirationPolicyPanel(
        initialInspirationPolicy: InspirationPolicy = InspirationPolicy(),
    ) : JPanel(GridBagLayout()) {
        private val allowCrossClientInspirationCheckbox =
            JCheckBox("Allow Cross-Client Inspiration", initialInspirationPolicy.allowCrossClientInspiration).apply {
                toolTipText = "Allow using code from other clients for inspiration"
            }

        private val allowedClientSlugsArea =
            JTextArea(initialInspirationPolicy.allowedClientSlugs.joinToString("\n"), 3, 30).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Allowed client slugs for inspiration (one per line)"
            }

        private val disallowedClientSlugsArea =
            JTextArea(initialInspirationPolicy.disallowedClientSlugs.joinToString("\n"), 3, 30).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Disallowed client slugs for inspiration (one per line)"
            }

        private val enforceFullAnonymizationCheckbox =
            JCheckBox("Enforce Full Anonymization", initialInspirationPolicy.enforceFullAnonymization).apply {
                toolTipText = "Enforce full anonymization when using inspiration"
            }

        private val maxSnippetsSpinner =
            JSpinner(SpinnerNumberModel(initialInspirationPolicy.maxSnippetsPerForeignClient, 0, 50, 1)).apply {
                preferredSize = Dimension(100, 30)
                toolTipText = "Maximum code snippets per foreign client"
            }

        init {
            border = TitledBorder("Inspiration Policy")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Allow Cross-Client Inspiration
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(allowCrossClientInspirationCheckbox, gbc)
            row++

            // Allowed Client Slugs
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Allowed Clients:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 0.3
            add(JScrollPane(allowedClientSlugsArea), gbc)
            row++

            // Disallowed Client Slugs
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Disallowed Clients:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 0.3
            add(JScrollPane(disallowedClientSlugsArea), gbc)
            row++

            // Enforce Full Anonymization
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(enforceFullAnonymizationCheckbox, gbc)
            row++

            // Max Snippets
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Max Snippets per Client:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx =
                0.0
            add(maxSnippetsSpinner, gbc)
            row++

            // Add spacer
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 0.4
            add(JPanel(), gbc)
        }

        fun getInspirationPolicy(): InspirationPolicy {
            val allowedSlugs =
                allowedClientSlugsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            val disallowedSlugs =
                disallowedClientSlugsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return InspirationPolicy(
                allowCrossClientInspiration = allowCrossClientInspirationCheckbox.isSelected,
                allowedClientSlugs = allowedSlugs,
                disallowedClientSlugs = disallowedSlugs,
                enforceFullAnonymization = enforceFullAnonymizationCheckbox.isSelected,
                maxSnippetsPerForeignClient = maxSnippetsSpinner.value as Int,
            )
        }
    }

    class ClientToolsPanel(
        initialClientTools: ClientTools = ClientTools(),
    ) : JPanel(GridBagLayout()) {
        // Git Connection Fields
        private val gitProviderField =
            JTextField(initialClientTools.git?.provider ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Git provider (github, gitlab, bitbucket)"
            }

        private val gitBaseUrlField =
            JTextField(initialClientTools.git?.baseUrl ?: "").apply {
                preferredSize = Dimension(300, 30)
                toolTipText = "Base URL for Git provider"
            }

        private val gitAuthTypeField =
            JTextField(initialClientTools.git?.authType ?: "").apply {
                preferredSize = Dimension(150, 30)
                toolTipText = "Authentication type (pat, ssh, oauth)"
            }

        private val gitCredentialsRefField =
            JTextField(initialClientTools.git?.credentialsRef ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Reference to stored Git credentials"
            }

        // Jira Connection Fields
        private val jiraBaseUrlField =
            JTextField(initialClientTools.jira?.baseUrl ?: "").apply {
                preferredSize = Dimension(300, 30)
                toolTipText = "Base URL for Jira instance"
            }

        private val jiraTenantField =
            JTextField(initialClientTools.jira?.tenant ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Jira tenant identifier"
            }

        private val jiraScopesArea =
            JTextArea((initialClientTools.jira?.scopes ?: emptyList()).joinToString("\n"), 3, 30).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Jira scopes (one per line)"
            }

        private val jiraCredentialsRefField =
            JTextField(initialClientTools.jira?.credentialsRef ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Reference to stored Jira credentials"
            }

        // Slack Connection Fields
        private val slackWorkspaceField =
            JTextField(initialClientTools.slack?.workspace ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Slack workspace identifier"
            }

        private val slackScopesArea =
            JTextArea((initialClientTools.slack?.scopes ?: emptyList()).joinToString("\n"), 3, 30).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Slack scopes (one per line)"
            }

        private val slackCredentialsRefField =
            JTextField(initialClientTools.slack?.credentialsRef ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Reference to stored Slack credentials"
            }

        // Teams Connection Fields
        private val teamsTenantField =
            JTextField(initialClientTools.teams?.tenant ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Teams tenant identifier"
            }

        private val teamsScopesArea =
            JTextArea((initialClientTools.teams?.scopes ?: emptyList()).joinToString("\n"), 3, 30).apply {
                lineWrap = true
                wrapStyleWord = true
                toolTipText = "Teams scopes (one per line)"
            }

        private val teamsCredentialsRefField =
            JTextField(initialClientTools.teams?.credentialsRef ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Reference to stored Teams credentials"
            }

        // Email Connection Fields
        private val emailProtocolField =
            JTextField(initialClientTools.email?.protocol ?: "").apply {
                preferredSize = Dimension(150, 30)
                toolTipText = "Email protocol (imap, graph)"
            }

        private val emailServerField =
            JTextField(initialClientTools.email?.server ?: "").apply {
                preferredSize = Dimension(250, 30)
                toolTipText = "Email server address"
            }

        private val emailUsernameField =
            JTextField(initialClientTools.email?.username ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Email username"
            }

        private val emailCredentialsRefField =
            JTextField(initialClientTools.email?.credentialsRef ?: "").apply {
                preferredSize = Dimension(200, 30)
                toolTipText = "Reference to stored Email credentials"
            }

        init {
            border = TitledBorder("Client Tools")
            setupLayout()
        }

        private fun setupLayout() {
            val gbc = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5) }
            var row = 0

            // Git Connection Section
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Git Connection:").apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, gbc)
            row++

            // Git Provider
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Provider:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitProviderField, gbc)
            row++

            // Git Base URL
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Base URL:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitBaseUrlField, gbc)
            row++

            // Git Auth Type
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Auth Type:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitAuthTypeField, gbc)
            row++

            // Git Credentials Ref
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(gitCredentialsRefField, gbc)
            row++

            // Jira Connection Section
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Jira Connection:").apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, gbc)
            row++

            // Jira Base URL
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Base URL:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(jiraBaseUrlField, gbc)
            row++

            // Jira Tenant
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Tenant:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(jiraTenantField, gbc)
            row++

            // Jira Scopes
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Scopes:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 0.1
            add(JScrollPane(jiraScopesArea), gbc)
            row++

            // Jira Credentials Ref
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(jiraCredentialsRefField, gbc)
            row++

            // Slack Connection Section
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Slack Connection:").apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, gbc)
            row++

            // Slack Workspace
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Workspace:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(slackWorkspaceField, gbc)
            row++

            // Slack Scopes
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Scopes:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 0.1
            add(JScrollPane(slackScopesArea), gbc)
            row++

            // Slack Credentials Ref
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(slackCredentialsRefField, gbc)
            row++

            // Teams Connection Section
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Teams Connection:").apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, gbc)
            row++

            // Teams Tenant
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Tenant:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(teamsTenantField, gbc)
            row++

            // Teams Scopes
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill = GridBagConstraints.NONE
            add(JLabel("Scopes:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill = GridBagConstraints.BOTH
            gbc.weightx =
                1.0
            gbc.weighty = 0.1
            add(JScrollPane(teamsScopesArea), gbc)
            row++

            // Teams Credentials Ref
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weighty = 0.0
            add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(teamsCredentialsRefField, gbc)
            row++

            // Email Connection Section
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.NONE
            add(JLabel("Email Connection:").apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, gbc)
            row++

            // Email Protocol
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 1
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Protocol:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(emailProtocolField, gbc)
            row++

            // Email Server
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Server:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(emailServerField, gbc)
            row++

            // Email Username
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Username:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(emailUsernameField, gbc)
            row++

            // Email Credentials Ref
            gbc.gridx = 0
            gbc.gridy = row
            gbc.anchor = GridBagConstraints.LINE_END
            gbc.fill =
                GridBagConstraints.NONE
            gbc.weightx = 0.0
            add(JLabel("Credentials Ref:"), gbc)
            gbc.gridx = 1
            gbc.anchor = GridBagConstraints.LINE_START
            gbc.fill =
                GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            add(emailCredentialsRefField, gbc)
            row++

            // Add spacer for better layout
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weighty = 1.0
            add(JPanel(), gbc)
        }

        fun getClientTools(): ClientTools {
            val git =
                if (gitProviderField.text.trim().isNotEmpty() || gitBaseUrlField.text.trim().isNotEmpty() ||
                    gitAuthTypeField.text.trim().isNotEmpty() || gitCredentialsRefField.text.trim().isNotEmpty()
                ) {
                    GitConn(
                        provider = gitProviderField.text.trim().ifEmpty { null },
                        baseUrl = gitBaseUrlField.text.trim().ifEmpty { null },
                        authType = gitAuthTypeField.text.trim().ifEmpty { null },
                        credentialsRef = gitCredentialsRefField.text.trim().ifEmpty { null },
                    )
                } else {
                    null
                }

            val jira =
                if (jiraBaseUrlField.text.trim().isNotEmpty() || jiraTenantField.text.trim().isNotEmpty() ||
                    jiraScopesArea.text.trim().isNotEmpty() || jiraCredentialsRefField.text.trim().isNotEmpty()
                ) {
                    val scopes =
                        jiraScopesArea.text
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    JiraConn(
                        baseUrl = jiraBaseUrlField.text.trim().ifEmpty { null },
                        tenant = jiraTenantField.text.trim().ifEmpty { null },
                        scopes = if (scopes.isEmpty()) null else scopes,
                        credentialsRef = jiraCredentialsRefField.text.trim().ifEmpty { null },
                    )
                } else {
                    null
                }

            val slack =
                if (slackWorkspaceField.text.trim().isNotEmpty() || slackScopesArea.text.trim().isNotEmpty() ||
                    slackCredentialsRefField.text.trim().isNotEmpty()
                ) {
                    val scopes =
                        slackScopesArea.text
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    SlackConn(
                        workspace = slackWorkspaceField.text.trim().ifEmpty { null },
                        scopes = if (scopes.isEmpty()) null else scopes,
                        credentialsRef = slackCredentialsRefField.text.trim().ifEmpty { null },
                    )
                } else {
                    null
                }

            val teams =
                if (teamsTenantField.text.trim().isNotEmpty() || teamsScopesArea.text.trim().isNotEmpty() ||
                    teamsCredentialsRefField.text.trim().isNotEmpty()
                ) {
                    val scopes =
                        teamsScopesArea.text
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    TeamsConn(
                        tenant = teamsTenantField.text.trim().ifEmpty { null },
                        scopes = if (scopes.isEmpty()) null else scopes,
                        credentialsRef = teamsCredentialsRefField.text.trim().ifEmpty { null },
                    )
                } else {
                    null
                }

            val email =
                if (emailProtocolField.text.trim().isNotEmpty() || emailServerField.text.trim().isNotEmpty() ||
                    emailUsernameField.text.trim().isNotEmpty() || emailCredentialsRefField.text.trim().isNotEmpty()
                ) {
                    EmailConn(
                        protocol = emailProtocolField.text.trim().ifEmpty { null },
                        server = emailServerField.text.trim().ifEmpty { null },
                        username = emailUsernameField.text.trim().ifEmpty { null },
                        credentialsRef = emailCredentialsRefField.text.trim().ifEmpty { null },
                    )
                } else {
                    null
                }

            return ClientTools(
                git = git,
                jira = jira,
                slack = slack,
                teams = teams,
                email = email,
            )
        }
    }
}
