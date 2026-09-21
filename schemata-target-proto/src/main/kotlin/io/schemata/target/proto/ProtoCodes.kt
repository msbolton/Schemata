package io.schemata.target.proto

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** Proto catalog, `SCH20xx`. `SCH2002` and `SCH2003` are retired and must not be reused. */
object ProtoCodes {
    val LOSSY = DiagnosticCode("SCH2001", Severity.WARNING, Category.LOSSY)
    val NAME_COLLISION = DiagnosticCode("SCH2004", Severity.ERROR, Category.SEMANTIC)
    val UNSUPPORTED_NESTING = DiagnosticCode("SCH2005", Severity.ERROR, Category.SEMANTIC)
    val INVALID_FIELD_NUMBER = DiagnosticCode("SCH2006", Severity.ERROR, Category.SEMANTIC)
    val INVALID_OVERRIDE = DiagnosticCode("SCH2007", Severity.ERROR, Category.SEMANTIC)

    val all: List<DiagnosticCode> =
        listOf(LOSSY, NAME_COLLISION, UNSUPPORTED_NESTING, INVALID_FIELD_NUMBER, INVALID_OVERRIDE)
}
