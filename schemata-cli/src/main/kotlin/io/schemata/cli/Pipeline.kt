package io.schemata.cli

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
 * parse → analyze → (lower → render) per target. Stops at the first stage that reports an error.
 */
object Pipeline {
    val targets: List<Target<*>> = listOf(ProtoTarget, SqlTarget)

    fun targetNamed(name: String): Target<*>? = targets.firstOrNull { it.name == name }

    fun compile(source: String, targets: List<Target<*>>): PipelineResult {
        val parsed = Parser.parse(source)
        val file = parsed.file ?: return PipelineResult(emptyList(), parsed.diagnostics)

        val analyzed = Analyzer.analyze(file)
        val diagnostics = parsed.diagnostics + analyzed.diagnostics
        val schema = analyzed.schema ?: return PipelineResult(emptyList(), diagnostics)

        val outputs = targets.map { target -> target to target.compile(schema) }
        return PipelineResult(
            files =
                outputs.flatMap { (target, out) -> out.files.map { TargetFile(target.name, it) } },
            diagnostics = diagnostics + outputs.flatMap { (_, out) -> out.diagnostics },
        )
    }
}
