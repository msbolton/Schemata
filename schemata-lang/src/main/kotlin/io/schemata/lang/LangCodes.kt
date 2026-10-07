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

    val BAD_ESCAPE =
        DiagnosticCode(
            "SCH0004",
            Severity.ERROR,
            Category.SYNTAX,
            "a string holds an escape the language does not define",
        )

    val CONTROL_CHARACTER =
        DiagnosticCode(
            "SCH0005",
            Severity.ERROR,
            Category.SYNTAX,
            "a string or doc comment holds a control character XML cannot carry",
        )

    val UNKNOWN_VERB =
        DiagnosticCode(
            "SCH0006",
            Severity.ERROR,
            Category.SYNTAX,
            "a binding's verb is not an HTTP method",
        )

    val MALFORMED_PATH =
        DiagnosticCode("SCH0007", Severity.ERROR, Category.SYNTAX, "a binding's path is malformed")

    val LEGACY_SYNTAX =
        DiagnosticCode(
            "SCH0008",
            Severity.ERROR,
            Category.SYNTAX,
            "a 1.x schema; run schemata upgrade",
        )

    val all: List<DiagnosticCode> =
        listOf(
            SYNTAX,
            RESERVED_KEYWORD,
            NUMERIC_LITERAL_RANGE,
            BAD_ESCAPE,
            CONTROL_CHARACTER,
            UNKNOWN_VERB,
            MALFORMED_PATH,
            LEGACY_SYNTAX,
        )
}
