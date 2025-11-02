package com.jervis.ui.component

import com.jervis.dto.ProjectOverridesDto
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Simple UI panel for project-level integration overrides.
 * No direct server calls; values are persisted via ProjectDto.overrides.
 */
class ProjectIntegrationOverridesPanel(
    initialOverrides: ProjectOverridesDto? = null,
) : JPanel(GridBagLayout()) {
    private val jiraProjectKeyField = JTextField(20)
    private val confluenceSpaceKeyField = JTextField(20)
    private val confluenceRootPageIdField = JTextField(20)

    init {
        layoutUI()
        applyInitial(initialOverrides)
    }

    private fun layoutUI() {
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                anchor = GridBagConstraints.LINE_START
            }
        var row = 0

        fun addRow(
            label: String,
            field: JTextField,
        ) {
            gbc.gridx = 0
            gbc.gridy = row
            add(JLabel(label), gbc)
            gbc.gridx = 1
            gbc.gridy = row
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(field, gbc)
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            row++
        }

        addRow("Jira Project Key (override):", jiraProjectKeyField)
        addRow("Confluence Space Key (override):", confluenceSpaceKeyField)
        addRow("Confluence Root Page ID (override):", confluenceRootPageIdField)
    }

    private fun applyInitial(overrides: ProjectOverridesDto?) {
        if (overrides == null) return
        jiraProjectKeyField.text = overrides.jiraProjectKey ?: ""
        confluenceSpaceKeyField.text = overrides.confluenceSpaceKey ?: ""
        confluenceRootPageIdField.text = overrides.confluenceRootPageId ?: ""
    }

    fun toOverridesPatch(): ProjectOverridesDto =
        ProjectOverridesDto(
            jiraProjectKey = jiraProjectKeyField.text.trim().ifEmpty { null },
            confluenceSpaceKey = confluenceSpaceKeyField.text.trim().ifEmpty { null },
            confluenceRootPageId = confluenceRootPageIdField.text.trim().ifEmpty { null },
        )
}
