package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportNames
import io.schemata.importer.Importer
import io.schemata.importer.proto.ProtoImporter
import io.schemata.importer.sql.SqlImporter
import io.schemata.importer.xsd.XsdImporter
import kotlin.io.path.Path

class ImportCommand : CliktCommand(name = "import") {
    override fun help(context: Context) =
        "Create .schemata files from an existing schema (XSD, Protobuf, or Postgres DDL)."

    private val from by
        option("--from", help = "Source format: xsd, proto, sql")
            .choice("xsd", "proto", "sql")
            .required()
    private val out by
        option("--out", help = "Output directory (default: out)")
            .path(canBeFile = false)
            .default(Path("out"))
    private val namespace by
        option(
            "--namespace",
            help =
                "Schema name for a single input file: one whose XSD targetNamespace is not " +
                    "urn:schemata:, or whose proto package or SQL schema is not a schema name",
        )
    private val reporting by ReportingOptions()
    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val (importer: Importer, files) =
            when (from) {
                "proto" -> ProtoImporter to ProtoSet.load(inputs)
                "sql" -> SqlImporter to SqlSet.load(inputs)
                else -> XsdImporter to XsdSet.load(inputs)
            }
        if (files.isEmpty()) {
            throw usageError("no .$from files found under: ${inputs.joinToString(", ")}")
        }
        namespace?.let { ns ->
            if (!ns.split('.').all(ImportNames::isNamespaceSegment)) {
                throw usageError("--namespace must be dotted lower-snake segments")
            }
        }
        if (namespace != null && files.size != 1) {
            throw usageError("--namespace applies to a single input file")
        }
        val result =
            importer.import(
                files.map { ImportInput(it.path, it.content, it.relative) },
                namespace,
                ::locate,
            )
        val report = importReport(result, reporting.strict)
        if (report.errors == 0) {
            result.files.forEach { writeOutput(out.resolve("import").resolve(it.path), it.content) }
        }
        emit(this, report, Sources.of(files), reporting, out.toString())
    }
}
