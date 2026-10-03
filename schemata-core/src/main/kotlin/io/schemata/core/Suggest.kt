package io.schemata.core

/** Name suggestions for naming diagnostics; best effort, never asserted by the compiler. */
object Suggest {
    private val camelBoundary = Regex("([a-z0-9])([A-Z])|([A-Z])([A-Z][a-z])")
    private val separators = Regex("[^A-Za-z0-9]+")

    fun lowerSnake(name: String): String =
        name
            .replace(camelBoundary) { m ->
                val g = m.groupValues
                if (g[1].isNotEmpty()) "${g[1]}_${g[2]}" else "${g[3]}_${g[4]}"
            }
            .replace(separators, "_")
            .replace(Regex("_+"), "_")
            .lowercase()
            .trim('_')

    fun upperCamel(name: String): String =
        lowerSnake(name)
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /** The suggestion when it is non-empty and differs from the name, else null. */
    fun example(name: String, suggestion: String): String? =
        suggestion.takeIf { it.isNotEmpty() && it != name }
}
