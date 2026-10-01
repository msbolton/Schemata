package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.Sources
import io.schemata.cli.report.importReport
import io.schemata.importer.xsd.ImportInput
import io.schemata.importer.xsd.XsdImporter
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

class ImportCommand : CliktCommand(name = "import") {
    override fun help(context: Context) = "Create .schemata files from an existing schema (XSD)."

    private val from by option("--from", help = "Source format: xsd").choice("xsd").required()
    private val out by
        option("--out", help = "Output directory (default: out)")
            .path(canBeFile = false)
            .default(Path("out"))
    private val namespace by
        option(
            "--namespace",
            help = "Namespace for a single input file whose targetNamespace is not urn:schemata:",
        )
    private val reporting by ReportingOptions()
    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val files = XsdSet.load(inputs)
        if (files.isEmpty()) {
            throw UsageError("no .xsd files found under: ${inputs.joinToString(", ")}")
        }
        if (namespace != null && files.size != 1) {
            throw UsageError("--namespace applies to a single input file")
        }
        val result =
            XsdImporter.import(files.map { ImportInput(it.path, it.content) }, namespace) { relative
                ->
                locate(files, relative)
            }
        val report = importReport(result, reporting.strict)
        if (report.errors == 0) {
            result.files.forEach { write(out.resolve("import").resolve(it.path), it.content) }
        }
        emit(this, report, Sources.of(files), reporting, out.toString())
    }
}

private fun write(destination: Path, content: String) {
    try {
        destination.createParentDirectories()
        destination.writeText(content)
    } catch (e: IOException) {
        throw CliktError("cannot write $destination: ${e.message}")
    }
}
