package io.schemata.importer.xsd

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** XSD import catalog, `SCH24xx`. */
object ImportCodes {
    val UNRESOLVED =
        DiagnosticCode(
            "SCH2401",
            Severity.ERROR,
            Category.SEMANTIC,
            "an xsd reference, import, or include cannot be resolved, or two constructs lower to one name",
        )
    val RENAMED =
        DiagnosticCode(
            "SCH2402",
            Severity.WARNING,
            Category.LOSSY,
            "an xsd name was changed to a schemata identifier",
        )
    val APPROXIMATED =
        DiagnosticCode(
            "SCH2403",
            Severity.WARNING,
            Category.LOSSY,
            "an xsd construct was approximated",
        )
    val WIDENED =
        DiagnosticCode(
            "SCH2404",
            Severity.WARNING,
            Category.LOSSY,
            "an xsd type or facet was widened or dropped",
        )
    val DROPPED =
        DiagnosticCode("SCH2405", Severity.WARNING, Category.LOSSY, "an xsd construct was dropped")

    val all: List<DiagnosticCode> = listOf(UNRESOLVED, RENAMED, APPROXIMATED, WIDENED, DROPPED)
}
