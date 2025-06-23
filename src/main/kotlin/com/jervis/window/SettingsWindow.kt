package com.jervis.window

import com.jervis.entity.EmbeddingModelType
import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.service.LMStudioService
import com.jervis.service.OllamaService
import com.jervis.service.SettingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants

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
 * Enum representing model providers
 */
enum class ModelProvider {
    OLLAMA,
    LM_STUDIO,
    OPEAN_AI,
    ANTHROPIC,
}

/**
 * Enum representing model types
 */
enum class ModelType {
    SIMPLE,
    CODER,
    FINALIZER,
    EMBEDDING,
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
                    ModelProvider.OPEAN_AI -> "OpenAI"
                    ModelProvider.ANTHROPIC -> "Anthropic"
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
    // External model settings
    private val useOllamaCheckbox = JCheckBox("Use Ollama", true)
    private val ollamaUrlField =
        JTextField().apply {
            preferredSize = Dimension(preferredSize.width, 30) // Increased height
        }

    private val useLmStudioCheckbox = JCheckBox("Use LM Studio", true)
    private val lmStudioUrlField =
        JTextField().apply {
            preferredSize = Dimension(preferredSize.width, 30) // Increased height
        }

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

    // Embedding model combo box removed from External Models tab and moved to Embedding tab

    // Anthropic settings
    private val anthropicApiKeyField =
        JTextField().apply {
            preferredSize = Dimension(600, 30) // Wider field for API keys (600px as required)
            toolTipText = "Enter your Anthropic API key here"
        }
    private val anthropicTestButton =
        JButton("Test").apply {
            toolTipText = "Test if the Anthropic API key is valid"
        }
    private val anthropicRateLimitInputTokensField = JTextField()
    private val anthropicRateLimitOutputTokensField = JTextField()
    private val anthropicRateLimitWindowField = JTextField()

    // OpenAI settings
    private val openaiApiKeyField =
        JTextField().apply {
            preferredSize = Dimension(600, 30) // Wider field for API keys (600px as required)
            toolTipText = "Enter your OpenAI API key here"
        }
    private val openaiTestButton =
        JButton("Test").apply {
            toolTipText = "Test if the OpenAI API key is valid"
        }

    // Embedding settings
    private val useLocalEmbeddingCheckbox = JCheckBox("Use Local Embedding", true)
    private val useExternalEmbeddingCheckbox = JCheckBox("Use External Embedding", false)

    // External embedding panel
    private val externalEmbeddingPanel = JPanel(BorderLayout(5, 5))

    private val freeEmbeddingRadioButton = JRadioButton("Free (Hugging Face)")
    private val openaiEmbeddingRadioButton = JRadioButton("OpenAI text-embedding-3-large")
    private val embeddingButtonGroup =
        ButtonGroup().apply {
            add(freeEmbeddingRadioButton)
            add(openaiEmbeddingRadioButton)
        }

    // Embedding model type dropdown
    private val embeddingModelTypeComboBox = JComboBox<String>()

    // LM Studio and Ollama model combo boxes
    private val lmStudioModelComboBox = JComboBox<ModelOption>()
    private val ollamaModelComboBox = JComboBox<ModelOption>()

    // Combo box for selecting multilingual embedding models
    private val huggingFaceModelComboBox =
        JComboBox<String>().apply {
            isEditable = false
            addItem("intfloat/multilingual-e5-large (primary, dim: 1024)")
            addItem("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2 (low-resource only, dim: 384)")
        }

