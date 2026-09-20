package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class Schemata : CliktCommand(name = "schemata") {
    override fun run() = Unit
}

fun main(args: Array<String>) = Schemata().subcommands(CompileCommand()).main(args)
