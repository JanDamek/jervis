package com.jervis.ui.window

import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.events.SettingsChangeEvent
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.lmstudio.LMStudioService
import com.jervis.service.llm.ollama.OllamaService
import com.jervis.service.setting.SettingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JPasswordField
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

/**
 * Data class representing a model option in the UI
 */
data class ModelOption(
    val name: String,
    val provider: ModelProvider,
    val type: ModelType,
) {
    override fun toString(): String = name
}

/**
 * Custom renderer for ModelOption in combo boxes
 */
class ModelOptionRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel

        if (value is ModelOption) {
            val providerText =
                when (value.provider) {
                    ModelProvider.OLLAMA -> "Ollama"
                    ModelProvider.LM_STUDIO -> "LM Studio"
                    ModelProvider.OPENAI -> "OpenAI"
                    ModelProvider.ANTHROPIC -> "Anthropic"
                    ModelProvider.DJL -> "Internal embedding"
                }
            label.text = "${value.name} ($providerText)"
        }

        return label
    }
}

class SettingsWindow(
    private val settingService: SettingService,
    private val llmCoordinator: LlmCoordinator,
    private val ollamaService: OllamaService,
    private val lmStudioService: LMStudioService,
) : JFrame("Settings") {
    private val logger = KotlinLogging.logger {}
    
    // Compact UI constants
    private val ROW_H = 28
    
    // Extension function for compact text fields
    private fun JTextField.compact(width: Int = 560): JTextField {
        preferredSize = Dimension(width, ROW_H)
        maximumSize = Dimension(Int.MAX_VALUE, ROW_H)
        return this
    }
    
    // Helper function for making rows in GridBagLayout
    private fun makeRow(label: String, comp: JComponent, grid: GridBagConstraints, panel: JPanel) {
        val lbl = JLabel(label).apply {
            horizontalAlignment = SwingConstants.RIGHT
        }
        grid.gridx = 0; grid.weightx = 0.0; grid.anchor = GridBagConstraints.LINE_END
        panel.add(lbl, grid)
        grid.gridx = 1; grid.weightx = 1.0; grid.anchor = GridBagConstraints.LINE_START; grid.fill = GridBagConstraints.HORIZONTAL
        panel.add(comp, grid)
        grid.gridy++
    }

    // External model settings
    private val useOllamaCheckbox = JCheckBox("Use Ollama", true)
    private val ollamaUrlField = JTextField().compact()

    private val useLmStudioCheckbox = JCheckBox("Use LM Studio", true)
    private val lmStudioUrlField = JTextField().compact()

    // Combined model selection combo boxes
    private val simpleModelComboBox =
        JComboBox<ModelOption>().apply {
            isEditable = false
            renderer = ModelOptionRenderer()
        }

    private val coderModelComboBox =
        JComboBox<ModelOption>().apply {
            isEditable = false
            renderer = ModelOptionRenderer()
        }

    private val finalizerModelComboBox =
        JComboBox<ModelOption>().apply {
            isEditable = false
            renderer = ModelOptionRenderer()
        }

    private val embeddingModelComboBox =
        JComboBox<ModelOption>().apply {
            isEditable = false
            renderer = ModelOptionRenderer()
        }

    // Anthropic settings
    private val anthropicApiKeyField =
        JTextField().compact().apply {
            toolTipText = "Create at console.anthropic.com. Value is stored locally (encrypted)."
        }
    private val anthropicTestButton =
        JButton("Test").apply {
            toolTipText = "Test if the Anthropic API key is valid"
        }
    private val anthropicRateLimitInputTokensField = JTextField().apply {
        preferredSize = Dimension(140, ROW_H)
        toolTipText = "Maximum input tokens per minute"
    }
    private val anthropicRateLimitOutputTokensField = JTextField().apply {
        preferredSize = Dimension(140, ROW_H)
        toolTipText = "Maximum output tokens per minute"
    }
    private val anthropicRateLimitWindowField = JTextField().apply {
        preferredSize = Dimension(140, ROW_H)
        toolTipText = "Rate limit window in seconds"
    }

    // OpenAI settings
    private val openaiApiKeyField =
        JTextField().compact().apply {
            toolTipText = "Create at platform.openai.com. Value is stored locally (encrypted)."
        }
    private val openaiTestButton =
        JButton("Test").apply {
            toolTipText = "Test if the OpenAI API key is valid"
        }

    // LM Studio and Ollama model combo boxes
    private val lmStudioModelComboBox = JComboBox<ModelOption>()
    private val ollamaModelComboBox = JComboBox<ModelOption>()

    init {
        layout = BorderLayout()

        // Add listeners to test buttons
        anthropicTestButton.addActionListener {
            val apiKey = anthropicApiKeyField.text
            if (apiKey.isNotBlank()) {
                CoroutineScope(Dispatchers.Main).launch {
                    anthropicTestButton.isEnabled = false
                    anthropicTestButton.text = "Testing..."

                    val success = llmCoordinator.verifyAnthropicApiKey(apiKey)

                    if (success) {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Successfully verified Anthropic API key!",
                            "API Key Verification",
                            JOptionPane.INFORMATION_MESSAGE,
                        )

                        // Add Anthropic models to combo boxes
                        val anthropicModels =
                            listOf(
                                "claude-3-opus-20240229",
                                "claude-3-sonnet-20240229",
                                "claude-3-haiku-20240307",
                            )

                        // Clear existing Anthropic models from combo boxes
                        updateModelComboBoxes(removeProvider = ModelProvider.ANTHROPIC)

                        // Add Anthropic models to combo boxes
                        anthropicModels.forEach { model ->
                            simpleModelComboBox.addItem(ModelOption(model, ModelProvider.ANTHROPIC, ModelType.SIMPLE))
                            coderModelComboBox.addItem(ModelOption(model, ModelProvider.ANTHROPIC, ModelType.COMPLEX))
                            finalizerModelComboBox.addItem(
                                ModelOption(
                                    model,
                                    ModelProvider.ANTHROPIC,
                                    ModelType.FINALIZER,
                                ),
                            )
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Failed to verify Anthropic API key. Please check it and try again.",
                            "API Key Verification Failed",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }

                    anthropicTestButton.isEnabled = true
                    anthropicTestButton.text = "Test"
                }
            } else {
                JOptionPane.showMessageDialog(
                    this@SettingsWindow,
                    "Please enter an Anthropic API key.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
        }

        openaiTestButton.addActionListener {
            val apiKey = openaiApiKeyField.text
            if (apiKey.isNotBlank()) {
                CoroutineScope(Dispatchers.Main).launch {
                    openaiTestButton.isEnabled = false
                    openaiTestButton.text = "Testing..."

                    val success = llmCoordinator.verifyOpenAiApiKey(apiKey)

                    if (success) {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Successfully verified OpenAI API key!",
                            "API Key Verification",
                            JOptionPane.INFORMATION_MESSAGE,
                        )

                        // Add OpenAI models to combo boxes
                        val openaiModels =
                            listOf(
                                "gpt-4o",
                                "gpt-4-turbo",
                                "gpt-4",
                                "gpt-3.5-turbo",
                            )

                        // Clear existing OpenAI models from combo boxes
                        updateModelComboBoxes(removeProvider = ModelProvider.OPENAI)

                        // Add OpenAI models to combo boxes
                        openaiModels.forEach { model ->
                            simpleModelComboBox.addItem(ModelOption(model, ModelProvider.OPENAI, ModelType.SIMPLE))
                            coderModelComboBox.addItem(ModelOption(model, ModelProvider.OPENAI, ModelType.COMPLEX))
                            finalizerModelComboBox.addItem(
                                ModelOption(
                                    model,
                                    ModelProvider.OPENAI,
                                    ModelType.FINALIZER,
                                ),
                            )
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Failed to verify OpenAI API key. Please check it and try again.",
                            "API Key Verification Failed",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }

                    openaiTestButton.isEnabled = true
                    openaiTestButton.text = "Test"
                }
            } else {
                JOptionPane.showMessageDialog(
                    this@SettingsWindow,
                    "Please enter an OpenAI API key.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
        }

        // Create tabbed pane
        val tabbedPane = JTabbedPane()

        // Create API Settings panel
        val apiSettingsPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val g = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 1.0
        }

        // Anthropic Key + Test
        val anthropicRow = JPanel(BorderLayout(8, 0)).apply {
            add(anthropicApiKeyField.apply { compact() }, BorderLayout.CENTER)
            add(anthropicTestButton, BorderLayout.EAST)
        }
        makeRow("Anthropic API Key*:", anthropicRow, g, apiSettingsPanel)

        // OpenAI Key + Test  
        val openaiRow = JPanel(BorderLayout(8, 0)).apply {
            add(openaiApiKeyField.apply { compact() }, BorderLayout.CENTER)
            add(openaiTestButton, BorderLayout.EAST)
        }
        makeRow("OpenAI API Key*:", openaiRow, g, apiSettingsPanel)

        // Rate limits (3 rows)
        makeRow("Input Rate (tokens/min):", anthropicRateLimitInputTokensField.apply { compact(140) }, g, apiSettingsPanel)
        makeRow("Output Rate (tokens/min):", anthropicRateLimitOutputTokensField.apply { compact(140) }, g, apiSettingsPanel)
        makeRow("Rate Window (sec):", anthropicRateLimitWindowField.apply { compact(140) }, g, apiSettingsPanel)

        // Use Ollama
        makeRow("Use Ollama:", useOllamaCheckbox, g, apiSettingsPanel)
        // Ollama URL row - show/hide based on checkbox
        val ollamaTestButton = JButton("Test")
        ollamaTestButton.addActionListener {
            val url = ollamaUrlField.text
            if (url.isNotBlank()) {
                CoroutineScope(Dispatchers.Main).launch {
                    ollamaTestButton.isEnabled = false
                    ollamaTestButton.text = "Testing..."

                    val success = ollamaService.testConnection(url)

                    if (success) {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Successfully connected to Ollama server!",
                            "Connection Test",
                            JOptionPane.INFORMATION_MESSAGE,
                        )

                        // Populate model dropdowns with Ollama models
                        val models = ollamaService.getAvailableModels(url)

                        // Clear existing Ollama models from combo boxes
                        updateModelComboBoxes(removeProvider = ModelProvider.OLLAMA)

                        // Add new Ollama models to combo boxes
                        models.forEach { model ->
                            simpleModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.SIMPLE))
                            coderModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.COMPLEX))
                            finalizerModelComboBox.addItem(
                                ModelOption(
                                    model.name,
                                    ModelProvider.OLLAMA,
                                    ModelType.FINALIZER,
                                ),
                            )
                            embeddingModelComboBox.addItem(
                                ModelOption(
                                    model.name,
                                    ModelProvider.OLLAMA,
                                    ModelType.EMBEDDING,
                                ),
                            )
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Failed to connect to Ollama server. Please check the URL and ensure the server is running.",
                            "Connection Test",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }

                    ollamaTestButton.isEnabled = true
                    ollamaTestButton.text = "Test"
                }
            } else {
                JOptionPane.showMessageDialog(
                    this@SettingsWindow,
                    "Please enter a valid Ollama server URL.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
        }
        val ollamaUrlRow = JPanel(BorderLayout(8, 0)).apply {
            add(ollamaUrlField.apply { compact() }, BorderLayout.CENTER)
            add(ollamaTestButton, BorderLayout.EAST)
        }
        fun showOllamaUrlRow(show: Boolean) {
            if (show && ollamaUrlRow.parent == null) { 
                makeRow("Ollama Server URL:", ollamaUrlRow, g, apiSettingsPanel)
                apiSettingsPanel.revalidate()
            }
            if (!show && ollamaUrlRow.parent != null) { 
                apiSettingsPanel.remove(ollamaUrlRow.parent)
                apiSettingsPanel.revalidate() 
                apiSettingsPanel.repaint()
            }
        }
        useOllamaCheckbox.addActionListener { showOllamaUrlRow(useOllamaCheckbox.isSelected) }
        showOllamaUrlRow(useOllamaCheckbox.isSelected)

        // Use LM Studio
        makeRow("Use LM Studio:", useLmStudioCheckbox, g, apiSettingsPanel)
        val lmStudioTestButton = JButton("Test")
        lmStudioTestButton.addActionListener {
            val url = lmStudioUrlField.text
            if (url.isNotBlank()) {
                CoroutineScope(Dispatchers.Main).launch {
                    lmStudioTestButton.isEnabled = false
                    lmStudioTestButton.text = "Testing..."

                    val success = lmStudioService.testConnection(url)

                    if (success) {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Successfully connected to LM Studio server!",
                            "Connection Test",
                            JOptionPane.INFORMATION_MESSAGE,
                        )

                        // Populate model dropdowns with LM Studio models
                        val models = lmStudioService.getAvailableModels(url)

                        // Clear existing LM Studio models from combo boxes
                        updateModelComboBoxes(removeProvider = ModelProvider.LM_STUDIO)

                        // Add new LM Studio models to combo boxes
                        models.forEach { model ->
                            simpleModelComboBox.addItem(
                                ModelOption(
                                    model.id,
                                    ModelProvider.LM_STUDIO,
                                    ModelType.SIMPLE,
                                ),
                            )
                            coderModelComboBox.addItem(
                                ModelOption(
                                    model.id,
                                    ModelProvider.LM_STUDIO,
                                    ModelType.COMPLEX,
                                ),
                            )
                            finalizerModelComboBox.addItem(
                                ModelOption(
                                    model.id,
                                    ModelProvider.LM_STUDIO,
                                    ModelType.FINALIZER,
                                ),
                            )
                            embeddingModelComboBox.addItem(
                                ModelOption(
                                    model.id,
                                    ModelProvider.LM_STUDIO,
                                    ModelType.EMBEDDING,
                                ),
                            )
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                            this@SettingsWindow,
                            "Failed to connect to LM Studio server. Please check the URL and ensure the server is running.",
                            "Connection Test",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }

                    lmStudioTestButton.isEnabled = true
                    lmStudioTestButton.text = "Test"
                }
            } else {
                JOptionPane.showMessageDialog(
                    this@SettingsWindow,
                    "Please enter a valid LM Studio server URL.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
        }
        val lmUrlRow = JPanel(BorderLayout(8, 0)).apply {
            add(lmStudioUrlField.apply { compact() }, BorderLayout.CENTER)
            add(lmStudioTestButton, BorderLayout.EAST)
        }
        fun showLmUrlRow(show: Boolean) {
            if (show && lmUrlRow.parent == null) { 
                makeRow("LM Studio URL:", lmUrlRow, g, apiSettingsPanel)
                apiSettingsPanel.revalidate()
            }
            if (!show && lmUrlRow.parent != null) { 
                apiSettingsPanel.remove(lmUrlRow.parent)
                apiSettingsPanel.revalidate() 
                apiSettingsPanel.repaint()
            }
        }
        useLmStudioCheckbox.addActionListener { showLmUrlRow(useLmStudioCheckbox.isSelected) }
        showLmUrlRow(useLmStudioCheckbox.isSelected)

        // Create Models panel - only model assignments, no external servers
        val modelsPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val mg = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 1.0
        }
        fun addAssign(label: String, combo: JComboBox<ModelOption>) {
            combo.preferredSize = Dimension(560, ROW_H)
            makeRow(label, combo, mg, modelsPanel)
        }
        addAssign("Simple Model:", simpleModelComboBox)
        addAssign("Coder Model:", coderModelComboBox)
        addAssign("Finalizer Model:", finalizerModelComboBox)
        addAssign("Embedding Model:", embeddingModelComboBox)

        // Add panels to tabbed pane
        tabbedPane.addTab("API Settings", apiSettingsPanel)
        tabbedPane.addTab("Models", modelsPanel)

        // Add tabbed pane directly to frame - no scroll pane needed
        add(tabbedPane, BorderLayout.CENTER)

        // Create button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))

        val saveButton = JButton("Save")
        saveButton.addActionListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (saveSettings()) {
                    JOptionPane.showMessageDialog(this@SettingsWindow, "Settings saved.")
                    // Do not close the window - just save and show message
                }
            }
        }

        val okButton = JButton("OK")
        okButton.addActionListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (saveSettings()) {
                    isVisible = false // Close the dialog after saving
                }
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

        // Configure window sizing and properties
        preferredSize = Dimension(820, 520)
        minimumSize = Dimension(820, 520) 
        isResizable = false
        
        pack()
        setLocationRelativeTo(null)

        // Add ESC key handling - ESC acts as Cancel
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    isVisible = false // Close without saving
                }
            }
        })
        
        // Make sure the window can receive key events
        isFocusable = true

        // Load current settings
        CoroutineScope(Dispatchers.Main).launch {
            loadSettings()
        }
    }

    /**
     * Update model combo boxes by removing models from a specific provider
     */
    private fun updateModelComboBoxes(removeProvider: ModelProvider) {
        // Remove models from the specified provider
        val simpleModels = mutableListOf<ModelOption>()
        val coderModels = mutableListOf<ModelOption>()
        val finalizerModels = mutableListOf<ModelOption>()

        // Collect models that should be kept
        for (i in 0 until simpleModelComboBox.itemCount) {
            val item = simpleModelComboBox.getItemAt(i)
            if (item.provider != removeProvider) {
                simpleModels.add(item)
            }
        }

        for (i in 0 until coderModelComboBox.itemCount) {
            val item = coderModelComboBox.getItemAt(i)
            if (item.provider != removeProvider) {
                coderModels.add(item)
            }
        }

        for (i in 0 until finalizerModelComboBox.itemCount) {
            val item = finalizerModelComboBox.getItemAt(i)
            if (item.provider != removeProvider) {
                finalizerModels.add(item)
            }
        }

        // Clear and repopulate combo boxes
        simpleModelComboBox.removeAllItems()
        coderModelComboBox.removeAllItems()
        finalizerModelComboBox.removeAllItems()

        simpleModels.forEach { simpleModelComboBox.addItem(it) }
        coderModels.forEach { coderModelComboBox.addItem(it) }
        finalizerModels.forEach { finalizerModelComboBox.addItem(it) }
    }

    private suspend fun loadSettings() {
        // Load external model settings
        useOllamaCheckbox.isSelected = settingService.ollamaEnabled
        ollamaUrlField.text = settingService.ollamaUrl

        useLmStudioCheckbox.isSelected = settingService.lmStudioEnabled
        lmStudioUrlField.text = settingService.lmStudioUrl

        // Get saved model settings
        val (_, simpleModelName) = settingService.getModelSimple()
        val (_, complexModelName) = settingService.getModelComplex()
        val (_, finalizingModelName) = settingService.getModelFinalizing()
        val (_, embeddingModelName) = settingService.getEmbeddingModel()

        // Clear combo boxes
        simpleModelComboBox.removeAllItems()
        coderModelComboBox.removeAllItems()
        finalizerModelComboBox.removeAllItems()
        embeddingModelComboBox.removeAllItems()

        // Dynamically load models from providers
        loadModelsFromProviders()

        // Load Anthropic settings
        anthropicApiKeyField.text = settingService.anthropicApiKey
        anthropicRateLimitInputTokensField.text =
            settingService.anthropicRateLimitInputTokens.toString()
        anthropicRateLimitOutputTokensField.text =
            settingService.anthropicRateLimitOutputTokens.toString()
        anthropicRateLimitWindowField.text =
            settingService.anthropicRateLimitWindowSeconds.toString()

        // Load OpenAI settings
        openaiApiKeyField.text = settingService.openaiApiKey

        // Select the saved models in the combo boxes
        selectSavedModel(simpleModelComboBox, simpleModelName)
        selectSavedModel(coderModelComboBox, complexModelName)
        selectSavedModel(finalizerModelComboBox, finalizingModelName)
        selectSavedModel(embeddingModelComboBox, embeddingModelName)
    }

    /**
     * Loads models from all available providers
     */
    private fun loadModelsFromProviders() {
        // Load OpenAI models if API key is configured
        val openaiApiKey = settingService.openaiApiKey
        if (openaiApiKey.isNotBlank() && openaiApiKey != "none") {
            val openaiModels =
                listOf(
                    "gpt-4o",
                    "gpt-4-turbo",
                    "gpt-4",
                    "gpt-3.5-turbo",
                )

            openaiModels.forEach { model ->
                simpleModelComboBox.addItem(ModelOption(model, ModelProvider.OPENAI, ModelType.SIMPLE))
                coderModelComboBox.addItem(ModelOption(model, ModelProvider.OPENAI, ModelType.COMPLEX))
                finalizerModelComboBox.addItem(ModelOption(model, ModelProvider.OPENAI, ModelType.FINALIZER))
            }

            // Add OpenAI embedding model
            embeddingModelComboBox.addItem(
                ModelOption(
                    "text-embedding-3-large",
                    ModelProvider.OPENAI,
                    ModelType.EMBEDDING,
                ),
            )
        }

        // Load Anthropic models if API key is configured
        val anthropicApiKey = settingService.anthropicApiKey
        if (anthropicApiKey.isNotBlank() && anthropicApiKey != "none") {
            val anthropicModels =
                listOf(
                    "claude-3-opus-20240229",
                    "claude-3-sonnet-20240229",
                    "claude-3-haiku-20240307",
                )

            anthropicModels.forEach { model ->
                simpleModelComboBox.addItem(ModelOption(model, ModelProvider.ANTHROPIC, ModelType.SIMPLE))
                coderModelComboBox.addItem(ModelOption(model, ModelProvider.ANTHROPIC, ModelType.COMPLEX))
                finalizerModelComboBox.addItem(ModelOption(model, ModelProvider.ANTHROPIC, ModelType.FINALIZER))
            }
        }

        // Add internal embedding model
        embeddingModelComboBox.addItem(
            ModelOption(
                "intfloat/multilingual-e5-large",
                ModelProvider.DJL,
                ModelType.EMBEDDING,
            ),
        )

        // Load Ollama models if enabled
        if (settingService.ollamaEnabled) {
            val ollamaUrl = settingService.ollamaUrl
            try {
                // Get Ollama models
                val ollamaModels = ollamaService.getAvailableModels(ollamaUrl)

                // Add Ollama models to combo boxes
                ollamaModels.forEach { model ->
                    simpleModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.SIMPLE))
                    coderModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.COMPLEX))
                    finalizerModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.FINALIZER))
                }

                // Get Ollama embedding models
                val ollamaEmbeddingModels = ollamaService.getEmbeddingModels(ollamaUrl)

                // Add Ollama embedding models to combo box
                ollamaEmbeddingModels.forEach { model ->
                    embeddingModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.EMBEDDING))
                }

                // If no embedding models found, add all models as options
                if (ollamaEmbeddingModels.isEmpty() && ollamaModels.isNotEmpty()) {
                    ollamaModels.forEach { model ->
                        embeddingModelComboBox.addItem(
                            ModelOption(
                                model.name,
                                ModelProvider.OLLAMA,
                                ModelType.EMBEDDING,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                // Log error but continue
                logger.error(e) { "Failed to load Ollama models: ${e.message}" }
            }
        }

        // Load LM Studio models if enabled
        if (settingService.lmStudioEnabled) {
            val lmStudioUrl = settingService.lmStudioUrl
            try {
                // Get LM Studio models
                val lmStudioModels = lmStudioService.getAvailableModels(lmStudioUrl)

                // Add LM Studio models to combo boxes
                lmStudioModels.forEach { model ->
                    simpleModelComboBox.addItem(ModelOption(model.id, ModelProvider.LM_STUDIO, ModelType.SIMPLE))
                    coderModelComboBox.addItem(ModelOption(model.id, ModelProvider.LM_STUDIO, ModelType.COMPLEX))
                    finalizerModelComboBox.addItem(ModelOption(model.id, ModelProvider.LM_STUDIO, ModelType.FINALIZER))
                }

                // Get LM Studio embedding models
                val lmStudioEmbeddingModels = lmStudioService.getEmbeddingModels(lmStudioUrl)

                // Add LM Studio embedding models to combo box
                lmStudioEmbeddingModels.forEach { model ->
                    embeddingModelComboBox.addItem(ModelOption(model.id, ModelProvider.LM_STUDIO, ModelType.EMBEDDING))
                }

                // If no embedding models found, add all models as options
                if (lmStudioEmbeddingModels.isEmpty() && lmStudioModels.isNotEmpty()) {
                    lmStudioModels.forEach { model ->
                        embeddingModelComboBox.addItem(
                            ModelOption(
                                model.id,
                                ModelProvider.LM_STUDIO,
                                ModelType.EMBEDDING,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                // Log error but continue
                logger.error(e) { "Failed to load LM Studio models: ${e.message}" }
            }
        }
    }

    /**
     * Selects a model in a combo box by name
     */
    private fun selectSavedModel(
        comboBox: JComboBox<ModelOption>,
        modelName: String,
    ) {
        if (modelName.isNotEmpty()) {
            // Find the model in the combo box
            for (i in 0 until comboBox.itemCount) {
                val item = comboBox.getItemAt(i)
                if (item.name == modelName) {
                    comboBox.selectedIndex = i
                    return
                }
            }

            // If model not found in combo box, add it
            // This can happen if a provider is disabled but was previously used
            // We still want to show the selected model
            val modelOptions = mutableListOf<ModelOption>()
            for (i in 0 until comboBox.itemCount) {
                modelOptions.add(comboBox.getItemAt(i))
            }

            // Determine model type based on combo box
            val modelType =
                when (comboBox) {
                    simpleModelComboBox -> ModelType.SIMPLE
                    coderModelComboBox -> ModelType.COMPLEX
                    finalizerModelComboBox -> ModelType.FINALIZER
                    embeddingModelComboBox -> ModelType.EMBEDDING
                    else -> ModelType.SIMPLE
                }

            // Add the model to the combo box with "(missing)" suffix
            // Determine the provider based on the model name
            val provider =
                when {
                    // OpenAI models start with "gpt-"
                    modelName.startsWith("gpt-") -> ModelProvider.OPENAI
                    // Anthropic models start with "claude-"
                    modelName.startsWith("claude-") -> ModelProvider.ANTHROPIC
                    // Default to OPENAI for unknown models
                    else -> ModelProvider.OPENAI
                }
            val missingModelOption = ModelOption("$modelName (missing)", provider, modelType)
            comboBox.addItem(missingModelOption)
            
            // Add tooltip to indicate missing model
            comboBox.toolTipText = "Model not found on server; please re-select."

            // Select the newly added item
            comboBox.selectedIndex = comboBox.itemCount - 1
        }
    }

    private suspend fun saveSettings(): Boolean {
        // Start batch mode to collect all setting changes and publish single consolidated event
        settingService.startBatch(SettingsChangeEvent.ChangeType.GENERAL)
        
        try {
            // Validate conditional URLs
            if (useOllamaCheckbox.isSelected) {
                val ollamaUrl = ollamaUrlField.text.trim()
                if (ollamaUrl.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Ollama Server URL is required when Ollama is enabled.",
                        "Validation Error",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return false
                }
                if (!ollamaUrl.startsWith("http://") && !ollamaUrl.startsWith("https://")) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Ollama Server URL must include protocol (http:// or https://).",
                        "Validation Error",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return false
                }
            }
            
            if (useLmStudioCheckbox.isSelected) {
                val lmStudioUrl = lmStudioUrlField.text.trim()
                if (lmStudioUrl.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "LM Studio URL is required when LM Studio is enabled.",
                        "Validation Error",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return false
                }
                if (!lmStudioUrl.startsWith("http://") && !lmStudioUrl.startsWith("https://")) {
                    JOptionPane.showMessageDialog(
                        this,
                        "LM Studio URL must include protocol (http:// or https://).",
                        "Validation Error",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return false
                }
            }

        // Save external model settings
        settingService.ollamaEnabled = useOllamaCheckbox.isSelected
        settingService.ollamaUrl = ollamaUrlField.text

        settingService.lmStudioEnabled = useLmStudioCheckbox.isSelected
        settingService.lmStudioUrl = lmStudioUrlField.text

        // Save model selections from combo boxes
        val selectedSimpleModel = simpleModelComboBox.selectedItem as? ModelOption
        val selectedCoderModel = coderModelComboBox.selectedItem as? ModelOption
        val selectedFinalizerModel = finalizerModelComboBox.selectedItem as? ModelOption
        val selectedEmbeddingModel = embeddingModelComboBox.selectedItem as? ModelOption

        // Validate required model selections
        if (selectedSimpleModel == null) {
            JOptionPane.showMessageDialog(
                this,
                "Simple Model selection is required.",
                "Model Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return false
        }

        if (selectedCoderModel == null) {
            JOptionPane.showMessageDialog(
                this,
                "Coder Model selection is required.",
                "Model Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return false
        }

        if (selectedEmbeddingModel == null) {
            JOptionPane.showMessageDialog(
                this,
                "Embedding Model selection is required.",
                "Model Validation",
                JOptionPane.WARNING_MESSAGE,
            )
            return false
        }

        // Save simple model
        if (selectedSimpleModel != null) {
            val modelProvider = selectedSimpleModel.provider
            logger.debug { "Saving simple model: ${selectedSimpleModel.name} ($modelProvider)" }
            settingService.setModelSimple(modelProvider, selectedSimpleModel.name)
        } else {
            logger.warn { "No simple model selected" }
        }

        // Save coder model
        if (selectedCoderModel != null) {
            val modelProvider = selectedCoderModel.provider
            logger.debug { "Saving coder model: ${selectedCoderModel.name} ($modelProvider)" }
            settingService.setModelComplex(modelProvider, selectedCoderModel.name)
        } else {
            logger.warn { "No coder model selected" }
        }

        // Save finalizer model
        if (selectedFinalizerModel != null) {
            val modelProvider = selectedFinalizerModel.provider
            logger.debug { "Saving finalizer model: ${selectedFinalizerModel.name} ($modelProvider)" }
            settingService.setModelFinalizing(modelProvider, selectedFinalizerModel.name)
        } else {
            logger.warn { "No finalizer model selected" }
        }

        // Save embedding model
        if (selectedEmbeddingModel != null) {
            // Map the selected model to the appropriate EmbeddingModelType
            val embeddingModelProvider = selectedEmbeddingModel.provider
            logger.debug { "Saving embedding model: ${selectedEmbeddingModel.name} ($embeddingModelProvider)" }
            settingService.setEmbeddingModel(embeddingModelProvider, selectedEmbeddingModel.name)
        } else {
            logger.warn { "No embedding model selected" }
        }

        // Check if Anthropic API key has changed
        val currentAnthropicApiKey = settingService.anthropicApiKey
        val newAnthropicApiKey = anthropicApiKeyField.text
        // Verify the new API key
        if (currentAnthropicApiKey != newAnthropicApiKey &&
            newAnthropicApiKey.isNotBlank() &&
            !llmCoordinator.verifyAnthropicApiKey(newAnthropicApiKey)
        ) {
            JOptionPane.showMessageDialog(
                this,
                "The Anthropic API key could not be verified. Please check it and try again.",
                "API Key Verification Failed",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        // Save Anthropic API key
        settingService.anthropicApiKey = newAnthropicApiKey

        // Check if OpenAI API key has changed
        val currentOpenAiApiKey = settingService.openaiApiKey
        val newOpenAiApiKey = openaiApiKeyField.text
        // Verify the new API key
        if (currentOpenAiApiKey != newOpenAiApiKey &&
            newOpenAiApiKey.isNotBlank() &&
            !llmCoordinator.verifyOpenAiApiKey(newOpenAiApiKey)
        ) {
            JOptionPane.showMessageDialog(
                this,
                "The OpenAI API key could not be verified. Please check it and try again.",
                "API Key Verification Failed",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        // Save OpenAI API key
        settingService.openaiApiKey = newOpenAiApiKey

        // Save rate limiting settings
        try {
            val inputRateLimitTokens = anthropicRateLimitInputTokensField.text.toInt()
            settingService.anthropicRateLimitInputTokens = inputRateLimitTokens
        } catch (_: NumberFormatException) {
            JOptionPane.showMessageDialog(
                this,
                "Input rate limit must be a valid number",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        try {
            val outputRateLimitTokens = anthropicRateLimitOutputTokensField.text.toInt()
            settingService.anthropicRateLimitOutputTokens = outputRateLimitTokens
        } catch (_: NumberFormatException) {
            JOptionPane.showMessageDialog(
                this,
                "Output rate limit must be a valid number",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        try {
            val rateLimitWindow = anthropicRateLimitWindowField.text.toInt()
            settingService.anthropicRateLimitWindowSeconds = rateLimitWindow
        } catch (_: NumberFormatException) {
            JOptionPane.showMessageDialog(
                this,
                "Rate limit window must be a valid number",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

            logger.info { "Settings saved successfully" }
            return true
        } finally {
            // End batch mode and publish single consolidated event
            settingService.endBatch()
        }
    }
}
