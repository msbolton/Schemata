package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.schemata.cli.report.JsonRenderer

class TargetsCommand : CliktCommand(name = "targets") {
    override fun help(context: Context) =
        "List the targets, the annotation keys each accepts, and the diagnostic codes each can emit."

    private val format: Format by
        option("--format", help = "human (default) or json")
            .choice("human" to Format.HUMAN, "json" to Format.JSON)
            .default(Format.HUMAN)

    override fun run() {
        when (format) {
            Format.JSON -> echo(JsonRenderer.targets(Pipeline.targets), trailingNewline = false)
            Format.HUMAN -> echo(human(), trailingNewline = false)
        }
    }

    private fun human(): String =
        Pipeline.targets.joinToString("\n") { target ->
            buildString {
                appendLine(target.name)
                appendLine("  annotations:")
                target.annotationSpecs.forEach { spec ->
                    val elements =
                        spec.elements.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
                    appendLine(
                        "  ${spec.key.padEnd(10)} ${spec.valueKind.name.lowercase().padEnd(10)} on $elements"
                    )
                }
                appendLine("  codes:")
                target.codes.forEach {
                    appendLine(
                        "  ${it.id}  ${it.severity.name.lowercase().padEnd(7)}  ${it.category.name.lowercase()}"
                    )
                }
            }
        }
}
