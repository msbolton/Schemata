package io.schemata.core

import io.schemata.lang.Names

/** Name suggestions for naming diagnostics; best effort, never asserted by the compiler. */
object Suggest {
    private val camelBoundary = Regex("([a-z0-9])([A-Z])|([A-Z])([A-Z][a-z])")
    private val separators = Regex("[^A-Za-z0-9]+")

    /**
     * A lower_snake name the language accepts: a keyword or `null` takes a `_value` suffix, and a
     * name starting with a digit a `v` prefix, as the XSD importer names them.
     */
    fun lowerSnake(name: String): String {
        val snake = snake(name)
        return when {
            snake in Names.keywords || snake == NULL -> "${snake}_value"
            snake.firstOrNull()?.isDigit() == true -> "v$snake"
            else -> snake
        }
    }

    fun upperCamel(name: String): String =
        snake(name)
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /** The suggestion when it is non-empty and differs from the name, else null. */
    fun example(name: String, suggestion: String): String? =
        suggestion.takeIf { it.isNotEmpty() && it != name }

    private fun snake(name: String): String =
        name
            .replace(camelBoundary) { m ->
                val g = m.groupValues
                if (g[1].isNotEmpty()) "${g[1]}_${g[2]}" else "${g[3]}_${g[4]}"
            }
            .replace(separators, "_")
            .lowercase()
            .trim('_')

    /** Not a keyword, since `= null` reads it as a literal, but reserved as a name all the same. */
    private const val NULL = "null"
}
