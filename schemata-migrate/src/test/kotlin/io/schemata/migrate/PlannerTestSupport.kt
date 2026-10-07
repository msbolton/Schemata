package io.schemata.migrate

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Parser
import io.schemata.target.sql.SqlAnnotations
import io.schemata.target.sql.SqlLowering

/** Analyses one `.schemata` text and lowers it with the SQL target; fails on any error. */
fun side(source: String): Side {
    val analyzed =
        Analyzer.analyze(
            listOf(Parser.parse(source.trimIndent(), "s.schemata").file!!),
            AnalysisOptions(
                annotations = AnnotationRegistry(CoreAnnotations.specs + SqlAnnotations.specs)
            ),
        )
    val schema = checkNotNull(analyzed.schema) { analyzed.diagnostics.joinToString("\n") }
    val lowered = SqlLowering.lower(schema)
    check(lowered.diagnostics.none { it.severity == io.schemata.lang.Severity.ERROR }) {
        lowered.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" }
    }
    return Side(schema, lowered.model)
}

fun plan(old: String, new: String): List<Step> = Planner.plan(side(old), side(new)).steps
