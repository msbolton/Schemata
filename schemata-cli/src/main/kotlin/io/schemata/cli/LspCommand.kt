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
        // The same registry and target names as the CLI, so schemata.toml is read the same way.
        val targetNames = Pipeline.targets.map { it.name }
        // Input that ends without `exit` is a clean stop only if the client asked for shutdown.
        exitProcess(
            SchemataServer.serve(System.`in`, protocol, Pipeline.annotations, targetNames) {
                exitProcess(it)
            }
        )
    }
}