    init {
        size = Dimension(700, 550) // Increased height for the new embedding tab
        setLocationRelativeTo(null)
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

        // Create Anthropic settings panel
        val anthropicPanel = JPanel(GridLayout(4, 2, 10, 10))
        anthropicPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        anthropicPanel.add(JLabel("Anthropic API Key:"))

        // Add API key field with test button
        val anthropicApiKeyPanel = JPanel(BorderLayout(5, 0))
        anthropicApiKeyPanel.add(anthropicApiKeyField, BorderLayout.CENTER)
        anthropicApiKeyPanel.add(anthropicTestButton, BorderLayout.EAST)
        anthropicPanel.add(anthropicApiKeyPanel)
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

        // Add API key field with test button
        val openaiApiKeyPanel = JPanel(BorderLayout(5, 0))
        openaiApiKeyPanel.add(openaiApiKeyField, BorderLayout.CENTER)
        openaiApiKeyPanel.add(openaiTestButton, BorderLayout.EAST)
        openaiPanel.add(openaiApiKeyPanel)

        // Create Embedding settings panel
        val embeddingPanel = JPanel(BorderLayout(10, 10))
        embeddingPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Title and description
        val titleLabel = JLabel("Embedding Model Selection", SwingConstants.CENTER)
        titleLabel.font = Font(titleLabel.font.name, Font.BOLD, 14)
        embeddingPanel.add(titleLabel, BorderLayout.NORTH)

        // Main content panel
        val embeddingContentPanel = JPanel(BorderLayout(10, 10))

        // Create a panel for the embedding model dropdown
        val embeddingModelPanel = JPanel(BorderLayout(5, 5))
        embeddingModelPanel.border = BorderFactory.createTitledBorder("Embedding Model")

        // Create a dropdown for embedding model selection
        val embeddingModelTypeComboBox = JComboBox<String>()
        embeddingModelTypeComboBox.addItem("Internal (Hugging Face)")
        embeddingModelTypeComboBox.addItem("OpenAI (text-embedding-3-large)")

        // Add LM Studio and Ollama options if enabled
        if (settingService.isLmStudioEnabled()) {
            embeddingModelTypeComboBox.addItem("LM Studio")
        }

        if (settingService.isOllamaEnabled()) {
            embeddingModelTypeComboBox.addItem("Ollama")
        }

        // Add the dropdown to the panel
        val embeddingModelTypePanel = JPanel(GridLayout(1, 2, 5, 5))
        embeddingModelTypePanel.add(JLabel("Select Embedding Model:"))
        embeddingModelTypePanel.add(embeddingModelTypeComboBox)
        embeddingModelPanel.add(embeddingModelTypePanel, BorderLayout.NORTH)

        // Panel for model-specific options
        val modelOptionsPanel = JPanel(CardLayout())

        // Internal model options panel
        val internalModelPanel = JPanel(BorderLayout(5, 5))
        internalModelPanel.border = BorderFactory.createTitledBorder("Internal Model Options")

        // Add Hugging Face model combo box
        val huggingFacePanel = JPanel(GridLayout(1, 2, 5, 5))
        huggingFacePanel.add(JLabel("Select Hugging Face model:"))
        huggingFacePanel.add(huggingFaceModelComboBox)
        internalModelPanel.add(huggingFacePanel, BorderLayout.NORTH)

        // OpenAI model options panel
        val openaiModelPanel = JPanel(BorderLayout(5, 5))
        openaiModelPanel.border = BorderFactory.createTitledBorder("OpenAI Model Options")

        // Add warning about OpenAI API key
        val openaiWarning = JLabel("Requires OpenAI API key and involves fees")
        openaiWarning.foreground = Color.RED
        openaiWarning.font = Font(openaiWarning.font.name, Font.ITALIC, 12)
        openaiModelPanel.add(openaiWarning, BorderLayout.NORTH)

        // LM Studio model options panel
        val lmStudioModelPanel = JPanel(BorderLayout(5, 5))
        lmStudioModelPanel.border = BorderFactory.createTitledBorder("LM Studio Model Options")

        // Add LM Studio model combo box
        val lmStudioModelComboBox = JComboBox<ModelOption>()
        val lmStudioModelPanel2 = JPanel(GridLayout(1, 2, 5, 5))
        lmStudioModelPanel2.add(JLabel("Select LM Studio model:"))
        lmStudioModelPanel2.add(lmStudioModelComboBox)
        lmStudioModelPanel.add(lmStudioModelPanel2, BorderLayout.NORTH)

        // Ollama model options panel
        val ollamaModelPanel = JPanel(BorderLayout(5, 5))
        ollamaModelPanel.border = BorderFactory.createTitledBorder("Ollama Model Options")

        // Add Ollama model combo box
        val ollamaModelComboBox = JComboBox<ModelOption>()
        val ollamaModelPanel2 = JPanel(GridLayout(1, 2, 5, 5))
        ollamaModelPanel2.add(JLabel("Select Ollama model:"))
        ollamaModelPanel2.add(ollamaModelComboBox)
        ollamaModelPanel.add(ollamaModelPanel2, BorderLayout.NORTH)

        // Add all model option panels to the card layout
        modelOptionsPanel.add(internalModelPanel, "Internal")
        modelOptionsPanel.add(openaiModelPanel, "OpenAI")
        modelOptionsPanel.add(lmStudioModelPanel, "LMStudio")
        modelOptionsPanel.add(ollamaModelPanel, "Ollama")

        // Add listener to show the appropriate panel based on the selected model type
        embeddingModelTypeComboBox.addActionListener {
            val selectedModel = embeddingModelTypeComboBox.selectedItem as String
            val cardLayout = modelOptionsPanel.layout as CardLayout
            when {
                selectedModel.startsWith("Internal") -> cardLayout.show(modelOptionsPanel, "Internal")
                selectedModel.startsWith("OpenAI") -> cardLayout.show(modelOptionsPanel, "OpenAI")
                selectedModel.startsWith("LM Studio") -> cardLayout.show(modelOptionsPanel, "LMStudio")
                selectedModel.startsWith("Ollama") -> cardLayout.show(modelOptionsPanel, "Ollama")
            }
        }

        // Add the model options panel to the embedding model panel
        embeddingModelPanel.add(modelOptionsPanel, BorderLayout.CENTER)

        // Add the embedding model panel to the content panel
        embeddingContentPanel.add(embeddingModelPanel, BorderLayout.CENTER)

        // Description text
        val descriptionText =
            JTextArea(
                "Embedding models convert text into vector representations for semantic search.\n" +
                    "The free option uses local models, while the other options require API keys and involve fees.\n" +
                    "WARNING: Changing the embedding model will invalidate the vector store and require reindexing.\n" +
                    "Changes will take effect after application restart.",
            )
        descriptionText.isEditable = false
        descriptionText.lineWrap = true
        descriptionText.wrapStyleWord = true
        descriptionText.background = embeddingPanel.background
        descriptionText.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        embeddingContentPanel.add(descriptionText, BorderLayout.SOUTH)

        // Add the content panel to the main panel
        embeddingPanel.add(embeddingContentPanel, BorderLayout.CENTER)

        // Create External Models settings panel
        val externalModelsPanel = JPanel(BorderLayout(10, 10))
        externalModelsPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Title
        val externalModelsTitleLabel = JLabel("External Model Settings", SwingConstants.CENTER)
        externalModelsTitleLabel.font = Font(externalModelsTitleLabel.font.name, Font.BOLD, 14)
        externalModelsPanel.add(externalModelsTitleLabel, BorderLayout.NORTH)

        // Main content panel with GridLayout - adjusted for new layout
        val externalModelsContentPanel = JPanel(GridLayout(10, 2, 10, 10))
        externalModelsContentPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Ollama settings
        externalModelsContentPanel.add(useOllamaCheckbox)
        externalModelsContentPanel.add(JLabel("")) // Empty cell for grid layout

        externalModelsContentPanel.add(JLabel("Ollama Server URL:"))
        val ollamaUrlPanel = JPanel(BorderLayout(5, 0))
        ollamaUrlPanel.add(ollamaUrlField, BorderLayout.CENTER)
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
                            coderModelComboBox.addItem(ModelOption(model.name, ModelProvider.OLLAMA, ModelType.CODER))
                        }

