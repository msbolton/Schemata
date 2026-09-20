package io.schemata.target.sql

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

object SqlCodes {
    val TABLE_COLLISION = DiagnosticCode("SCH2101", Severity.ERROR, Category.SEMANTIC)
    val SCHEMA_COLLISION = DiagnosticCode("SCH2102", Severity.ERROR, Category.SEMANTIC)

    val all: List<DiagnosticCode> = listOf(TABLE_COLLISION, SCHEMA_COLLISION)
}
