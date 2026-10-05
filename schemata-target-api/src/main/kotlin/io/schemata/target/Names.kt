package io.schemata.target

/** Naming rules every target shares. */
object Names {
    private val boundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /**
     * `BankTransfer` → `bank_transfer`, `HTTPStatus` → `http_status`; a lowercase name is
     * unchanged.
     */
    fun snakeCase(name: String): String = name.split(boundary).joinToString("_").lowercase()

    /**
     * `list_orders` → `ListOrders`, `get_v2` → `GetV2`, `get` → `Get`: split on `_`, drop empty
     * parts, upper-case each part's first character.
     */
    fun upperCamel(lowerSnake: String): String =
        lowerSnake
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
