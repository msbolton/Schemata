package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

class Schemata : CliktCommand(name = "schemata") {
    init {
        versionOption(Version.current, names = setOf("--version")) { "schemata $it" }
    }

    override fun run() = Unit
}

/** The build's version, compiled in so a native image reports it like the jar does. */
object Version {
    val current: String = BuildVersion.VERSION
}

fun main(args: Array<String>) =
    Schemata()
        .subcommands(
            CompileCommand(),
            CheckCommand(),
            FmtCommand(),
            TargetsCommand(),
            ImportCommand(),
            DiffCommand(),
        )
        .main(args)
