package com.jervis.ui.style

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Lightweight UI design helper for consistent spacing and structure across windows.
 * Keeps Swing code idiomatic and avoids introducing new frameworks.
 */
object UiDesign {
    // Spacing
    const val gap = 8
    const val sectionGap = 12
    const val outerMargin = 10

    // Fonts
    val headerFont = Font("SansSerif", Font.BOLD, 16)
    val subHeaderFont = Font("SansSerif", Font.BOLD, 13)

    fun headerLabel(text: String): JLabel =
        JLabel(text).apply {
            font = headerFont
        }

    fun subHeaderLabel(text: String): JLabel =
        JLabel(text).apply {
            font = subHeaderFont
            foreground = Color(0x33, 0x33, 0x33)
        }

    fun sectionPanel(
        title: String? = null,
        content: JComponent,
    ): JPanel =
        JPanel(BorderLayout(gap, gap)).apply {
            border = BorderFactory.createEmptyBorder(sectionGap, sectionGap, sectionGap, sectionGap)
            if (title != null) {
                add(subHeaderLabel(title), BorderLayout.NORTH)
            }
            add(content, BorderLayout.CENTER)
        }

    fun actionBar(vararg actions: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, gap, 0)).apply {
            actions.forEach { add(it) }
        }

    fun formPanel(builder: FormBuilder.() -> Unit): JPanel =
        JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(sectionGap, sectionGap, sectionGap, sectionGap)
            val gbc =
                GridBagConstraints().apply {
                    anchor = GridBagConstraints.LINE_START
                    insets = java.awt.Insets(gap, gap, gap, gap)
                }
            val fb = FormBuilder(this, gbc)
            fb.builder()
        }

    class FormBuilder(
        private val panel: JPanel,
        private val gbc: GridBagConstraints,
    ) {
        private var row = 0

        fun row(
            label: String,
            field: JComponent,
        ) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(JLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(field, gbc)
            row++
        }

        fun fullWidth(comp: JComponent) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(comp, gbc)
            gbc.gridwidth = 1
            row++
        }
    }
}
