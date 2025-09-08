package com.jervis.domain.language

/**
 * Enum representing supported communication languages in the system.
 * Used for configuring language preferences for different platforms and communications.
 */
enum class Language(
    val code: String,
    val displayName: String,
) {
    CZECH("cs", "Čeština"),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    RUSSIAN("ru", "Русский"),
    POLISH("pl", "Polski"),
    HUNGARIAN("hu", "Magyar"),
    SLOVAK("sk", "Slovenčina"),
    ;

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code.equals(code, ignoreCase = true) }

        fun getDefault(): Language = ENGLISH
    }
}
