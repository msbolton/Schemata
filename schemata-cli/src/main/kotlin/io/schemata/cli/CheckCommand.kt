package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources

class CheckCommand : CliktCommand(name = "check") {
    override fun help(context: Context) =
        "Report every diagnostic compile would, for every target, without writing files."

    private val targetNames by
        option(
                "--target",
                help =
                    "Comma-separated targets (default: all): ${Pipeline.targets.joinToString(", ") { it.name }}",
            )
            .split(",")

    private val reporting by ReportingOptions()

    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val targets = selectTargets(targetNames)
        val sources = loadSources(inputs)
        val result = Pipeline.check(sources, targets, strict = reporting.strict)
        val report = Report.of(result, strict = reporting.strict, checkOnly = true)
        emit(this, report, Sources.of(sources), reporting, out = "")
    }
}
