package io.schemata.target.sql

object Naming {
    private val boundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /** `OrderLine` → `order_line`. Input is UpperCamel; the analyzer guarantees that. */
    fun snakeCase(upperCamel: String): String =
        upperCamel.split(boundary).joinToString("_").lowercase()
}
