package com.jervis.ui.window

import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.ModelParams
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.CreatePromptRequest
import com.jervis.entity.mongo.PromptDocument
import com.jervis.entity.mongo.PromptMetadata
import com.jervis.entity.mongo.PromptStatus
import com.jervis.entity.mongo.UpdatePromptRequest
import com.jervis.service.admin.PromptManagementService
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.prompts.PromptTemplateService
import com.jervis.ui.component.ApplicationWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder

class PromptManagementWindow(
    private val promptManagementService: PromptManagementService,
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
    private val promptTemplateService: PromptTemplateService,
    private val applicationWindowManager: ApplicationWindowManager? = null,
) : JFrame("Prompt Management") {
    private val logger = KotlinLogging.logger {}
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // UI Components
    private val toolTypeCombo = JComboBox(McpToolType.values())
    private val modelTypeCombo = JComboBox(arrayOf<ModelType?>(null, *ModelType.values()))
    private val statusCombo = JComboBox(PromptStatus.values())
    private val promptsList = JList<PromptDocument>()
    private val promptsListModel = DefaultListModel<PromptDocument>()

    // Content editors
    private val systemPromptArea = JTextArea(20, 60)
    private val userPromptArea = JTextArea(10, 60)
    private val descriptionField = JTextField(50)

    // Model parameters
    private val creativityCombo = JComboBox(CreativityLevel.values())
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.1))
    private val topPSpinner = JSpinner(SpinnerNumberModel(0.9, 0.0, 1.0, 0.1))
    private val presencePenaltySpinner = JSpinner(SpinnerNumberModel(0.0, -2.0, 2.0, 0.1))
    private val frequencyPenaltySpinner = JSpinner(SpinnerNumberModel(0.0, -2.0, 2.0, 0.1))
    private val repeatPenaltySpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 2.0, 0.1))
    private val systemPromptWeightSpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 5.0, 0.1))
    private val jsonModeCheckbox = JCheckBox("JSON Mode")

    // Metadata fields
    private val authorField = JTextField(30)
    private val tagsField = JTextField(50)
    private val notesArea = JTextArea(5, 50)
    private val prioritySpinner = JSpinner(SpinnerNumberModel(0, -100, 100, 1))

    // Testing UI components
    private val testModelTypeCombo = JComboBox(ModelType.values())
    private val testOutputLanguageCombo =
        JComboBox(arrayOf("english", "czech", "german", "french", "spanish", "italian"))
    private val testQuickModeCheckbox = JCheckBox("Quick Mode", false)
    private val testParametersPanel = JPanel()
    private val testUserRequestArea = JTextArea(8, 50)
    private val testPreviewArea = JTextArea(10, 50)
    private val testStatusLabel = JLabel("Ready")
    private val testProgressBar = JProgressBar()
    private val parameterFields = mutableMapOf<String, JTextField>()
    private var currentTestJob: Job? = null

    // Current selection
    private var currentPrompt: PromptDocument? = null
    private var isUpdating = false

    init {
        initializeUI()
        loadPrompts()
        setupEventHandlers()
        setupKeyboardShortcuts()
    }

    private fun initializeUI() {
        defaultCloseOperation = HIDE_ON_CLOSE
        setSize(1400, 900)
        setLocationRelativeTo(null)

        layout = BorderLayout()

        // Create main split pane
        val leftPanel = createLeftPanel()
        val rightPanel = createRightPanel()

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel)
        splitPane.dividerLocation = 350
        splitPane.resizeWeight = 0.25

        add(splitPane, BorderLayout.CENTER)
        add(createToolbar(), BorderLayout.NORTH)
        add(createStatusBar(), BorderLayout.SOUTH)
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))

        val saveButton = JButton("Save")
        val deleteButton = JButton("Delete")
        val cloneButton = JButton("Clone for Model")
        val refreshButton = JButton("Refresh")

        saveButton.addActionListener { saveCurrentPrompt() }
        deleteButton.addActionListener { deleteCurrentPrompt() }
        cloneButton.addActionListener { clonePromptForModel() }
        refreshButton.addActionListener { loadPrompts() }

        toolbar.add(saveButton)
        toolbar.add(deleteButton)
        toolbar.add(JSeparator(SwingConstants.VERTICAL))
        toolbar.add(cloneButton)
        toolbar.add(JSeparator(SwingConstants.VERTICAL))
        toolbar.add(refreshButton)

        return toolbar
    }

    private fun createStatusBar(): JPanel {
        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT))
        statusBar.border = EmptyBorder(5, 10, 5, 10)
        statusBar.add(JLabel("Ready"))
        return statusBar
    }

    private fun createLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // Filters
        val filterPanel = createFilterPanel()
        panel.add(filterPanel, BorderLayout.NORTH)

        // Prompts list
        promptsList.model = promptsListModel
        promptsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        promptsList.cellRenderer = PromptListCellRenderer()

        val scrollPane = JScrollPane(promptsList)
        scrollPane.preferredSize = Dimension(330, 600)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun createFilterPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = TitledBorder("Filters")

        val grid = JPanel(GridLayout(3, 2, 5, 5))
        grid.border = EmptyBorder(10, 10, 10, 10)

        grid.add(JLabel("Tool Type:"))
        toolTypeCombo.insertItemAt(null, 0) // Add "All" option
        toolTypeCombo.selectedIndex = 0
        grid.add(toolTypeCombo)

        grid.add(JLabel("Model Type:"))
        modelTypeCombo.renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = value?.toString() ?: "All Models"
                    return this
                }
            }
        grid.add(modelTypeCombo)

        grid.add(JLabel("Status:"))
        statusCombo.insertItemAt(null, 0)
        statusCombo.selectedIndex = 1 // Default to ACTIVE
        grid.add(statusCombo)

        panel.add(grid)

        return panel
    }

    private fun createRightPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val tabbedPane = JTabbedPane()

        // Content tab
        tabbedPane.addTab("Content", createContentPanel())

        // Model Parameters tab
        tabbedPane.addTab("Model Parameters", createModelParamsPanel())

        // Metadata tab
        tabbedPane.addTab("Metadata", createMetadataPanel())

        // Test Prompt tab
        tabbedPane.addTab("Test Prompt", createTestPanel())

        panel.add(tabbedPane, BorderLayout.CENTER)
        panel.add(createActionButtons(), BorderLayout.SOUTH)

        return panel
    }

    private fun createContentPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val contentTabbedPane = JTabbedPane()

        // System prompt tab
        systemPromptArea.lineWrap = true
        systemPromptArea.wrapStyleWord = true
        systemPromptArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val systemScrollPane = JScrollPane(systemPromptArea)
        systemScrollPane.preferredSize = Dimension(800, 300)
        contentTabbedPane.addTab("System Prompt", systemScrollPane)

        // User prompt tab
        userPromptArea.lineWrap = true
        userPromptArea.wrapStyleWord = true
        userPromptArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val userScrollPane = JScrollPane(userPromptArea)
        userScrollPane.preferredSize = Dimension(800, 200)
        contentTabbedPane.addTab("User Prompt", userScrollPane)

        panel.add(contentTabbedPane, BorderLayout.CENTER)

        // Description at top
        val descPanel = JPanel(BorderLayout())
        descPanel.border = TitledBorder("Description")
        descPanel.add(descriptionField, BorderLayout.CENTER)
        panel.add(descPanel, BorderLayout.NORTH)

        return panel
    }

    private fun createModelParamsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val grid = JPanel(GridLayout(8, 2, 10, 5))
        grid.border = EmptyBorder(20, 20, 20, 20)

        grid.add(JLabel("Creativity Level:"))
        grid.add(creativityCombo)

        grid.add(JLabel("Presence Penalty:"))
        grid.add(presencePenaltySpinner)

        grid.add(JLabel("Frequency Penalty:"))
        grid.add(frequencyPenaltySpinner)

        grid.add(JLabel("Repeat Penalty:"))
        grid.add(repeatPenaltySpinner)

        grid.add(JLabel("System Prompt Weight:"))
        grid.add(systemPromptWeightSpinner)

        grid.add(JLabel("JSON Mode:"))
        grid.add(jsonModeCheckbox)

        grid.add(JLabel("Priority:"))
        grid.add(prioritySpinner)

        panel.add(grid)
        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun createMetadataPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val grid = JPanel(GridLayout(3, 2, 10, 5))
        grid.border = EmptyBorder(20, 20, 20, 20)

        grid.add(JLabel("Author:"))
        grid.add(authorField)

        grid.add(JLabel("Tags (comma-separated):"))
        grid.add(tagsField)

        panel.add(grid, BorderLayout.NORTH)

        val notesPanel = JPanel(BorderLayout())
        notesPanel.border = TitledBorder("Notes")
        notesArea.lineWrap = true
        notesArea.wrapStyleWord = true
        notesPanel.add(JScrollPane(notesArea), BorderLayout.CENTER)
        panel.add(notesPanel, BorderLayout.CENTER)

        return panel
    }

    private fun createTestPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // Configuration panel at top
        val configPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        configPanel.border = TitledBorder("Test Configuration")

        configPanel.add(JLabel("Model:"))
        testModelTypeCombo.selectedItem = ModelType.INTERNAL
        configPanel.add(testModelTypeCombo)

        configPanel.add(Box.createHorizontalStrut(20))

        configPanel.add(JLabel("Language:"))
        testOutputLanguageCombo.selectedItem = "english"
        configPanel.add(testOutputLanguageCombo)

        configPanel.add(Box.createHorizontalStrut(20))
        configPanel.add(testQuickModeCheckbox)

        val testButton = JButton("Test Current Prompt")
        testButton.addActionListener { testCurrentPrompt() }
        configPanel.add(testButton)

        val cancelButton = JButton("Cancel")
        cancelButton.addActionListener { cancelCurrentTest() }
        cancelButton.isEnabled = false
        configPanel.add(cancelButton)

        panel.add(configPanel, BorderLayout.NORTH)

        // Split pane for parameters and preview
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

        // Left side - Parameters and User Request
        val leftSection = JPanel(BorderLayout())

        // Template parameters section
        val parametersSection = JPanel(BorderLayout())
        parametersSection.border = TitledBorder("Template Parameters")

        testParametersPanel.layout = BoxLayout(testParametersPanel, BoxLayout.Y_AXIS)
        val parametersScroll = JScrollPane(testParametersPanel)
        parametersScroll.preferredSize = Dimension(300, 150)
        parametersSection.add(parametersScroll, BorderLayout.CENTER)

        leftSection.add(parametersSection, BorderLayout.NORTH)

        // User request section
        val userRequestSection = JPanel(BorderLayout())
        userRequestSection.border = TitledBorder("User Request")

        testUserRequestArea.lineWrap = true
        testUserRequestArea.wrapStyleWord = true
        testUserRequestArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        testUserRequestArea.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()
            },
        )

        val userRequestScroll = JScrollPane(testUserRequestArea)
        userRequestScroll.preferredSize = Dimension(300, 120)
        userRequestSection.add(userRequestScroll, BorderLayout.CENTER)

        leftSection.add(userRequestSection, BorderLayout.CENTER)

        splitPane.leftComponent = leftSection

        // Right side - Preview
        val previewSection = JPanel(BorderLayout())
        previewSection.border = TitledBorder("Composed Prompt Preview")

        testPreviewArea.isEditable = false
        testPreviewArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        testPreviewArea.lineWrap = true
        testPreviewArea.wrapStyleWord = true
        testPreviewArea.background = Color(250, 250, 250)

        val previewScroll = JScrollPane(testPreviewArea)
        previewSection.add(previewScroll, BorderLayout.CENTER)

        splitPane.rightComponent = previewSection
        splitPane.resizeWeight = 0.4

        panel.add(splitPane, BorderLayout.CENTER)

        // Status panel at bottom
        val statusPanel = JPanel(BorderLayout())

        testProgressBar.isStringPainted = true
        testProgressBar.string = ""
        testProgressBar.isVisible = false
        statusPanel.add(testProgressBar, BorderLayout.NORTH)

        testStatusLabel.border = EmptyBorder(5, 10, 5, 10)
        statusPanel.add(testStatusLabel, BorderLayout.SOUTH)

        panel.add(statusPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createActionButtons(): JPanel {
        val panel = JPanel(FlowLayout())

        val exportButton = JButton("Export")
        val importButton = JButton("Import")

        exportButton.addActionListener { exportPrompts() }
        importButton.addActionListener { importPrompts() }

        panel.add(exportButton)
        panel.add(importButton)

        return panel
    }

    private fun setupEventHandlers() {
        // Filter change handlers
        toolTypeCombo.addActionListener { if (!isUpdating) filterPrompts() }
        modelTypeCombo.addActionListener { if (!isUpdating) filterPrompts() }
        statusCombo.addActionListener { if (!isUpdating) filterPrompts() }

        // Selection handler
        promptsList.addListSelectionListener {
            if (!it.valueIsAdjusting && !isUpdating) {
                loadSelectedPrompt()
            }
        }
    }

    private fun setupKeyboardShortcuts() {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = rootPane.actionMap

        // Ctrl+S - Save
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save")
        actionMap.put(
            "save",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    saveCurrentPrompt()
                }
            },
        )

        // F5 - Refresh
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh")
        actionMap.put(
            "refresh",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    loadPrompts()
                }
            },
        )

        // Delete - Delete current
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete")
        actionMap.put(
            "delete",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    deleteCurrentPrompt()
                }
            },
        )
    }

    // Implementation methods will be added in next part due to length
    private fun loadPrompts() {
        coroutineScope.launch {
            try {
                val prompts = promptManagementService.getAllPrompts()
                withContext(Dispatchers.Swing) {
                    updatePromptsList(prompts)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load prompts" }
                withContext(Dispatchers.Swing) {
                    showErrorDialog("Failed to load prompts: ${e.message}")
                }
            }
        }
    }

    private fun updatePromptsList(prompts: List<PromptDocument>) {
        isUpdating = true
        promptsListModel.clear()
        val filteredPrompts = filterPromptsByCurrentSelection(prompts)
        filteredPrompts.forEach { promptsListModel.addElement(it) }
        isUpdating = false
    }

    private fun filterPromptsByCurrentSelection(prompts: List<PromptDocument>): List<PromptDocument> {
        val selectedToolType = toolTypeCombo.selectedItem as? McpToolType
        val selectedModelType = modelTypeCombo.selectedItem as? ModelType?
        val selectedStatus = statusCombo.selectedItem as? PromptStatus

        return prompts.filter { prompt ->
            (selectedToolType == null || prompt.toolType == selectedToolType) &&
                (selectedModelType == null || prompt.modelType == selectedModelType) &&
                (selectedStatus == null || prompt.status == selectedStatus)
        }
    }

    private fun filterPrompts() {
        loadPrompts() // Reload and filter
    }

    private fun loadSelectedPrompt() {
        val selected = promptsList.selectedValue
        if (selected != null) {
            currentPrompt = selected
            populateFields(selected)
        }
    }

    private fun populateFields(prompt: PromptDocument) {
        isUpdating = true

        // Content fields
        systemPromptArea.text = prompt.systemPrompt ?: ""
        userPromptArea.text = prompt.userPrompt ?: ""
        descriptionField.text = prompt.description ?: ""

        // Model parameters
        creativityCombo.selectedItem = prompt.modelParams.creativityLevel
        presencePenaltySpinner.value = prompt.modelParams.presencePenalty
        frequencyPenaltySpinner.value = prompt.modelParams.frequencyPenalty
        repeatPenaltySpinner.value = prompt.modelParams.repeatPenalty
        systemPromptWeightSpinner.value = prompt.modelParams.systemPromptWeight
        jsonModeCheckbox.isSelected = prompt.modelParams.jsonMode
        prioritySpinner.value = prompt.priority

        // Metadata
        authorField.text = prompt.metadata.author ?: ""
        tagsField.text = prompt.metadata.tags.joinToString(", ")
        notesArea.text = prompt.metadata.notes ?: ""

        // Update test panel parameter fields
        updateTestParameterFields(prompt)

        isUpdating = false
    }

    private fun createNewPrompt() {
        currentPrompt = null
        clearFields()
        toolTypeCombo.selectedIndex = if (toolTypeCombo.itemCount > 0) 1 else 0 // Skip "All" option
        modelTypeCombo.selectedIndex = 0
    }

    private fun clearFields() {
        isUpdating = true
        systemPromptArea.text = ""
        userPromptArea.text = ""
        descriptionField.text = ""
        creativityCombo.selectedItem = CreativityLevel.MEDIUM
        presencePenaltySpinner.value = 0.0
        frequencyPenaltySpinner.value = 0.0
        repeatPenaltySpinner.value = 1.0
        systemPromptWeightSpinner.value = 1.0
        jsonModeCheckbox.isSelected = false
        prioritySpinner.value = 0
        authorField.text = ""
        tagsField.text = ""
        notesArea.text = ""
        isUpdating = false
    }

    private fun saveCurrentPrompt() {
        coroutineScope.launch {
            try {
                val selectedToolType =
                    if (toolTypeCombo.selectedIndex > 0) {
                        toolTypeCombo.selectedItem as McpToolType
                    } else {
                        withContext(Dispatchers.Swing) {
                            showErrorDialog("Please select a tool type")
                        }
                        return@launch
                    }

                if (currentPrompt == null) {
                    // Create new prompt
                    val request =
                        CreatePromptRequest(
                            toolType = selectedToolType,
                            modelType = modelTypeCombo.selectedItem as? ModelType,
                            systemPrompt = systemPromptArea.text.takeIf { it.isNotBlank() },
                            userPrompt = userPromptArea.text.takeIf { it.isNotBlank() },
                            description = descriptionField.text.takeIf { it.isNotBlank() },
                            modelParams = createModelParamsFromUI(),
                            metadata = createMetadataFromUI(),
                            priority = prioritySpinner.value as Int,
                        )

                    promptManagementService.createPrompt(request, "prompt-management-ui")
                    withContext(Dispatchers.Swing) {
                        showInfoDialog("Prompt created successfully")
                        loadPrompts()
                    }
                } else {
                    // Update existing prompt
                    val request =
                        UpdatePromptRequest(
                            systemPrompt = systemPromptArea.text.takeIf { it.isNotBlank() },
                            userPrompt = userPromptArea.text.takeIf { it.isNotBlank() },
                            description = descriptionField.text.takeIf { it.isNotBlank() },
                            modelParams = createModelParamsFromUI(),
                            metadata = createMetadataFromUI(),
                            priority = prioritySpinner.value as Int,
                        )

                    promptManagementService.updatePrompt(currentPrompt!!.id.toString(), request, "prompt-management-ui")
                    withContext(Dispatchers.Swing) {
                        showInfoDialog("Prompt updated successfully")
                        loadPrompts()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save prompt" }
                withContext(Dispatchers.Swing) {
                    showErrorDialog("Failed to save prompt: ${e.message}")
                }
            }
        }
    }

    private fun createModelParamsFromUI(): ModelParams =
        ModelParams(
            creativityLevel = creativityCombo.selectedItem as CreativityLevel,
            presencePenalty = presencePenaltySpinner.value as Double,
            frequencyPenalty = frequencyPenaltySpinner.value as Double,
            repeatPenalty = repeatPenaltySpinner.value as Double,
            systemPromptWeight = systemPromptWeightSpinner.value as Double,
            jsonMode = jsonModeCheckbox.isSelected,
        )

    private fun createMetadataFromUI(): PromptMetadata {
        val tags =
            tagsField.text
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return PromptMetadata(
            tags = tags,
            author = authorField.text.takeIf { it.isNotBlank() },
            notes = notesArea.text.takeIf { it.isNotBlank() },
        )
    }

    private fun deleteCurrentPrompt() {
        val prompt = currentPrompt ?: return

        val result =
            JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete the prompt for ${prompt.toolType}?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )

        if (result == JOptionPane.YES_OPTION) {
            coroutineScope.launch {
                try {
                    val success = promptManagementService.deletePrompt(prompt.id.toString(), "prompt-management-ui")
                    withContext(Dispatchers.Swing) {
                        if (success) {
                            showInfoDialog("Prompt deleted successfully")
                            currentPrompt = null
                            clearFields()
                            loadPrompts()
                        } else {
                            showErrorDialog("Failed to delete prompt")
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete prompt" }
                    withContext(Dispatchers.Swing) {
                        showErrorDialog("Failed to delete prompt: ${e.message}")
                    }
                }
            }
        }
    }

    private fun clonePromptForModel() {
        val prompt = currentPrompt ?: return

        val targetModel =
            JOptionPane.showInputDialog(
                this,
                "Select target model type:",
                "Clone Prompt",
                JOptionPane.QUESTION_MESSAGE,
                null,
                ModelType.values(),
                ModelType.INTERNAL,
            ) as? ModelType ?: return

        coroutineScope.launch {
            try {
                promptManagementService.clonePromptForModel(
                    prompt.id.toString(),
                    targetModel,
                    "prompt-management-ui",
                )
                withContext(Dispatchers.Swing) {
                    showInfoDialog("Prompt cloned successfully for model type: $targetModel")
                    loadPrompts()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to clone prompt" }
                withContext(Dispatchers.Swing) {
                    showErrorDialog("Failed to clone prompt: ${e.message}")
                }
            }
        }
    }

    private fun testCurrentPrompt() {
        val prompt = currentPrompt ?: return

        currentTestJob =
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.Swing) {
                        testStatusLabel.text = "Preparing test..."
                        testProgressBar.isVisible = true
                        testProgressBar.isIndeterminate = true
                    }

                    if (!isActive) return@launch

                    // Extract parameters from current prompt
                    val systemPrompt = prompt.systemPrompt ?: ""
                    val userPrompt = prompt.userPrompt ?: ""
                    val userRequest = testUserRequestArea.text.trim()

                    // Get parameter values from UI
                    val parameters = parameterFields.mapValues { (_, field) -> field.text }

                    // Compose final prompts
                    val finalSystemPrompt =
                        if (systemPrompt.isNotEmpty()) {
                            promptTemplateService.composePrompt(systemPrompt, parameters)
                        } else {
                            ""
                        }

                    val composedUserPrompt =
                        if (userPrompt.isNotEmpty()) {
                            promptTemplateService.composePrompt(userPrompt, parameters)
                        } else {
                            ""
                        }

                    // Combine userPrompt template with user request
                    val finalUserPrompt =
                        buildString {
                            if (composedUserPrompt.isNotEmpty()) {
                                append(composedUserPrompt)
                                if (userRequest.isNotEmpty()) {
                                    append("\n\n")
                                }
                            }
                            if (userRequest.isNotEmpty()) {
                                append(userRequest)
                            }
                        }.takeIf { it.isNotEmpty() } ?: ""

                    withContext(Dispatchers.Swing) {
                        testProgressBar.string = "Calling LLM..."
                        testStatusLabel.text = "Calling LLM..."
                    }

                    if (!isActive) return@launch

                    // Get configuration
                    val selectedModelType = testModelTypeCombo.selectedItem as ModelType
                    val selectedLanguage = testOutputLanguageCombo.selectedItem as String
                    val isQuickMode = testQuickModeCheckbox.isSelected

                    // Call LLM
                    val llmResponse =
                        llmGateway.callLlm(
                            type = selectedModelType,
                            userPrompt = finalUserPrompt,
                            systemPrompt = finalSystemPrompt,
                            outputLanguage = selectedLanguage,
                            quick = isQuickMode,
                        )

                    if (!isActive) return@launch

                    withContext(Dispatchers.Swing) {
                        testProgressBar.isVisible = false
                        testStatusLabel.text = "LLM response received"

                        // Show result in dialog
                        showLlmResult(finalSystemPrompt, finalUserPrompt, llmResponse)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to test prompt" }
                    withContext(Dispatchers.Swing) {
                        testProgressBar.isVisible = false
                        testStatusLabel.text = "Error: ${e.message}"
                        showErrorDialog("Failed to test prompt: ${e.message}")
                    }
                } finally {
                    currentTestJob = null
                }
            }
    }

    private fun cancelCurrentTest() {
        currentTestJob?.let { job ->
            job.cancel()
            currentTestJob = null
            testProgressBar.isVisible = false
            testStatusLabel.text = "Test cancelled"
        }
    }

    private fun showLlmResult(
        systemPrompt: String,
        userPrompt: String,
        response: LlmResponse,
    ) {
        val dialog = JDialog(this, "LLM Test Results", true)
        dialog.layout = BorderLayout()

        val tabbedPane = JTabbedPane()

        // Composed prompts tab
        val composedText =
            buildString {
                if (systemPrompt.isNotEmpty()) {
                    appendLine("=== SYSTEM PROMPT ===")
                    appendLine(systemPrompt)
                    appendLine()
                }
                if (userPrompt.isNotEmpty()) {
                    appendLine("=== USER PROMPT ===")
                    appendLine(userPrompt)
                }
            }

        val composedArea = JTextArea(composedText)
        composedArea.isEditable = false
        composedArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        composedArea.lineWrap = true
        composedArea.wrapStyleWord = true
        tabbedPane.addTab("Composed Prompt", JScrollPane(composedArea))

        // LLM response tab
        val responseArea = JTextArea(response.answer)
        responseArea.isEditable = false
        responseArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        responseArea.lineWrap = true
        responseArea.wrapStyleWord = true
        tabbedPane.addTab("LLM Response", JScrollPane(responseArea))

        dialog.add(tabbedPane, BorderLayout.CENTER)

        val closeButton = JButton("Close")
        closeButton.addActionListener { dialog.dispose() }
        val buttonPanel = JPanel(FlowLayout())
        buttonPanel.add(closeButton)
        dialog.add(buttonPanel, BorderLayout.SOUTH)

        dialog.preferredSize = Dimension(800, 600)
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun updateTestParameterFields(prompt: PromptDocument) {
        coroutineScope.launch {
            try {
                val systemPrompt = prompt.systemPrompt ?: ""
                val userPrompt = prompt.userPrompt ?: ""
                val combinedTemplate = "$systemPrompt\n$userPrompt"
                val variables = promptTemplateService.extractTemplateVariables(combinedTemplate)

                withContext(Dispatchers.Swing) {
                    // Clear existing parameter fields
                    testParametersPanel.removeAll()
                    parameterFields.clear()

                    if (variables.isEmpty()) {
                        val noParamsLabel = JLabel("No template variables found in this prompt")
                        noParamsLabel.foreground = Color.GRAY
                        testParametersPanel.add(noParamsLabel)
                    } else {
                        variables.forEach { variableName ->
                            val fieldPanel = JPanel(FlowLayout(FlowLayout.LEFT))
                            val label = JLabel("$variableName:")
                            label.preferredSize = Dimension(120, 25)

                            val textField = JTextField(20)
                            textField.document.addDocumentListener(
                                object : javax.swing.event.DocumentListener {
                                    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()

                                    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()

                                    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateTestPreview()
                                },
                            )

                            fieldPanel.add(label)
                            fieldPanel.add(textField)

                            testParametersPanel.add(fieldPanel)
                            parameterFields[variableName] = textField
                        }
                    }

                    testParametersPanel.revalidate()
                    testParametersPanel.repaint()
                    updateTestPreview()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to update test parameter fields" }
            }
        }
    }

    private fun updateTestPreview() {
        val prompt = currentPrompt ?: return

        coroutineScope.launch {
            try {
                val systemPrompt = prompt.systemPrompt ?: ""
                val userPrompt = prompt.userPrompt ?: ""
                val parameters = parameterFields.mapValues { (_, field) -> field.text }

                val systemPreview =
                    if (systemPrompt.isNotEmpty()) {
                        promptTemplateService.createPromptPreview(systemPrompt, parameters)
                    } else {
                        ""
                    }

                val userPreview =
                    if (userPrompt.isNotEmpty()) {
                        promptTemplateService.createPromptPreview(userPrompt, parameters)
                    } else {
                        ""
                    }

                // Get user request from UI
                val userRequest =
                    withContext(Dispatchers.Swing) {
                        testUserRequestArea.text.trim()
                    }

                val fullPreview =
                    buildString {
                        if (systemPreview.isNotEmpty()) {
                            appendLine("=== SYSTEM PROMPT ===")
                            appendLine(systemPreview)
                            appendLine()
                        }
                        if (userPreview.isNotEmpty() || userRequest.isNotEmpty()) {
                            appendLine("=== FINAL USER PROMPT ===")
                            if (userPreview.isNotEmpty()) {
                                appendLine(userPreview)
                                if (userRequest.isNotEmpty()) {
                                    appendLine()
                                    appendLine("--- User Request ---")
                                }
                            }
                            if (userRequest.isNotEmpty()) {
                                append(userRequest)
                            }
                        }
                    }

                withContext(Dispatchers.Swing) {
                    testPreviewArea.text = fullPreview
                    testPreviewArea.caretPosition = 0
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to update test preview" }
                withContext(Dispatchers.Swing) {
                    testPreviewArea.text = "Error generating preview: ${e.message}"
                }
            }
        }
    }

    private fun exportPrompts() {
        // Placeholder for export functionality
        showInfoDialog("Export functionality would be implemented here")
    }

    private fun importPrompts() {
        // Placeholder for import functionality
        showInfoDialog("Import functionality would be implemented here")
    }

    private fun showErrorDialog(message: String) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
    }

    private fun showInfoDialog(message: String) {
        JOptionPane.showMessageDialog(this, message, "Information", JOptionPane.INFORMATION_MESSAGE)
    }
}

// Custom list cell renderer for prompts
class PromptListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        if (value is PromptDocument) {
            val modelSuffix = value.modelType?.let { " (${it.name})" } ?: " (All Models)"
            val versionSuffix = " v${value.version}"
            text = "${value.toolType.name}$modelSuffix$versionSuffix"

            // Different colors for different statuses
            when (value.status) {
                PromptStatus.ACTIVE -> foreground = if (isSelected) Color.WHITE else Color.BLACK
                PromptStatus.DRAFT -> foreground = if (isSelected) Color.CYAN else Color.BLUE
                PromptStatus.DEPRECATED -> foreground = if (isSelected) Color.LIGHT_GRAY else Color.GRAY
                PromptStatus.ARCHIVED -> foreground = if (isSelected) Color.LIGHT_GRAY else Color.LIGHT_GRAY
            }

            // Add priority indicator
            if (value.priority != 0) {
                text += " [P:${value.priority}]"
            }
        }

        return this
    }
}
