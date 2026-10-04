package io.schemata.target.xsd

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** XSD catalog, `SCH22xx`. */
object XsdCodes {
    val LOSSY =
        DiagnosticCode(
            "SCH2201",
            Severity.WARNING,
            Category.LOSSY,
            "something XSD 1.0 cannot express was dropped by the lowering",
        )
    val NAME_COLLISION =
        DiagnosticCode(
            "SCH2202",
            Severity.ERROR,
            Category.SEMANTIC,
            "two constructs lower to the same XSD name",
        )
    val NAMESPACE_COLLISION =
        DiagnosticCode(
            "SCH2203",
            Severity.ERROR,
            Category.SEMANTIC,
            "two namespaces lower to the same target namespace",
        )
    val ATTRIBUTE_NOT_ALLOWED =
        DiagnosticCode(
            "SCH2204",
            Severity.ERROR,
            Category.SEMANTIC,
            "an @xsd representation key is on a field that cannot take it",
        )
    val INVALID_OVERRIDE =
        DiagnosticCode(
            "SCH2205",
            Severity.ERROR,
            Category.SEMANTIC,
            "an @xsd override is not a valid XML name or URI",
        )

    val all: List<DiagnosticCode> =
        listOf(LOSSY, NAME_COLLISION, NAMESPACE_COLLISION, ATTRIBUTE_NOT_ALLOWED, INVALID_OVERRIDE)
}
