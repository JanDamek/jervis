package com.jervis.ui.window.project

import javax.swing.table.DefaultTableCellRenderer

/**
 * Custom cell renderer for boolean values
 */
class BooleanTableCellRenderer : DefaultTableCellRenderer() {
    init {
        // Ensure renderer is opaque to prevent grid lines from painting over text
        isOpaque = true
    }

    override fun setValue(value: Any?) {
        text =
            when (value as? Boolean) {
                true -> "Yes"
                false -> "No"
                else -> ""
            }
        horizontalAlignment = CENTER
    }
}
