package io.schemata.core

import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.ast.SourceFile

/** [schema] is null exactly when [diagnostics] contains an error. */
data class AnalysisResult(val schema: Schema?, val diagnostics: List<Diagnostic>)

object Analyzer {
    fun analyze(
        files: List<SourceFile>,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
    ): AnalysisResult = throw UnsupportedOperationException("replaced in Plan B1 Task 2")
}
