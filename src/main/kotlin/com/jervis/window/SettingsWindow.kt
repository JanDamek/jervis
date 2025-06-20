package com.jervis.window

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.service.SettingService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField

class SettingsWindow(
    private val settingService: SettingService,
    private val llmCoordinator: LlmCoordinator,
) : JFrame("Settings") {
    // Common settings
    private val portField = JTextField()
    private val qdrantUrlField = JTextField()

    // Anthropic settings
    private val anthropicApiKeyField = JTextField().apply {
        preferredSize = Dimension(400, 30)  // Wider field for API keys
        toolTipText = "Enter your Anthropic API key here"
    }
    private val anthropicRateLimitInputTokensField = JTextField()
    private val anthropicRateLimitOutputTokensField = JTextField()
    private val anthropicRateLimitWindowField = JTextField()

    // OpenAI settings
    private val openaiApiKeyField = JTextField().apply {
        preferredSize = Dimension(400, 30)  // Wider field for API keys
        toolTipText = "Enter your OpenAI API key here"
    }

    init {
        size = Dimension(700, 500)  // Increased width and height for better display of API keys
        setLocationRelativeTo(null)
        layout = BorderLayout()

        // Create tabbed pane
        val tabbedPane = JTabbedPane()

        // Create common settings panel
        val commonPanel = JPanel(GridLayout(2, 2, 10, 10))
        commonPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        commonPanel.add(JLabel("RAG API Port:"))
        commonPanel.add(portField)
        commonPanel.add(JLabel("Qdrant URL:"))
        commonPanel.add(qdrantUrlField)

        // Create Anthropic settings panel
        val anthropicPanel = JPanel(GridLayout(4, 2, 10, 10))
        anthropicPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        anthropicPanel.add(JLabel("Anthropic API Key:"))
        anthropicPanel.add(anthropicApiKeyField)
        anthropicPanel.add(JLabel("Input Rate Limit (tokens/min):"))
        anthropicPanel.add(anthropicRateLimitInputTokensField)
        anthropicPanel.add(JLabel("Output Rate Limit (tokens/min):"))
        anthropicPanel.add(anthropicRateLimitOutputTokensField)
        anthropicPanel.add(JLabel("Rate Limit Window (seconds):"))
        anthropicPanel.add(anthropicRateLimitWindowField)

        // Create OpenAI settings panel
        val openaiPanel = JPanel(GridLayout(1, 2, 10, 10))
        openaiPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        openaiPanel.add(JLabel("OpenAI API Key:"))
        openaiPanel.add(openaiApiKeyField)

        // Add panels to tabbed pane
        tabbedPane.addTab("Common", commonPanel)
        tabbedPane.addTab("Anthropic", anthropicPanel)
        tabbedPane.addTab("OpenAI", openaiPanel)

        // Add tabbed pane to frame
        add(tabbedPane, BorderLayout.CENTER)

        // Create button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))

        val saveButton = JButton("Save")
        saveButton.addActionListener {
            if (saveSettings()) {
                JOptionPane.showMessageDialog(this, "Settings saved.")
                isVisible = false // Close the dialog after saving
            }
        }

        val okButton = JButton("OK")
        okButton.addActionListener {
            if (saveSettings()) {
                isVisible = false // Close the dialog after saving
            }
        }

        val cancelButton = JButton("Cancel")
        cancelButton.addActionListener {
            isVisible = false // Close without saving
        }

        buttonPanel.add(saveButton)
        buttonPanel.add(okButton)
        buttonPanel.add(cancelButton)

        add(buttonPanel, BorderLayout.SOUTH)

        // Load current settings
        loadSettings()
    }

    private fun loadSettings() {
        // Load common settings
        portField.text = settingService.getStringValue(SettingService.RAG_PORT, "11434")
        qdrantUrlField.text = settingService.getStringValue(SettingService.QDRANT_URL, "http://localhost:6333")

        // Load Anthropic settings
        anthropicApiKeyField.text = settingService.getStringValue(SettingService.ANTHROPIC_API_KEY, "")
        anthropicRateLimitInputTokensField.text = settingService.getIntValue(SettingService.ANTHROPIC_RATE_LIMIT_INPUT_TOKENS, 20000).toString()
        anthropicRateLimitOutputTokensField.text = settingService.getIntValue(SettingService.ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS, 4000).toString()
        anthropicRateLimitWindowField.text = settingService.getIntValue(SettingService.ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS, 60).toString()

        // Load OpenAI settings
        openaiApiKeyField.text = settingService.getStringValue(SettingService.OPENAI_API_KEY, "")
    }

    private fun saveSettings(): Boolean {
        // Save common settings
        settingService.saveValue(SettingService.RAG_PORT, portField.text)
        settingService.saveValue(SettingService.QDRANT_URL, qdrantUrlField.text)

        // Check if Anthropic API key has changed
        val currentAnthropicApiKey = settingService.getStringValue(SettingService.ANTHROPIC_API_KEY, "")
        val newAnthropicApiKey = anthropicApiKeyField.text
        if (currentAnthropicApiKey != newAnthropicApiKey && newAnthropicApiKey.isNotBlank()) {
            // Verify the new API key
            if (!llmCoordinator.verifyAnthropicApiKey(newAnthropicApiKey)) {
                JOptionPane.showMessageDialog(
                    this,
                    "The Anthropic API key could not be verified. Please check it and try again.",
                    "API Key Verification Failed",
                    JOptionPane.ERROR_MESSAGE
                )
                return false
            }
        }

        // Save Anthropic API key
        settingService.saveValue(SettingService.ANTHROPIC_API_KEY, newAnthropicApiKey)

        // Check if OpenAI API key has changed
        val currentOpenAiApiKey = settingService.getStringValue(SettingService.OPENAI_API_KEY, "")
        val newOpenAiApiKey = openaiApiKeyField.text
        if (currentOpenAiApiKey != newOpenAiApiKey && newOpenAiApiKey.isNotBlank()) {
            // Verify the new API key
            if (!llmCoordinator.verifyOpenAiApiKey(newOpenAiApiKey)) {
                JOptionPane.showMessageDialog(
                    this,
                    "The OpenAI API key could not be verified. Please check it and try again.",
                    "API Key Verification Failed",
                    JOptionPane.ERROR_MESSAGE
                )
                return false
            }
        }

        // Save OpenAI API key
        settingService.saveValue(SettingService.OPENAI_API_KEY, newOpenAiApiKey)

        // Save rate limiting settings
        try {
            val inputRateLimitTokens = anthropicRateLimitInputTokensField.text.toInt()
            settingService.saveIntSetting(SettingService.ANTHROPIC_RATE_LIMIT_INPUT_TOKENS, inputRateLimitTokens)
        } catch (e: NumberFormatException) {
            JOptionPane.showMessageDialog(this, "Input rate limit must be a valid number", "Error", JOptionPane.ERROR_MESSAGE)
            return false
        }

        try {
            val outputRateLimitTokens = anthropicRateLimitOutputTokensField.text.toInt()
            settingService.saveIntSetting(SettingService.ANTHROPIC_RATE_LIMIT_OUTPUT_TOKENS, outputRateLimitTokens)
        } catch (e: NumberFormatException) {
            JOptionPane.showMessageDialog(this, "Output rate limit must be a valid number", "Error", JOptionPane.ERROR_MESSAGE)
            return false
        }

        try {
            val rateLimitWindow = anthropicRateLimitWindowField.text.toInt()
            settingService.saveIntSetting(SettingService.ANTHROPIC_RATE_LIMIT_WINDOW_SECONDS, rateLimitWindow)
        } catch (e: NumberFormatException) {
            JOptionPane.showMessageDialog(this, "Rate limit window must be a valid number", "Error", JOptionPane.ERROR_MESSAGE)
            return false
        }

        return true
    }
}
