package io.schemata.target.proto

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** Proto catalog, `SCH20xx`. */
object ProtoCodes {
    val LOSSY = DiagnosticCode("SCH2001", Severity.WARNING, Category.LOSSY)
    val UNSUPPORTED_SHAPE = DiagnosticCode("SCH2002", Severity.ERROR, Category.SEMANTIC)
    val UNSUPPORTED_VALUE = DiagnosticCode("SCH2003", Severity.ERROR, Category.SEMANTIC)
    val NAME_COLLISION = DiagnosticCode("SCH2004", Severity.ERROR, Category.SEMANTIC)
    val UNSUPPORTED_NESTING = DiagnosticCode("SCH2005", Severity.ERROR, Category.SEMANTIC)

    val all: List<DiagnosticCode> =
        listOf(LOSSY, UNSUPPORTED_SHAPE, UNSUPPORTED_VALUE, NAME_COLLISION, UNSUPPORTED_NESTING)
}
