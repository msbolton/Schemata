package io.schemata.cli

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.hasErrors
import io.schemata.target.OutputFile
import io.schemata.target.Target
import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlTarget
import io.schemata.target.xsd.XsdTarget

data class TargetFile(val target: String, val file: OutputFile)

/** One target's outcome. [files] is empty when the target reported an error or was only checked. */
data class TargetResult(
    val name: String,
    val files: List<OutputFile>,
    val diagnostics: List<Diagnostic>,
) {
    val ok: Boolean
        get() = !diagnostics.hasErrors
}

/**
 * [core] holds parse and analysis diagnostics; [targets] holds one entry per selected target, in
 * selection order, and is empty when core reported an error.
 */
data class PipelineResult(val core: List<Diagnostic>, val targets: List<TargetResult>) {
    val diagnostics: List<Diagnostic>
        get() = core + targets.flatMap { it.diagnostics }

    val files: List<TargetFile>
        get() = targets.flatMap { t -> t.files.map { TargetFile(t.name, it) } }

    val hasErrors: Boolean
        get() = diagnostics.hasErrors
}

/**
 * parse every source → analyze the set → per target, lower then render. Stops after parsing or
 * analysis when that stage reports an error; every file is parsed before stopping so all syntax
 * errors are reported. A target's own error stops only that target.
 */
object Pipeline {
    val targets: List<Target<*>> = listOf(ProtoTarget, SqlTarget, XsdTarget, JsonSchemaTarget)

    /**
     * Core's keys plus every target's, whatever `--target` selects: validity never depends on the
     * emitters chosen.
     */
    val annotations: AnnotationRegistry =
        AnnotationRegistry(CoreAnnotations.specs + targets.flatMap { it.annotationSpecs })

    fun targetNamed(name: String): Target<*>? = targets.firstOrNull { it.name == name }

    fun compile(
        sources: List<SourceInput>,
        targets: List<Target<*>>,
        strict: Boolean = false,
    ): PipelineResult =
        run(sources, targets, strict) { target, schema ->
            val out = target.compile(schema)
            TargetResult(target.name, out.files, out.diagnostics)
        }

    /** Every stage of [compile] except rendering; the result carries no files. */
    fun check(
        sources: List<SourceInput>,
        targets: List<Target<*>>,
        strict: Boolean = false,
    ): PipelineResult =
        run(sources, targets, strict) { target, schema ->
            TargetResult(target.name, emptyList(), target.lower(schema).diagnostics)
        }

    private fun run(
        sources: List<SourceInput>,
        targets: List<Target<*>>,
        strict: Boolean,
        perTarget: (Target<*>, Schema) -> TargetResult,
    ): PipelineResult {
        val parsed = sources.map { Parser.parse(it.content, it.path) }
        val parseDiagnostics = parsed.flatMap { it.diagnostics }
        if (parseDiagnostics.hasErrors) return PipelineResult(parseDiagnostics, emptyList())

        val analyzed =
            Analyzer.analyze(
                parsed.map { it.file!! },
                AnalysisOptions(strictOrdinals = strict, annotations = annotations),
            )
        val core = parseDiagnostics + analyzed.diagnostics
        val schema = analyzed.schema ?: return PipelineResult(core, emptyList())
        return PipelineResult(core, targets.map { perTarget(it, schema) })
    }
}
