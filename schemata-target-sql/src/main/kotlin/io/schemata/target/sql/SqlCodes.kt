package io.schemata.target.sql

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/**
 * SQL catalog, `SCH21xx`. `SCH2103`, `SCH2104`, and `SCH2107` (a nullable or non-scalar key field,
 * which core now reports for every target) are retired and must not be reused.
 */
object SqlCodes {
    val TABLE_COLLISION =
        DiagnosticCode(
            "SCH2101",
            Severity.ERROR,
            Category.SEMANTIC,
            "two models lower to the same table",
        )
    val SCHEMA_COLLISION =
        DiagnosticCode(
            "SCH2102",
            Severity.ERROR,
            Category.SEMANTIC,
            "two schemas lower to the same Postgres schema",
        )
    val LOSSY =
        DiagnosticCode(
            "SCH2105",
            Severity.WARNING,
            Category.LOSSY,
            "something Postgres cannot enforce or type was relaxed by the lowering",
        )
    val MISSING_KEY =
        DiagnosticCode(
            "SCH2106",
            Severity.ERROR,
            Category.SEMANTIC,
            "a model has no primary key and no field uses it",
        )
    val RECURSIVE_EMBED =
        DiagnosticCode(
            "SCH2108",
            Severity.ERROR,
            Category.SEMANTIC,
            "embedding a model would recurse",
        )
    val IDENTIFIER_TRUNCATED =
        DiagnosticCode(
            "SCH2109",
            Severity.WARNING,
            Category.SEMANTIC,
            "an identifier exceeds Postgres's 63-byte limit and was truncated",
        )
    val STRATEGY_NOT_ALLOWED =
        DiagnosticCode(
            "SCH2110",
            Severity.ERROR,
            Category.SEMANTIC,
            "a strategy or constraint is not allowed for the field's shape",
        )
    val NAME_COLLISION =
        DiagnosticCode("SCH2111", Severity.ERROR, Category.SEMANTIC, "two relational names collide")
    val TYPE_LIMIT =
        DiagnosticCode(
            "SCH2112",
            Severity.ERROR,
            Category.SEMANTIC,
            "a decimal precision exceeds Postgres's limit",
        )
    val REDUNDANT_CONSTRAINT =
        DiagnosticCode(
            "SCH2113",
            Severity.WARNING,
            Category.SEMANTIC,
            "a unique or index duplicates the primary key",
        )
    val INVALID_OVERRIDE =
        DiagnosticCode(
            "SCH2114",
            Severity.ERROR,
            Category.SEMANTIC,
            "an @sql name override is empty",
        )

    val all: List<DiagnosticCode> =
        listOf(
            TABLE_COLLISION,
            SCHEMA_COLLISION,
            LOSSY,
            MISSING_KEY,
            RECURSIVE_EMBED,
            IDENTIFIER_TRUNCATED,
            STRATEGY_NOT_ALLOWED,
            NAME_COLLISION,
            TYPE_LIMIT,
            REDUNDANT_CONSTRAINT,
            INVALID_OVERRIDE,
        )
}
