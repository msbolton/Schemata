package io.schemata.lang

object LangCodes {
    val SYNTAX =
        DiagnosticCode(
            "SCH0001",
            Severity.ERROR,
            Category.SYNTAX,
            "the parser cannot continue at this token",
        )
    val RESERVED_KEYWORD =
        DiagnosticCode(
            "SCH0002",
            Severity.ERROR,
            Category.SYNTAX,
            "a reserved keyword starts a declaration",
        )
    val NUMERIC_LITERAL_RANGE =
        DiagnosticCode(
            "SCH0003",
            Severity.ERROR,
            Category.SYNTAX,
            "an integer or ordinal literal is out of range",
        )

    val all: List<DiagnosticCode> = listOf(SYNTAX, RESERVED_KEYWORD, NUMERIC_LITERAL_RANGE)
}
