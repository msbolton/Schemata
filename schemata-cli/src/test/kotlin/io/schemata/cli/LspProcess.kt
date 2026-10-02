package io.schemata.cli

import io.schemata.testkit.LspSession
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

/**
 * One editor session against `<command> lsp` as a child process, touching every message shape the
 * server sends: the watcher registration, a diagnostic for an unknown type, a definition across an
 * import, hover, the outline, references, a rename and a refused one, a format request, a deleted
 * file's empty publish, then shutdown and exit. Fails if the process writes anything to stderr or
 * does not exit with 0.
 */
internal fun runLspSession(command: List<String>, examples: File) {
    val dir = Files.createTempDirectory("schemata-lsp").toFile()
    try {
        File(examples, "shop").copyRecursively(File(dir, "shop"))
        val orders = File(dir, "shop/orders.schemata")
        val customers = File(dir, "shop/customers.schemata")
        val extra = File(dir, "shop/extra.schemata")
        extra.writeText("namespace shop.extra\n\nrecord Extra { #1 x: Missing }\n")
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
            assertEquals(listOf("workspace/didChangeWatchedFiles"), session.registrations())

            val service = session.server.textDocumentService
            val at = Position(line, character)
            val hover = service.hover(HoverParams(id, at)).get(30, TimeUnit.SECONDS)
            assertTrue(
                hover.contents.right.value.contains("record shop.customers.Customer"),
                hover.contents.right.value,
            )
            val outline = service.documentSymbol(DocumentSymbolParams(id)).get(30, TimeUnit.SECONDS)
            val namespace = outline.single().right
            assertEquals("shop.orders", namespace.name)
            assertTrue(namespace.children.any { it.name == "Order" })
            val references =
                service
                    .references(ReferenceParams(id, at, ReferenceContext(true)))
                    .get(30, TimeUnit.SECONDS)
            assertEquals(
                setOf(session.uri(orders.toPath()), session.uri(customers.toPath())),
                references.map { it.uri }.toSet(),
            )
            val renamed = service.rename(RenameParams(id, at, "Client")).get(30, TimeUnit.SECONDS)
            assertEquals(
                setOf(session.uri(orders.toPath()), session.uri(customers.toPath())),
                renamed.changes.keys,
            )
            val refused =
                assertFailsWith<ExecutionException> {
                    service.rename(RenameParams(id, at, "string")).get(30, TimeUnit.SECONDS)
                }
            assertEquals(
                "'string' is a builtin type name",
                (refused.cause as ResponseErrorException).responseError.message,
            )

            val edits =
                session.server.textDocumentService
                    .formatting(DocumentFormattingParams(id, FormattingOptions(2, true)))
                    .get(30, TimeUnit.SECONDS)
            assertEquals(emptyList(), edits)

            session.diagnostics(extra.toPath()) { it.isNotEmpty() }
            extra.delete()
            session.watched(extra.toPath(), FileChangeType.Deleted)
            session.diagnostics(extra.toPath()) { it.isEmpty() }
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
