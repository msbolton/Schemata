package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.schemata.lsp.server.SchemataServer
import kotlin.system.exitProcess

class LspCommand : CliktCommand(name = "lsp") {
    override fun help(context: Context) =
        "Run the language server on stdin and stdout, for an editor to start."

    override fun run() {
        // The protocol owns stdout; anything else that prints goes to stderr instead.
        val protocol = System.out
        System.setOut(System.err)
        SchemataServer.launch(System.`in`, protocol, Pipeline.annotations) { exitProcess(it) }.get()
    }
}
