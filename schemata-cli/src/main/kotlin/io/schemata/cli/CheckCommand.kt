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

    private val protoLayout by protoLayoutOption()

    private val reporting by ReportingOptions()

    private val configuration by ConfigOptions()

    private val inputs by argument("PATHS").path(mustExist = true).multiple()

    override fun run() {
        val config = configuration.resolve(this, reporting)
        val style = reporting.resolve(config)
        val strict = compileStrict(reporting.strict, config)
        val targets = compileTargets(targetNames, protoLayout, config)
        val sources = loadSources(inputPaths(inputs, config), config)
        val result = Pipeline.check(sources, targets, strict, config)
        val report = Report.of(result, strict = strict, checkOnly = true)
        emit(this, report, reportSources(sources, config), style, out = "")
    }
}
