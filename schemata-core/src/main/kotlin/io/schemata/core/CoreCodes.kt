package io.schemata.core

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

object CoreCodes {
    private fun semantic(id: String) = DiagnosticCode(id, Severity.ERROR, Category.SEMANTIC)

    val NAMESPACE_SEGMENT_NAMING = semantic("SCH1001")
    val RECORD_NAMING = semantic("SCH1002")
    val FIELD_NAMING = semantic("SCH1003")
    val DUPLICATE_RECORD = semantic("SCH1004")
    val DUPLICATE_FIELD = semantic("SCH1005")
    val UNKNOWN_TYPE = semantic("SCH1006")
    val RECORD_TYPED_FIELD = semantic("SCH1007")
    val UNSUPPORTED_CONSTRUCT = semantic("SCH1008")

    val all: List<DiagnosticCode> =
        listOf(
            NAMESPACE_SEGMENT_NAMING,
            RECORD_NAMING,
            FIELD_NAMING,
            DUPLICATE_RECORD,
            DUPLICATE_FIELD,
            UNKNOWN_TYPE,
            RECORD_TYPED_FIELD,
            UNSUPPORTED_CONSTRUCT,
        )
}
