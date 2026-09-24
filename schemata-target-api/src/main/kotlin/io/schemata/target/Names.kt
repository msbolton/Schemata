package io.schemata.target

/** Naming rules every target shares. */
object Names {
    private val boundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /**
     * `BankTransfer` → `bank_transfer`, `HTTPStatus` → `http_status`; a lowercase name is
     * unchanged.
     */
    fun snakeCase(name: String): String = name.split(boundary).joinToString("_").lowercase()
}
