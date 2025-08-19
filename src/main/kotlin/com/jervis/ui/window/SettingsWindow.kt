package com.jervis.ui.window

import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.setting.SettingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants

class SettingsWindow(
    private val settingService: SettingService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
) : JFrame("Settings") {
    private val logger = KotlinLogging.logger {}

    // UI helpers
    private val ROW_H = 28
    private fun JTextField.compact(width: Int = 560): JTextField {
        preferredSize = Dimension(width, ROW_H)
        maximumSize = Dimension(Int.MAX_VALUE, ROW_H)
        return this
    }
    private fun makeRow(label: String, comp: JComponent, grid: GridBagConstraints, panel: JPanel) {
        val lbl = JLabel(label).apply { horizontalAlignment = SwingConstants.RIGHT }
        grid.gridx = 0; grid.weightx = 0.0; grid.anchor = GridBagConstraints.LINE_END
        panel.add(lbl, grid)
        grid.gridx = 1; grid.weightx = 1.0; grid.anchor = GridBagConstraints.LINE_START; grid.fill = GridBagConstraints.HORIZONTAL
        panel.add(comp, grid)
        grid.gridy++
    }

    // Fields required by the new spec
    private val anthropicApiKeyField = JTextField().compact().apply { toolTipText = "Anthropic API Key" }
    private val anthropicTestButton = JButton("Test")

    private val openaiApiKeyField = JTextField().compact().apply { toolTipText = "OpenAI API Key" }
    private val openaiTestButton = JButton("Test")

    private val ollamaUrlField = JTextField().compact().apply { toolTipText = "http://host:port" }
    private val ollamaTestButton = JButton("Test")

    private val lmStudioUrlField = JTextField().compact().apply { toolTipText = "http://host:port" }
    private val lmStudioTestButton = JButton("Test")

    init {
        layout = BorderLayout()

        // Panel with four rows
        val panel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val g = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 1.0
        }

        // Row: Anthropic API Key + Test
        val anthRow = JPanel(BorderLayout(8, 0)).apply {
            add(anthropicApiKeyField, BorderLayout.CENTER)
            add(anthropicTestButton, BorderLayout.EAST)
        }
        makeRow("Anthropic API Key:", anthRow, g, panel)

        // Row: OpenAI API Key + Test
        val oaiRow = JPanel(BorderLayout(8, 0)).apply {
            add(openaiApiKeyField, BorderLayout.CENTER)
            add(openaiTestButton, BorderLayout.EAST)
        }
        makeRow("OpenAI API Key:", oaiRow, g, panel)

        // Row: Ollama URL + Test
        val ollRow = JPanel(BorderLayout(8, 0)).apply {
            add(ollamaUrlField, BorderLayout.CENTER)
            add(ollamaTestButton, BorderLayout.EAST)
        }
        makeRow("Ollama URL:", ollRow, g, panel)

        // Row: LM Studio URL + Test
        val lmRow = JPanel(BorderLayout(8, 0)).apply {
            add(lmStudioUrlField, BorderLayout.CENTER)
            add(lmStudioTestButton, BorderLayout.EAST)
        }
        makeRow("LM Studio URL:", lmRow, g, panel)

        add(panel, BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val saveButton = JButton("Save")
        val okButton = JButton("OK")
        val cancelButton = JButton("Cancel")
        buttonPanel.add(saveButton)
        buttonPanel.add(okButton)
        buttonPanel.add(cancelButton)
        add(buttonPanel, BorderLayout.SOUTH)

        // Listeners
        anthropicTestButton.addActionListener {
            val key = anthropicApiKeyField.text
            if (key.isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter Anthropic API key", "Validation", JOptionPane.WARNING_MESSAGE)
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    anthropicTestButton.isEnabled = false
                    anthropicTestButton.text = "Testing..."
                    val models = try { llmCoordinator.listAnthropicModels(key) } catch (_: Exception) { emptySet() }
                    val configured = linkedSetOf(
                        settingService.anthropicModelCode,
                        settingService.anthropicModelExplanation,
                        settingService.anthropicModelSummary,
                        settingService.anthropicModelGeneral,
                    )
                    val sb = StringBuilder()
                    sb.append("Anthropic models (available vs configured):\n")
                    configured.filter { it.isNotBlank() }.forEach { m ->
                        val mark = if (models.contains(m)) "\u2713" else "\u2717"
                        sb.append("$mark $m\n")
                    }
                    if (configured.isEmpty()) sb.append("(No configured models)\n")
                    if (models.isEmpty()) sb.append("(Models endpoint returned no data or access denied)\n")
                    JOptionPane.showMessageDialog(
                        this@SettingsWindow,
                        sb.toString().trimEnd(),
                        "Anthropic",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    anthropicTestButton.isEnabled = true
                    anthropicTestButton.text = "Test"
                }
            }
        }

        openaiTestButton.addActionListener {
            val key = openaiApiKeyField.text
            if (key.isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter OpenAI API key", "Validation", JOptionPane.WARNING_MESSAGE)
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    openaiTestButton.isEnabled = false
                    openaiTestButton.text = "Testing..."
                    val models = try { llmCoordinator.listOpenAiModels(key) } catch (_: Exception) { emptySet() }
                    val configured = linkedSetOf(
                        settingService.openaiModelCode,
                        settingService.openaiModelExplanation,
                        settingService.openaiModelSummary,
                        settingService.openaiModelGeneral,
                    )
                    val sb = StringBuilder()
                    sb.append("OpenAI models (available vs configured):\n")
                    configured.filter { it.isNotBlank() }.forEach { m ->
                        val mark = if (models.contains(m)) "\u2713" else "\u2717"
                        sb.append("$mark $m\n")
                    }
                    if (configured.isEmpty()) sb.append("(No configured models)\n")
                    if (models.isEmpty()) sb.append("(Models endpoint returned no data or access denied)\n")
                    JOptionPane.showMessageDialog(
                        this@SettingsWindow,
                        sb.toString().trimEnd(),
                        "OpenAI",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    openaiTestButton.isEnabled = true
                    openaiTestButton.text = "Test"
                }
            }
        }

        ollamaTestButton.addActionListener {
            val url = ollamaUrlField.text.trim()
            if (url.isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter Ollama URL", "Validation", JOptionPane.WARNING_MESSAGE)
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    ollamaTestButton.isEnabled = false
                    ollamaTestButton.text = "Testing..."
                    val connected = try { ollamaService.testConnection(url) } catch (_: Exception) { false }
                    val models = if (connected) try { ollamaService.getAvailableModels(url) } catch (_: Exception) { emptyList() } else emptyList()
                    val available = models.map { it.name }.toSet()
                    val infoByName = models.associateBy({ it.name }, { "(size=${it.size}B, modified=${it.modified})" })

                    val required = mutableListOf<Pair<String, String>>()
                    if (settingService.embeddingModelType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                        required += Pair("[embedding]", settingService.embeddingModelName)
                    }
                    if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                        required += Pair("[simple]", settingService.modelSimpleName)
                    }
                    if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                        required += Pair("[complex]", settingService.modelComplexName)
                    }
                    if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                        required += Pair("[finalizing]", settingService.modelFinalizingName)
                    }

                    val sb = StringBuilder()
                    sb.append(if (connected) "Connected to Ollama.\n" else "Failed to connect to Ollama.\n")
                    if (required.isEmpty()) {
                        sb.append("No OLLAMA-configured models in settings.\n")
                    } else {
                        sb.append("Models:\n")
                        required.filter { it.second.isNotBlank() }.forEach { (tag, name) ->
                            val mark = if (available.contains(name)) "\u2713" else "\u2717"
                            val extra = infoByName[name]?.let { " $it" } ?: ""
                            sb.append("$mark $name $tag$extra\n")
                        }
                    }

                    // Always show Ollama embedding/translation configured models
                    val olText = settingService.ollamaTextEmbeddingModelName
                    val olCode = settingService.ollamaCodeEmbeddingModelName
                    val olTrans = settingService.ollamaTranslationModelName
                    if (olText.isNotBlank() || olCode.isNotBlank() || olTrans.isNotBlank()) {
                        sb.append("\nOllama specifics:\n")
                        if (olText.isNotBlank()) {
                            val mark = if (available.contains(olText)) "\u2713" else "\u2717"
                            sb.append("$mark $olText [embedding-text] (max=${settingService.ollamaTextEmbeddingMaxTokens})\n")
                        }
                        if (olCode.isNotBlank()) {
                            val mark = if (available.contains(olCode)) "\u2713" else "\u2717"
                            sb.append("$mark $olCode [embedding-code] (max=${settingService.ollamaCodeEmbeddingMaxTokens})\n")
                        }
                        if (olTrans.isNotBlank()) {
                            val mark = if (available.contains(olTrans)) "\u2713" else "\u2717"
                            sb.append("$mark $olTrans [translation-quick] (max=${settingService.ollamaTranslationMaxTokens})\n")
                        }
                    }

                    // Quick chat on first configured chat model
                    var chatModel: String? = null
                    if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.OLLAMA && settingService.modelSimpleName.isNotBlank()) chatModel = settingService.modelSimpleName
                    else if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.OLLAMA && settingService.modelComplexName.isNotBlank()) chatModel = settingService.modelComplexName
                    else if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.OLLAMA && settingService.modelFinalizingName.isNotBlank()) chatModel = settingService.modelFinalizingName

                    if (connected && chatModel != null && available.contains(chatModel)) {
                        val reply = try { ollamaService.quickChat(url, chatModel, "Ahoj! Odpověz krátce jednou větou.") } catch (_: Exception) { null }
                        if (!reply.isNullOrBlank()) {
                            val snippet = if (reply.length > 160) reply.substring(0, 160) + "…" else reply
                            sb.append("\nShort reply from $chatModel:\n$snippet")
                        }
                    }

                    JOptionPane.showMessageDialog(
                        this@SettingsWindow,
                        sb.toString().trimEnd(),
                        "Ollama",
                        if (connected) JOptionPane.INFORMATION_MESSAGE else JOptionPane.ERROR_MESSAGE,
                    )

                    ollamaTestButton.isEnabled = true
                    ollamaTestButton.text = "Test"
                }
            }
        }

        lmStudioTestButton.addActionListener {
            val url = lmStudioUrlField.text.trim()
            if (url.isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter LM Studio URL", "Validation", JOptionPane.WARNING_MESSAGE)
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    lmStudioTestButton.isEnabled = false
                    lmStudioTestButton.text = "Testing..."
                    val connected = try { lmStudioService.testConnection(url) } catch (_: Exception) { false }
                    val models = if (connected) try { lmStudioService.getAvailableModels(url) } catch (_: Exception) { emptyList() } else emptyList()
                    val available = models.map { it.id }.toSet()

                    val required = mutableListOf<Pair<String, String>>()
                    if (settingService.embeddingModelType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                        required += Pair("[embedding]", settingService.embeddingModelName)
                    }
                    if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                        required += Pair("[simple]", settingService.modelSimpleName)
                    }
                    if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                        required += Pair("[complex]", settingService.modelComplexName)
                    }
                    if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                        required += Pair("[finalizing]", settingService.modelFinalizingName)
                    }

                    val sb = StringBuilder()
                    sb.append(if (connected) "Connected to LM Studio.\n" else "Failed to connect to LM Studio.\n")
                    if (required.isEmpty()) {
                        sb.append("No LM STUDIO-configured models in settings.\n")
                    } else {
                        sb.append("Models:\n")
                        required.filter { it.second.isNotBlank() }.forEach { (tag, name) ->
                            val mark = if (available.contains(name)) "\u2713" else "\u2717"
                            sb.append("$mark $name $tag\n")
                        }
                    }

                    // Always show LM Studio embedding/translation configured models
                    val lmText = settingService.lmStudioEmbeddingTextModelName
                    val lmCode = settingService.lmStudioEmbeddingCodeModelName
                    val lmTrans = settingService.lmStudioTranslationQuickModelName
                    if (lmText.isNotBlank() || lmCode.isNotBlank() || lmTrans.isNotBlank()) {
                        sb.append("\nLM Studio specifics:\n")
                        if (lmText.isNotBlank()) {
                            val mark = if (available.contains(lmText)) "\u2713" else "\u2717"
                            sb.append("$mark $lmText [embedding-text] (max=${settingService.lmStudioEmbeddingTextMaxTokens})\n")
                        }
                        if (lmCode.isNotBlank()) {
                            val mark = if (available.contains(lmCode)) "\u2713" else "\u2717"
                            sb.append("$mark $lmCode [embedding-code] (max=${settingService.lmStudioEmbeddingCodeMaxTokens})\n")
                        }
                        if (lmTrans.isNotBlank()) {
                            val mark = if (available.contains(lmTrans)) "\u2713" else "\u2717"
                            sb.append("$mark $lmTrans [translation-quick] (max=${settingService.lmStudioTranslationQuickMaxTokens}, exceptional=${settingService.lmStudioTranslationQuickMaxTokensExceptional})\n")
                        }
                    }

                    // Quick chat on first configured chat model
                    var chatModel: String? = null
                    if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.LM_STUDIO && settingService.modelSimpleName.isNotBlank()) chatModel = settingService.modelSimpleName
                    else if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.LM_STUDIO && settingService.modelComplexName.isNotBlank()) chatModel = settingService.modelComplexName
                    else if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.LM_STUDIO && settingService.modelFinalizingName.isNotBlank()) chatModel = settingService.modelFinalizingName

                    if (connected && chatModel != null && available.contains(chatModel)) {
                        val reply = try { lmStudioService.quickChat(url, chatModel, "Ahoj! Odpověz krátce jednou větou.") } catch (_: Exception) { null }
                        if (!reply.isNullOrBlank()) {
                            val snippet = if (reply.length > 160) reply.substring(0, 160) + "…" else reply
                            sb.append("\nShort reply from $chatModel:\n$snippet")
                        }
                    }

                    JOptionPane.showMessageDialog(
                        this@SettingsWindow,
                        sb.toString().trimEnd(),
                        "LM Studio",
                        if (connected) JOptionPane.INFORMATION_MESSAGE else JOptionPane.ERROR_MESSAGE,
                    )

                    lmStudioTestButton.isEnabled = true
                    lmStudioTestButton.text = "Test"
                }
            }
        }

        fun saveSettings(): Boolean {
            // Persist only four values
            settingService.anthropicApiKey = anthropicApiKeyField.text
            settingService.openaiApiKey = openaiApiKeyField.text
            settingService.ollamaUrl = ollamaUrlField.text.trim()
            settingService.lmStudioUrl = lmStudioUrlField.text.trim()
            // Optionally toggle enabled flags based on URL presence
            try {
                settingService.ollamaEnabled = settingService.ollamaUrl.isNotBlank()
            } catch (_: Exception) {}
            try {
                settingService.lmStudioEnabled = settingService.lmStudioUrl.isNotBlank()
            } catch (_: Exception) {}
            logger.info { "Settings (keys/urls) saved" }
            return true
        }

        saveButton.addActionListener {
            if (saveSettings()) JOptionPane.showMessageDialog(this, "Settings saved.")
        }
        okButton.addActionListener {
            if (saveSettings()) isVisible = false
        }
        cancelButton.addActionListener { isVisible = false }

        // Window sizing
        preferredSize = Dimension(720, 320)
        minimumSize = Dimension(700, 300)
        isResizable = false
        pack()
        setLocationRelativeTo(null)

        // ESC closes
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    isVisible = false
                }
            }
        })
        isFocusable = true

        // Load initial values
        CoroutineScope(Dispatchers.Main).launch { loadSettings() }
    }

    private suspend fun loadSettings() {
        anthropicApiKeyField.text = settingService.anthropicApiKey
        openaiApiKeyField.text = settingService.openaiApiKey
        ollamaUrlField.text = settingService.ollamaUrl
        lmStudioUrlField.text = settingService.lmStudioUrl
    }
}
