package com.jervis.ui.window.project

import com.jervis.dto.ProjectDto
import javax.swing.table.AbstractTableModel

/**
 * Table model for projects
 */
class ProjectTableModel(
    private var projectList: List<ProjectDto>,
) : AbstractTableModel() {
    private val columns = arrayOf("ID", "Name", "Path", "Description", "Client", "Disabled", "Active", "Is Current")

    fun updateProjects(projects: List<ProjectDto>) {
        this.projectList = projects
        fireTableDataChanged()
    }

    fun getProjectAt(rowIndex: Int): ProjectDto = projectList[rowIndex]

    override fun getRowCount(): Int = projectList.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        val project = projectList[rowIndex]

        return when (columnIndex) {
            0 -> project.id
            1 -> project.name
            2 -> project.description ?: ""
            3 -> project.isDisabled
            4 -> project.isActive
            else -> ""
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> =
        when (columnIndex) {
            0 -> String::class.java
            5, 6, 7 -> Boolean::class.java
            else -> String::class.java
        }

    override fun isCellEditable(
        rowIndex: Int,
        columnIndex: Int,
    ): Boolean {
        return false // All cells are non-editable directly in table
    }
}
