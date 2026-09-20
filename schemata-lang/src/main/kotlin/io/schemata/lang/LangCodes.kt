package io.schemata.lang

object LangCodes {
    val SYNTAX = DiagnosticCode("SCH0001", Severity.ERROR, Category.SYNTAX)
    val RESERVED_KEYWORD = DiagnosticCode("SCH0002", Severity.ERROR, Category.SYNTAX)
    val NUMERIC_LITERAL_RANGE = DiagnosticCode("SCH0003", Severity.ERROR, Category.SYNTAX)

    val all: List<DiagnosticCode> = listOf(SYNTAX, RESERVED_KEYWORD, NUMERIC_LITERAL_RANGE)
}
