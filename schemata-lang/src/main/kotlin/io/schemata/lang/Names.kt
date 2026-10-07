package io.schemata.lang

/** What the lexer accepts as a name: an identifier that is not one of the language's keywords. */
object Names {
    /** The words the lexer reads as keywords, so none of them can name a declaration or field. */
    val keywords: Set<String> =
        setOf(
            "schema",
            "import",
            "as",
            "model",
            "enum",
            "union",
            "alias",
            "reserved",
            "true",
            "false",
            "service",
            "operation",
            "stream",
        )

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Whether [text] lexes as one identifier token (a keyword does not). */
    fun isIdentifier(text: String): Boolean = identifier.matches(text) && text !in keywords
}
