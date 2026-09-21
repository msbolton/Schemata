package io.schemata.target.proto

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

object ProtoCodes {
    val LOSSY_UUID = DiagnosticCode("SCH2001", Severity.WARNING, Category.LOSSY)
    val UNSUPPORTED_SHAPE = DiagnosticCode("SCH2002", Severity.ERROR, Category.SEMANTIC)
    val UNSUPPORTED_VALUE = DiagnosticCode("SCH2003", Severity.ERROR, Category.SEMANTIC)

    val all: List<DiagnosticCode> = listOf(LOSSY_UUID, UNSUPPORTED_SHAPE, UNSUPPORTED_VALUE)
}
