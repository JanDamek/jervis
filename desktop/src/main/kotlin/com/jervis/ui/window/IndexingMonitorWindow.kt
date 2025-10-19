package com.jervis.ui.window

import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingProgressEventDto
import com.jervis.service.indexing.monitoring.IndexingStepDto
import com.jervis.service.indexing.monitoring.IndexingStepStatusEnum
import com.jervis.service.indexing.monitoring.ProjectIndexingStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import java.awt.Component as AwtComponent

/**
 * Dialog window for monitoring indexing progress
 */
class IndexingMonitorWindow(
    private val indexingMonitorService: IIndexingMonitorService,
    parentWindow: JFrame? = null,
) : JDialog(parentWindow, "Indexing Monitor", false) {
    private val logger = KotlinLogging.logger {}
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI Components
    private val projectTree = JTree()
    private val logArea = JTextArea()
    private val refreshTimer: Timer
    private val statusLabel = JLabel("Ready")

    // Tree model and nodes
    private val rootNode = DefaultMutableTreeNode("Indexing Projects")
    private val treeModel = DefaultTreeModel(rootNode)
    private val projectNodes = mutableMapOf<String, DefaultMutableTreeNode>()

    init {
        initializeUI()
        setupEventHandling()

        // Start periodic refresh
        refreshTimer = Timer(1000) { refreshDisplay() }
        refreshTimer.start()

        // Load initial data
        refreshDisplay()
    }

    private fun initializeUI() {
        title = "Indexing Monitor"
        size = Dimension(1000, 700)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setLocationRelativeTo(parent)

        val contentPane = JPanel(BorderLayout())

        // Create main split pane
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.resizeWeight = 0.6

        // Left panel - project tree
        projectTree.model = treeModel
        projectTree.cellRenderer = IndexingTreeCellRenderer()
        projectTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        projectTree.isRootVisible = true
        projectTree.showsRootHandles = true

        val treeScrollPane = JScrollPane(projectTree)
        treeScrollPane.preferredSize = Dimension(600, 500)
        splitPane.leftComponent = treeScrollPane

        // Right panel - details and logs
        val rightPanel = JPanel(BorderLayout())

        // Log area
        logArea.isEditable = false
        logArea.background = Color.BLACK
        logArea.foreground = Color.GREEN
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        logArea.text = "Select an indexing step to view detailed logs..."

        val logScrollPane = JScrollPane(logArea)
        logScrollPane.preferredSize = Dimension(400, 500)
        rightPanel.add(logScrollPane, BorderLayout.CENTER)

        splitPane.rightComponent = rightPanel
        contentPane.add(splitPane, BorderLayout.CENTER)

        // Bottom status panel
        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = BorderFactory.createLoweredBevelBorder()
        statusPanel.add(statusLabel, BorderLayout.WEST)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refreshDisplay() }
        statusPanel.add(refreshButton, BorderLayout.EAST)

        contentPane.add(statusPanel, BorderLayout.SOUTH)

        add(contentPane)
    }

    private fun setupEventHandling() {
        // Tree selection handler
        projectTree.addTreeSelectionListener(
            object : TreeSelectionListener {
                override fun valueChanged(e: TreeSelectionEvent) {
                    val selectedNode = projectTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    selectedNode?.let { updateLogArea(it) }
                }
            },
        )

        // Window close handler
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    refreshTimer.stop()
                    coroutineScope.launch {
                        // Cancel coroutines if needed
                    }
                }
            },
        )
    }

    private fun refreshDisplay() {
        coroutineScope.launch {
            try {
                val projectStates = indexingMonitorService.getAllProjectStates()
                withContext(Dispatchers.Swing) {
                    updateTreeModel(projectStates)
                    updateStatusLabel(projectStates)
                }
            } catch (e: Exception) {
                logger.error(e) { "Error refreshing indexing monitor display" }
            }
        }
    }

    private fun updateTreeModel(projectStates: Map<String, ProjectIndexingStateDto>) {
        // Save current tree state before update
        val expandedPaths = saveExpandedPaths()
        val selectedPath = projectTree.selectionPath

        // Clear existing project nodes
        rootNode.removeAllChildren()
        projectNodes.clear()

        // Add project nodes
        projectStates.values.sortedBy { it.projectName }.forEach { state ->
            val projectNode = DefaultMutableTreeNode(ProjectNodeData(state))
            rootNode.add(projectNode)
            projectNodes[state.projectId] = projectNode

            // Add step nodes
            state.steps.forEach { step ->
                addStepNode(projectNode, step)
            }
        }

        treeModel.reload()

        // Restore tree state after update
        restoreExpandedPaths(expandedPaths)
        restoreSelection(selectedPath)
    }

    private fun addStepNode(
        parentNode: DefaultMutableTreeNode,
        step: IndexingStepDto,
    ) {
        val stepNode = DefaultMutableTreeNode(StepNodeData(step))
        parentNode.add(stepNode)

        // Add sub-steps recursively
        step.subSteps.forEach { subStep ->
            addStepNode(stepNode, subStep)
        }
    }

    /**
     * Save all currently expanded tree paths
     */
    private fun saveExpandedPaths(): Set<String> {
        val expandedPaths = mutableSetOf<String>()
        val enumeration = projectTree.getExpandedDescendants(TreePath(rootNode.path))

        enumeration?.let {
            while (it.hasMoreElements()) {
                val path = it.nextElement()
                expandedPaths.add(getPathIdentifier(path))
            }
        }

        return expandedPaths
    }

    /**
     * Restore previously expanded tree paths
     */
    private fun restoreExpandedPaths(expandedPaths: Set<String>) {
        // Always expand root node
        projectTree.expandPath(TreePath(rootNode.path))

        // Restore other expanded paths
        for (pathId in expandedPaths) {
            val treePath = findTreePathByIdentifier(pathId)
            treePath?.let { projectTree.expandPath(it) }
        }
    }

    /**
     * Restore previously selected tree path
     */
    private fun restoreSelection(selectedPath: TreePath?) {
        if (selectedPath != null) {
            val pathId = getPathIdentifier(selectedPath)
            val newPath = findTreePathByIdentifier(pathId)
            newPath?.let {
                projectTree.selectionPath = it
            }
        }
    }

    /**
     * Generate a unique identifier for a tree path based on node content
     */
    private fun getPathIdentifier(path: TreePath): String {
        val pathComponents = mutableListOf<String>()

        for (i in 0 until path.pathCount) {
            val node = path.getPathComponent(i) as? DefaultMutableTreeNode
            node?.let {
                when (val userData = it.userObject) {
                    is ProjectNodeData -> pathComponents.add("project:${userData.state.projectName}")
                    is StepNodeData -> pathComponents.add("step:${userData.step.stepType}")
                    else -> pathComponents.add("root")
                }
            }
        }

        return pathComponents.joinToString("/")
    }

    /**
     * Find a tree path by its identifier
     */
    private fun findTreePathByIdentifier(pathId: String): TreePath? {
        val components = pathId.split("/")
        var currentPath = TreePath(rootNode)

        // Skip root component (first element)
        for (i in 1 until components.size) {
            val component = components[i]
            val currentNode = currentPath.lastPathComponent as DefaultMutableTreeNode

            // Find matching child node
            var matchingChild: DefaultMutableTreeNode? = null
            for (j in 0 until currentNode.childCount) {
                val child = currentNode.getChildAt(j) as DefaultMutableTreeNode
                val childId =
                    when (val userData = child.userObject) {
                        is ProjectNodeData -> "project:${userData.state.projectName}"
                        is StepNodeData -> "step:${userData.step.stepType}"
                        else -> "unknown"
                    }

                if (childId == component) {
                    matchingChild = child
                    break
                }
            }

            if (matchingChild != null) {
                currentPath = currentPath.pathByAddingChild(matchingChild)
            } else {
                // Path not found
                return null
            }
        }

        return currentPath
    }

    private fun updateLogArea(node: DefaultMutableTreeNode) {
        when (val userData = node.userObject) {
            is ProjectNodeData -> {
                val state = userData.state
                val logs = mutableListOf<String>()
                logs.add("=== PROJECT: ${state.projectName} ===")
                logs.add("Status: ${state.status}")
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                logs.add(
                    "Started: ${state.startTime?.let { formatter.format(it.atZone(java.time.ZoneId.systemDefault())) } ?: "Not started"}",
                )
                state.endTime?.let {
                    logs.add("Ended: ${formatter.format(it.atZone(java.time.ZoneId.systemDefault()))}")
                }
                state.duration?.let {
                    logs.add("Duration: ${formatDuration(it)}")
                }
                state.overallProgress?.let { progress ->
                    logs.add(
                        "Progress: ${progress.current}/${progress.total} (${
                            String.format(
                                "%.1f",
                                progress.percentage,
                            )
                        }%)",
                    )
                }
                logs.add("")
                logs.add("=== STEPS OVERVIEW ===")
                state.steps.forEach { step ->
                    logs.add("${step.stepType.stepName}: ${step.status}")
                }
                logArea.text = logs.joinToString("\n")
            }

            is StepNodeData -> {
                val step = userData.step
                val logs = mutableListOf<String>()
                logs.add("=== STEP: ${step.stepType.stepName} ===")
                logs.add("Description: ${step.stepType.description}")
                logs.add("Status: ${step.status}")
                step.message?.let { logs.add("Message: $it") }
                step.errorMessage?.let { logs.add("Error: $it") }
                val stepFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                step.startTime?.let {
                    logs.add("Started: ${stepFormatter.format(it.atZone(java.time.ZoneId.systemDefault()))}")
                }
                step.endTime?.let {
                    logs.add("Ended: ${stepFormatter.format(it.atZone(java.time.ZoneId.systemDefault()))}")
                }
                step.duration?.let {
                    logs.add("Duration: ${formatDuration(it)}")
                }
                step.progress?.let { progress ->
                    logs.add(
                        "Progress: ${progress.current}/${progress.total} (${
                            String.format(
                                "%.1f",
                                progress.percentage,
                            )
                        }%)",
                    )
                }
                logs.add("")
                logs.add("=== DETAILED LOGS ===")
                if (step.logs.isEmpty()) {
                    logs.add("No logs available")
                } else {
                    logs.addAll(step.logs)
                }
                logArea.text = logs.joinToString("\n")
                logArea.caretPosition = logArea.document.length // Scroll to bottom
            }

            else -> {
                logArea.text = "Select a project or step to view details..."
            }
        }
    }

    private fun updateStatusLabel(projectStates: Map<String, ProjectIndexingStateDto>) {
        val activeCount = projectStates.values.count { it.isActive }
        val completedCount = projectStates.values.count { it.isCompleted }
        val failedCount = projectStates.values.count { it.hasFailed }

        statusLabel.text = "Projects - Active: $activeCount, Completed: $completedCount, Failed: $failedCount"
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    @EventListener
    suspend fun handleProgressEvent(event: IndexingProgressEventDto) {
        withContext(Dispatchers.Swing) {
            // Update UI based on event
            // The tree will be updated on next refresh cycle
            logger.debug { "Received progress event: ${event.stepType.stepName} - ${event.status}" }
        }
    }

    // Data classes for tree nodes
    data class ProjectNodeData(
        val state: ProjectIndexingStateDto,
    ) {
        override fun toString(): String {
            val statusIcon =
                when (state.status) {
                    IndexingStepStatusEnum.RUNNING -> "🔄"
                    IndexingStepStatusEnum.COMPLETED -> "✅"
                    IndexingStepStatusEnum.FAILED -> "❌"
                    IndexingStepStatusEnum.PENDING -> "⏳"
                    IndexingStepStatusEnum.SKIPPED -> "⏭️"
                }
            val progressText =
                state.overallProgress?.let {
                    " (${it.current}/${it.total})"
                } ?: ""
            return "$statusIcon ${state.projectName}$progressText"
        }
    }

    data class StepNodeData(
        val step: IndexingStepDto,
    ) {
        override fun toString(): String {
            val statusIcon =
                when (step.status) {
                    IndexingStepStatusEnum.RUNNING -> "🔄"
                    IndexingStepStatusEnum.COMPLETED -> "✅"
                    IndexingStepStatusEnum.FAILED -> "❌"
                    IndexingStepStatusEnum.PENDING -> "⏳"
                    IndexingStepStatusEnum.SKIPPED -> "⏭️"
                }
            val progressText =
                step.progress?.let {
                    " (${it.current}/${it.total})"
                } ?: ""
            return "$statusIcon ${step.stepType.stepName}$progressText"
        }
    }

    // Custom tree cell renderer for better visual representation
    private inner class IndexingTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): AwtComponent {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

            val node = value as? DefaultMutableTreeNode
            when (val userData = node?.userObject) {
                is ProjectNodeData -> {
                    foreground =
                        when (userData.state.status) {
                            IndexingStepStatusEnum.RUNNING -> Color.BLUE
                            IndexingStepStatusEnum.COMPLETED -> Color.GREEN.darker()
                            IndexingStepStatusEnum.FAILED -> Color.RED
                            else -> Color.BLACK
                        }
                    font = font.deriveFont(Font.BOLD)
                }

                is StepNodeData -> {
                    foreground =
                        when (userData.step.status) {
                            IndexingStepStatusEnum.RUNNING -> Color.BLUE
                            IndexingStepStatusEnum.COMPLETED -> Color.GREEN.darker()
                            IndexingStepStatusEnum.FAILED -> Color.RED
                            IndexingStepStatusEnum.PENDING -> Color.GRAY
                            IndexingStepStatusEnum.SKIPPED -> Color.ORANGE
                        }
                    font = font.deriveFont(Font.PLAIN)
                }
            }

            return this
        }
    }
}
