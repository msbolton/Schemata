package io.schemata.cli

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.hasErrors
import io.schemata.target.OutputFile
import io.schemata.target.Target
import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.target.openapi.OpenApiTarget
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
 * Parse and analysis only, no target. [implicitOrdinals] names every declaration and service (by
 * its parsed AST, before analysis assigns stand-in ordinals) that has a field, enum value, union
 * member, or operation with no explicit `#n`; [schema] is null exactly when [diagnostics] contains
 * an error.
 */
data class Analyzed(
    val schema: Schema?,
    val diagnostics: List<Diagnostic>,
    val implicitOrdinals: Set<QualifiedName>,
)

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
    val targets: List<Target<*>> =
        listOf(ProtoTarget, SqlTarget, XsdTarget, JsonSchemaTarget, OpenApiTarget)

    /**
     * Core's keys plus every target's, whatever `--target` selects: validity never depends on the
     * emitters chosen.
     */
    val annotations: AnnotationRegistry =
        AnnotationRegistry(CoreAnnotations.specs + targets.flatMap { it.annotationSpecs })

    fun targetNamed(name: String): Target<*>? = targets.firstOrNull { it.name == name }

    /** Parses and analyses [sources], same as every stage before a target sees the schema. */
    fun analyze(sources: List<SourceInput>, strict: Boolean = false): Analyzed {
        val parsed = sources.map { Parser.parse(it.content, it.path) }
        val parseDiagnostics = parsed.flatMap { it.diagnostics }
        if (parseDiagnostics.hasErrors) return Analyzed(null, parseDiagnostics, emptySet())
        val files = parsed.map { it.file!! }
        val analyzed =
            Analyzer.analyze(
                files,
                AnalysisOptions(strictOrdinals = strict, annotations = annotations),
            )
        return Analyzed(
            analyzed.schema,
            parseDiagnostics + analyzed.diagnostics,
            implicitOrdinals(files),
        )
    }

    private fun implicitOrdinals(files: List<SourceFile>): Set<QualifiedName> {
        val out = mutableSetOf<QualifiedName>()
        files.forEach { file ->
            file.declarations.forEach { collect(it, file.namespace.name, emptyList(), out) }
            file.services
                .filter { s -> s.operations.any { it.ordinal == null } }
                .forEach { out += QualifiedName(file.namespace.name, listOf(it.name)) }
        }
        return out
    }

    private fun collect(
        decl: Declaration,
        namespace: String,
        path: List<String>,
        out: MutableSet<QualifiedName>,
    ) {
        when (decl) {
            is RecordDecl -> {
                if (decl.fields.any { it.ordinal == null })
                    out += QualifiedName(namespace, path + decl.name)
                decl.nested.forEach { collect(it, namespace, path + decl.name, out) }
            }
            is EnumDecl ->
                if (decl.values.any { it.ordinal == null })
                    out += QualifiedName(namespace, path + decl.name)
            is UnionDecl ->
                if (decl.members.any { it.ordinal == null })
                    out += QualifiedName(namespace, path + decl.name)
            is AliasDecl -> Unit
        }
    }

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
        val analyzed = analyze(sources, strict)
        val schema = analyzed.schema ?: return PipelineResult(analyzed.diagnostics, emptyList())
        return PipelineResult(analyzed.diagnostics, targets.map { perTarget(it, schema) })
    }
}
