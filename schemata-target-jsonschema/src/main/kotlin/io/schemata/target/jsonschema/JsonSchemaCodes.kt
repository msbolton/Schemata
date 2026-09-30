package io.schemata.target.jsonschema

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** JSON Schema catalog, `SCH23xx`. */
object JsonSchemaCodes {
    val LOSSY =
        DiagnosticCode(
            "SCH2301",
            Severity.WARNING,
            Category.LOSSY,
            "something JSON Schema cannot express was dropped or approximated by the lowering",
        )
    val NAME_COLLISION =
        DiagnosticCode(
            "SCH2302",
            Severity.ERROR,
            Category.SEMANTIC,
            "two constructs lower to the same JSON Schema name",
        )
    val INVALID_OVERRIDE =
        DiagnosticCode(
            "SCH2303",
            Severity.ERROR,
            Category.SEMANTIC,
            "a @jsonschema override is empty, holds a character a \$ref cannot carry, or is not an absolute URI",
        )
    val ID_COLLISION =
        DiagnosticCode(
            "SCH2304",
            Severity.ERROR,
            Category.SEMANTIC,
            "two namespaces lower to the same \$id",
        )

    val all: List<DiagnosticCode> = listOf(LOSSY, NAME_COLLISION, INVALID_OVERRIDE, ID_COLLISION)
}
