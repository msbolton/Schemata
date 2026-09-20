package io.schemata.target.proto

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

object ProtoCodes {
    val LOSSY_UUID = DiagnosticCode("SCH2001", Severity.WARNING, Category.LOSSY)

    val all: List<DiagnosticCode> = listOf(LOSSY_UUID)
}
