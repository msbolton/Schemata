package io.schemata.target.openapi

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** OpenAPI catalog, `SCH26xx`. */
object OpenApiCodes {
    val QUERY_SHAPE =
        DiagnosticCode(
            "SCH2601",
            Severity.ERROR,
            Category.SEMANTIC,
            "a request field cannot be a query parameter",
        )
    val COLLISION =
        DiagnosticCode(
            "SCH2602",
            Severity.ERROR,
            Category.SEMANTIC,
            "two services, operations, or schemas lower to one OpenAPI name",
        )
    val INVALID_OVERRIDE =
        DiagnosticCode(
            "SCH2603",
            Severity.ERROR,
            Category.SEMANTIC,
            "an @openapi value is not a valid id, URL, or key",
        )
    val LOSSY =
        DiagnosticCode(
            "SCH2604",
            Severity.WARNING,
            Category.LOSSY,
            "something OpenAPI cannot express was dropped by the lowering",
        )

    val all: List<DiagnosticCode> = listOf(QUERY_SHAPE, COLLISION, INVALID_OVERRIDE, LOSSY)
}
