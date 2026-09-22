package io.schemata.target

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.hasErrors

/**
 * Marker for a target's own model — the thing [Target.lower] produces and [Target.render] prints.
 */
interface TargetModel

data class Lowered<M : TargetModel>(val model: M, val diagnostics: List<Diagnostic>)

/** [path] is relative to the target's output root and uses forward slashes. */
data class OutputFile(val path: String, val content: String)

data class CompileOutput(val files: List<OutputFile>, val diagnostics: List<Diagnostic>)

/**
 * A backend in two stages. [lower] makes every representational decision and reports each lossy
 * one; [render] prints a model that is already legal. Test lowering on the model, never on rendered
 * text.
 */
interface Target<M : TargetModel> {
    val name: String

    /** The annotation keys this target accepts; the CLI unions every target's into one registry. */
    val annotationSpecs: List<AnnotationSpec>
        get() = emptyList()

    fun lower(schema: Schema): Lowered<M>

    fun render(model: M): List<OutputFile>

    /** Runs [lower] then [render]; render is skipped when lowering reports an error. */
    fun compile(schema: Schema): CompileOutput {
        val lowered = lower(schema)
        if (lowered.diagnostics.hasErrors) return CompileOutput(emptyList(), lowered.diagnostics)
        return CompileOutput(render(lowered.model), lowered.diagnostics)
    }
}