                        // Populate embedding model dropdown for the Embedding tab
                        val embeddingModels = ollamaService.getEmbeddingModels(url)

                        // Clear and populate the Ollama model combo box in the Embedding tab
                        ollamaModelComboBox.removeAllItems()

                        // Add embedding models
                        embeddingModels.forEach { model ->
                            ollamaModelComboBox.addItem(
                                ModelOption(
                                    model.name,
                                    ModelProvider.OLLAMA,
                                    ModelType.EMBEDDING,
                                ),
                            )
                        }

                        // If no embedding models found, add all models as options
                        if (embeddingModels.isEmpty()) {
                            models.forEach { model ->
                                ollamaModelComboBox.addItem(
                                    ModelOption(
                                        model.name,
                                        ModelProvider.OLLAMA,
                                        ModelType.EMBEDDING,
                                    ),
                                )
                            }
                        }

                        // Show a message if no models were found
                        if (models.isEmpty()) {
                            JOptionPane.showMessageDialog(
                                this@SettingsWindow,
                                "No models were found on the Ollama server.",
                                "No Models Found",
                                JOptionPane.WARNING_MESSAGE,
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
        ollamaUrlPanel.add(ollamaTestButton, BorderLayout.EAST)
        externalModelsContentPanel.add(ollamaUrlPanel)

        // Spacer
        externalModelsContentPanel.add(JLabel(""))
        externalModelsContentPanel.add(JLabel(""))

        // LM Studio settings
        externalModelsContentPanel.add(useLmStudioCheckbox)
        externalModelsContentPanel.add(JLabel("")) // Empty cell for grid layout

        externalModelsContentPanel.add(JLabel("LM Studio URL:"))
        val lmStudioUrlPanel = JPanel(BorderLayout(5, 0))
        lmStudioUrlPanel.add(lmStudioUrlField, BorderLayout.CENTER)
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
                            coderModelComboBox.addItem(ModelOption(model.id, ModelProvider.LM_STUDIO, ModelType.CODER))
                        }

                        // Populate embedding model dropdown for the Embedding tab
                        val embeddingModels = lmStudioService.getEmbeddingModels(url)

                        // Clear and populate the LM Studio model combo box in the Embedding tab
                        lmStudioModelComboBox.removeAllItems()

                        // Add embedding models
                        embeddingModels.forEach { model ->
                            lmStudioModelComboBox.addItem(
                                ModelOption(
                                    model.id,
                                    ModelProvider.LM_STUDIO,
                                    ModelType.EMBEDDING,
                                ),
                            )
                        }

                        // If no embedding models found, add all models as options
                        if (embeddingModels.isEmpty()) {
                            models.forEach { model ->
                                lmStudioModelComboBox.addItem(
                                    ModelOption(
                                        model.id,
                                        ModelProvider.LM_STUDIO,
                                        ModelType.EMBEDDING,
                                    ),
                                )
                            }
                        }

                        // Show a message if no models were found
                        if (models.isEmpty()) {
                            JOptionPane.showMessageDialog(
                                this@SettingsWindow,
                                "No models were found on the LM Studio server.",
                                "No Models Found",
                                JOptionPane.WARNING_MESSAGE,
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
        lmStudioUrlPanel.add(lmStudioTestButton, BorderLayout.EAST)
        externalModelsContentPanel.add(lmStudioUrlPanel)

        // Spacer
        externalModelsContentPanel.add(JLabel(""))
        externalModelsContentPanel.add(JLabel(""))

        // Combined model selection
        externalModelsContentPanel.add(JLabel("Simple Model (for quick edits):"))
        externalModelsContentPanel.add(simpleModelComboBox)

        externalModelsContentPanel.add(JLabel("Coder Model (for programming):"))
        externalModelsContentPanel.add(coderModelComboBox)

        externalModelsPanel.add(externalModelsContentPanel, BorderLayout.CENTER)

        // Description
        val externalModelsDescription =
            JTextArea(
                "Configure external model providers for different types of tasks.\n" +
                    "Simple models are used for quick text edits and chunking.\n" +
                    "Coder models are used for more sophisticated tasks like programming.",
            )
        externalModelsDescription.isEditable = false
        externalModelsDescription.lineWrap = true
        externalModelsDescription.wrapStyleWord = true
        externalModelsDescription.background = externalModelsPanel.background
        externalModelsDescription.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        externalModelsPanel.add(externalModelsDescription, BorderLayout.SOUTH)

        // Add panels to tabbed pane
        tabbedPane.addTab("Anthropic", anthropicPanel)
        tabbedPane.addTab("OpenAI", openaiPanel)
        tabbedPane.addTab("Embedding", embeddingPanel)
        tabbedPane.addTab("External Models", externalModelsPanel)

        // Add tabbed pane to frame
        add(tabbedPane, BorderLayout.CENTER)

        // Create button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))

        val saveButton = JButton("Save")
        saveButton.addActionListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (saveSettings()) {
                    JOptionPane.showMessageDialog(this@SettingsWindow, "Settings saved.")
                    isVisible = false // Close the dialog after saving
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

        // Clear and repopulate combo boxes
        simpleModelComboBox.removeAllItems()
        coderModelComboBox.removeAllItems()

        simpleModels.forEach { simpleModelComboBox.addItem(it) }
        coderModels.forEach { coderModelComboBox.addItem(it) }
    }

    private suspend fun loadSettings() {
        // Load external model settings
        useOllamaCheckbox.isSelected = settingService.isOllamaEnabled()
        ollamaUrlField.text = settingService.getOllamaUrl()

        useLmStudioCheckbox.isSelected = settingService.isLmStudioEnabled()
        lmStudioUrlField.text =
            settingService.getLmStudioUrl()

        val (_, simpleModelName) = settingService.getModelSimple()
        val (_, complexModelName) = settingService.getModelComplex()
        val (_, _) = settingService.getModelFinalizing()
        val (_, _) = settingService.getEmbeddingModel()

        // Clear combo boxes
        simpleModelComboBox.removeAllItems()
        coderModelComboBox.removeAllItems()

        // Add default Ollama models
        if (useOllamaCheckbox.isSelected) {
            simpleModelComboBox.addItem(
                ModelOption(
                    simpleModelName,
                    ModelProvider.OLLAMA,
                    ModelType.SIMPLE,
                ),
            )
            coderModelComboBox.addItem(
                ModelOption(
                    complexModelName,
                    ModelProvider.OLLAMA,
                    ModelType.CODER,
                ),
            )

            // Add Ollama embedding model to the Ollama model combo box
            ollamaModelComboBox.removeAllItems()
            ollamaModelComboBox.addItem(
                ModelOption(
                    settingService.getEmbeddingModelName(),
                    ModelProvider.OLLAMA,
                    ModelType.EMBEDDING,
                ),
            )
        }

        // Load Anthropic settings
        anthropicApiKeyField.text = settingService.getAnthropicApiKey()
        anthropicRateLimitInputTokensField.text =
            settingService.getAnthropicRateLimitInputTokens().toString()
        anthropicRateLimitOutputTokensField.text =
            settingService.getAnthropicRateLimitOutputTokens().toString()
        anthropicRateLimitWindowField.text =
            settingService.getAnthropicRateLimitWindowSeconds().toString()

        // Load OpenAI settings
        openaiApiKeyField.text = settingService.getOpenaiApiKey()

        // Load embedding settings
        settingService.getEmbeddingModelTypeEnum()

        // Initialize the embedding model type dropdown
        embeddingModelTypeComboBox.removeAllItems()
        embeddingModelTypeComboBox.addItem("Internal (Hugging Face)")
        embeddingModelTypeComboBox.addItem("OpenAI (text-embedding-3-large)")

        // Add LM Studio and Ollama options if enabled
        if (settingService.isLmStudioEnabled()) {
            embeddingModelTypeComboBox.addItem("LM Studio")
        }

        if (settingService.isOllamaEnabled()) {
            embeddingModelTypeComboBox.addItem("Ollama")
        }

        // Trigger the action listener to show the appropriate panel
        embeddingModelTypeComboBox.actionListeners.forEach { it.actionPerformed(null) }
    }

    private suspend fun saveSettings(): Boolean {
        // Save external model settings
        settingService.setOllamaEnabled(useOllamaCheckbox.isSelected)
        settingService.setOllamaUrl(ollamaUrlField.text)

        settingService.setLmStuiodEnabled(useLmStudioCheckbox.isSelected)
        settingService.setLmStudioUrl(lmStudioUrlField.text)

        // Save model selections from combo boxes
        val selectedSimpleModel = simpleModelComboBox.selectedItem as? ModelOption
        val selectedCoderModel = coderModelComboBox.selectedItem as? ModelOption

        // Check if models are selected before accessing their properties
        if (selectedSimpleModel != null) {
            settingService.setModelSimpleName(selectedSimpleModel.name)
        }

        if (selectedCoderModel != null) {
            settingService.setModelComplexName(selectedCoderModel.name)
        }

        // Check if Anthropic API key has changed
        val currentAnthropicApiKey = settingService.getAnthropicApiKey()
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
        settingService.setAnthropicApiKey(newAnthropicApiKey)

        // Check if OpenAI API key has changed
        val currentOpenAiApiKey = settingService.getOpenaiApiKey()
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
        settingService.setOpenAiApiKey(newOpenAiApiKey)

        // Save embedding settings
        val selectedEmbeddingModel = embeddingModelTypeComboBox.selectedItem as? String

        // Map the selected string to the appropriate EmbeddingModelType
        val embeddingModelType = when (selectedEmbeddingModel) {
            "Internal (Hugging Face)" -> EmbeddingModelType.INTERNAL
            "OpenAI (text-embedding-3-large)" -> EmbeddingModelType.OPENAI
            "LM Studio" -> EmbeddingModelType.LM_STUDIO
            "Ollama" -> EmbeddingModelType.OLLAMA
            else -> EmbeddingModelType.INTERNAL // Default to INTERNAL if no match or null
        }

        // Save the embedding model type using the direct method
        settingService.saveEmbeddingModelTypeEnum(embeddingModelType)

        // Save rate limiting settings
        try {
            val inputRateLimitTokens = anthropicRateLimitInputTokensField.text.toInt()
            settingService.setAnthropicRateLimitInputTokens(inputRateLimitTokens)
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
            settingService.setAnthropicRateLimitOutputTokens(outputRateLimitTokens)
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
            settingService.setAnthropicRateLimitWindowSeconds(rateLimitWindow)
        } catch (_: NumberFormatException) {
            JOptionPane.showMessageDialog(
                this,
                "Rate limit window must be a valid number",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }

        return true
    }
}
