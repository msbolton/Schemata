package io.schemata.target.sql

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** SQL catalog, `SCH21xx`. `SCH2104` is retired and must not be reused. */
object SqlCodes {
    val TABLE_COLLISION = DiagnosticCode("SCH2101", Severity.ERROR, Category.SEMANTIC)
    val SCHEMA_COLLISION = DiagnosticCode("SCH2102", Severity.ERROR, Category.SEMANTIC)
    val UNSUPPORTED_SHAPE = DiagnosticCode("SCH2103", Severity.ERROR, Category.SEMANTIC)
    val LOSSY = DiagnosticCode("SCH2105", Severity.WARNING, Category.LOSSY)
    val MISSING_KEY = DiagnosticCode("SCH2106", Severity.ERROR, Category.SEMANTIC)
    val KEY_COLUMN = DiagnosticCode("SCH2107", Severity.ERROR, Category.SEMANTIC)
    val RECURSIVE_EMBED = DiagnosticCode("SCH2108", Severity.ERROR, Category.SEMANTIC)
    val IDENTIFIER_TRUNCATED = DiagnosticCode("SCH2109", Severity.WARNING, Category.SEMANTIC)
    val STRATEGY_NOT_ALLOWED = DiagnosticCode("SCH2110", Severity.ERROR, Category.SEMANTIC)
    val NAME_COLLISION = DiagnosticCode("SCH2111", Severity.ERROR, Category.SEMANTIC)
    val TYPE_LIMIT = DiagnosticCode("SCH2112", Severity.ERROR, Category.SEMANTIC)
    val REDUNDANT_CONSTRAINT = DiagnosticCode("SCH2113", Severity.WARNING, Category.SEMANTIC)

    val all: List<DiagnosticCode> =
        listOf(
            TABLE_COLLISION,
            SCHEMA_COLLISION,
            UNSUPPORTED_SHAPE,
            LOSSY,
            MISSING_KEY,
            KEY_COLUMN,
            RECURSIVE_EMBED,
            IDENTIFIER_TRUNCATED,
            STRATEGY_NOT_ALLOWED,
            NAME_COLLISION,
            TYPE_LIMIT,
            REDUNDANT_CONSTRAINT,
        )
}
