package com.jervis.ui.component

import com.jervis.dto.AnonymizationDto
import com.jervis.dto.FormattingDto
import com.jervis.dto.GuidelinesDto
import com.jervis.dto.InspirationPolicyDto
import com.jervis.dto.ReviewPolicyDto
import com.jervis.dto.SecretsPolicyDto
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
    fun createGuidelinesPanel(guidelines: GuidelinesDto = GuidelinesDto()): GuidelinesPanel = GuidelinesPanel(guidelines)

    /**
     * Creates a panel for ReviewPolicy configuration
     */
    fun createReviewPolicyPanel(reviewPolicy: ReviewPolicyDto = ReviewPolicyDto()): ReviewPolicyPanel = ReviewPolicyPanel(reviewPolicy)

    /**
     * Creates a panel for Formatting configuration
     */
    fun createFormattingPanel(formatting: FormattingDto = FormattingDto()): FormattingPanel = FormattingPanel(formatting)

    /**
     * Creates a panel for SecretsPolicy configuration
     */
    fun createSecretsPolicyPanel(secretsPolicy: SecretsPolicyDto = SecretsPolicyDto()): SecretsPolicyPanel =
        SecretsPolicyPanel(secretsPolicy)

    /**
     * Creates a panel for Anonymization configuration
     */
    fun createAnonymizationPanel(anonymization: AnonymizationDto = AnonymizationDto()): AnonymizationPanel =
        AnonymizationPanel(anonymization)

    /**
     * Creates a panel for InspirationPolicy configuration
     */
    fun createInspirationPolicyPanel(inspirationPolicy: InspirationPolicyDto = InspirationPolicyDto()): InspirationPolicyPanel =
        InspirationPolicyPanel(inspirationPolicy)

    class GuidelinesPanel(
        initialGuidelines: GuidelinesDto = GuidelinesDto(),
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

        fun getGuidelines(): GuidelinesDto =
            GuidelinesDto(
                codeStyleDocUrl = codeStyleDocUrlField.text.trim().ifEmpty { null },
                commitMessageConvention = commitMessageConventionField.text.trim(),
                branchingModel = branchingModelField.text.trim(),
                testCoverageTarget = testCoverageSpinner.value as Int,
            )
    }

    class ReviewPolicyPanel(
        initialReviewPolicy: ReviewPolicyDto = ReviewPolicyDto(),
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

        fun getReviewPolicy(): ReviewPolicyDto {
            val reviewersHints =
                reviewersHintsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return ReviewPolicyDto(
                requireCodeOwner = requireCodeOwnerCheckbox.isSelected,
                minApprovals = minApprovalsSpinner.value as Int,
                reviewersHints = reviewersHints,
            )
        }
    }

    class FormattingPanel(
        initialFormatting: FormattingDto = FormattingDto(),
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

        fun getFormatting(): FormattingDto =
            FormattingDto(
                formatter = formatterField.text.trim(),
                version = versionField.text.trim().ifEmpty { null },
                lineWidth = lineWidthSpinner.value as Int,
                tabWidth = tabWidthSpinner.value as Int,
                rules = parseRulesFromDisplay(rulesArea.text),
            )
    }

    class SecretsPolicyPanel(
        initialSecretsPolicy: SecretsPolicyDto = SecretsPolicyDto(),
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

        fun getSecretsPolicy(): SecretsPolicyDto {
            val bannedPatterns =
                bannedPatternsArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return SecretsPolicyDto(
                bannedPatterns = bannedPatterns,
                cloudUploadAllowed = cloudUploadAllowedCheckbox.isSelected,
                allowPII = allowPIICheckbox.isSelected,
            )
        }
    }

    class AnonymizationPanel(
        initialAnonymization: AnonymizationDto = AnonymizationDto(),
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

        fun getAnonymization(): AnonymizationDto {
            val rules =
                rulesArea.text
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            return AnonymizationDto(
                enabled = enabledCheckbox.isSelected,
                rules = rules,
            )
        }
    }

    class InspirationPolicyPanel(
        initialInspirationPolicy: InspirationPolicyDto = InspirationPolicyDto(),
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

        fun getInspirationPolicy(): InspirationPolicyDto {
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

            return InspirationPolicyDto(
                allowCrossClientInspiration = allowCrossClientInspirationCheckbox.isSelected,
                allowedClientSlugs = allowedSlugs,
                disallowedClientSlugs = disallowedSlugs,
                enforceFullAnonymization = enforceFullAnonymizationCheckbox.isSelected,
                maxSnippetsPerForeignClient = maxSnippetsSpinner.value as Int,
            )
        }
    }
}
