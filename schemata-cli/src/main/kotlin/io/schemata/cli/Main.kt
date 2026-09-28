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

/** The build's version as the jar manifest recorded it; `unknown` when not run from a jar. */
object Version {
    val current: String = Schemata::class.java.`package`?.implementationVersion ?: "unknown"
}

fun main(args: Array<String>) =
    Schemata().subcommands(CompileCommand(), CheckCommand(), TargetsCommand()).main(args)
