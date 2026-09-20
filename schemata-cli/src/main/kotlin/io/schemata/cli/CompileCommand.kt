package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CompileCommand : CliktCommand(name = "compile") {
    override fun help(context: Context) = "Compile a .schemata file to one or more targets."

    private val targetNames by
        option(
                "--target",
                help = "Comma-separated targets: ${Pipeline.targets.joinToString(", ") { it.name }}",
            )
            .split(",")
            .required()

    private val out by
        option("--out", help = "Output directory (default: out)")
            .path(canBeFile = false)
            .default(Path("out"))

    private val input by argument("FILE").path(mustExist = true, canBeDir = false)

    override fun run() {
        val targets =
            targetNames.map { name ->
                Pipeline.targetNamed(name)
                    ?: throw UsageError(
                        "unknown target '$name'; available: ${Pipeline.targets.joinToString(", ") { it.name }}"
                    )
            }

        val result = Pipeline.compile(input.readText(), targets)
        result.diagnostics.forEach { echo(format(it, input), err = true) }
        if (result.hasErrors) throw ProgramResult(1)

        result.files.forEach { (target, file) ->
            val destination = out.resolve(target).resolve(file.path)
            destination.createParentDirectories()
            destination.writeText(file.content)
            echo("wrote $destination")
        }
    }

    private fun format(d: Diagnostic, file: Path): String {
        val kind =
            when (d.category) {
                Category.LOSSY -> "warning (lossy)"
                else -> d.severity.name.lowercase()
            }
        val where = d.span?.let { "$file:${it.startLine}:${it.startColumn}" } ?: "$file"
        return "$kind: $where: ${d.message}"
    }
}
