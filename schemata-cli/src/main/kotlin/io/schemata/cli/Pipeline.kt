package io.schemata.cli

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.hasErrors
import io.schemata.target.OutputFile
import io.schemata.target.Target
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlTarget

data class TargetFile(val target: String, val file: OutputFile)

data class PipelineResult(val files: List<TargetFile>, val diagnostics: List<Diagnostic>) {
    val hasErrors: Boolean
        get() = diagnostics.hasErrors
}

/**
 * parse every source → analyze the set → (lower → render) per target. Stops at the first stage that
 * reports an error; every file is parsed before stopping so all syntax errors are reported.
 */
object Pipeline {
    val targets: List<Target<*>> = listOf(ProtoTarget, SqlTarget)

    fun targetNamed(name: String): Target<*>? = targets.firstOrNull { it.name == name }

    fun compile(
        sources: List<SourceInput>,
        targets: List<Target<*>>,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
    ): PipelineResult {
        val parsed = sources.map { Parser.parse(it.content, it.path) }
        val parseDiagnostics = parsed.flatMap { it.diagnostics }
        if (parseDiagnostics.hasErrors) return PipelineResult(emptyList(), parseDiagnostics)

        val analyzed = Analyzer.analyze(parsed.map { it.file!! }, options)
        val diagnostics = parseDiagnostics + analyzed.diagnostics
        val schema = analyzed.schema ?: return PipelineResult(emptyList(), diagnostics)

        val outputs = targets.map { target -> target to target.compile(schema) }
        return PipelineResult(
            files =
                outputs.flatMap { (target, out) -> out.files.map { TargetFile(target.name, it) } },
            diagnostics = diagnostics + outputs.flatMap { (_, out) -> out.diagnostics },
        )
    }
}
