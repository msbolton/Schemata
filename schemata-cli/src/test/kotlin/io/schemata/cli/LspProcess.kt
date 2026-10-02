package io.schemata.cli

import io.schemata.testkit.LspSession
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * One editor session against `<command> lsp` as a child process: a diagnostic for an unknown type,
 * a definition across an import, a format request, then shutdown and exit. Fails if the process
 * writes anything to stderr or does not exit with 0.
 */
internal fun runLspSession(command: List<String>, examples: File) {
    val dir = Files.createTempDirectory("schemata-lsp").toFile()
    try {
        File(examples, "shop").copyRecursively(File(dir, "shop"))
        val orders = File(dir, "shop/orders.schemata")
        val customers = File(dir, "shop/customers.schemata")
        val text = orders.readText()
        val process = ProcessBuilder(command + "lsp").redirectErrorStream(false).start()
        var err = ""
        val drain = Thread { err = process.errorStream.bufferedReader().readText() }
        drain.start()
        val session = LspSession.overProcess(process)
        try {
            session.initialize(dir.toPath())
            session.open(orders.toPath(), text.replace("customer:  Customer", "customer:  Custmer"))
            val shown = session.diagnostics(orders.toPath()) { it.isNotEmpty() }
            // The misspelled name is unknown, and the import it no longer uses is reported too.
            assertEquals(setOf("SCH1006", "SCH1012"), shown.map { it.code.left }.toSet())
            session.change(orders.toPath(), text)
            session.diagnostics(orders.toPath()) { it.isEmpty() }

            val index = text.indexOf("customer:  Customer") + 12
            val line = text.substring(0, index).count { it == '\n' }
            val character = index - (text.lastIndexOf('\n', index - 1) + 1)
            val id = TextDocumentIdentifier(session.uri(orders.toPath()))
            val found =
                session.server.textDocumentService
                    .definition(DefinitionParams(id, Position(line, character)))
                    .get(30, TimeUnit.SECONDS)
            assertEquals(session.uri(customers.toPath()), found.left.single().uri)

            val edits =
                session.server.textDocumentService
                    .formatting(DocumentFormattingParams(id, FormattingOptions(2, true)))
                    .get(30, TimeUnit.SECONDS)
            assertEquals(emptyList(), edits)
        } finally {
            session.close()
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the server did not exit")
        drain.join(5000)
        assertEquals(0, process.exitValue(), err)
        assertEquals("", err)
    } finally {
        dir.deleteRecursively()
    }
}
