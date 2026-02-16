package com.jervis.ui.screens.environment

/**
 * Tabs available in Environment Manager detail panel.
 */
enum class EnvironmentManagerTab(val label: String) {
    OVERVIEW("Přehled"),
    COMPONENTS("Komponenty"),
    K8S_RESOURCES("K8s zdroje"),
    LOGS_EVENTS("Logy & Události"),
}
