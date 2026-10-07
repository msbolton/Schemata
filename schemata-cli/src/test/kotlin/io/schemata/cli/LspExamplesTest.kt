package io.schemata.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.schemata.lsp.server.SchemataServer
import io.schemata.testkit.LspSession
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.io.TempDir

/** The server on the three examples, with every target's annotation keys known. */
class LspExamplesTest {
    @TempDir lateinit var dir: Path

    private fun copyExamples(): Path {
        File("../examples").canonicalFile.copyRecursively(dir.resolve("examples").toFile())
        return dir.resolve("examples")
    }

    private fun session(root: Path): LspSession {
        val session =
            LspSession.inProcess { input, output ->
                SchemataServer.launch(input, output, Pipeline.annotations) {}
            }
        session.initialize(root)
        return session
    }

    private fun position(text: String, needle: String): Position {
        val index = text.indexOf(needle)
        require(index >= 0) { "'$needle' not found" }
        val line = text.substring(0, index).count { it == '\n' }
        return Position(line, index - (text.lastIndexOf('\n', index - 1) + 1))
    }

    @Test
    fun `every example file opens with no diagnostics`() {
        val examples = copyExamples()
        session(examples).use { session ->
            val files =
                examples.toFile().walkTopDown().filter { it.extension == "schemata" }.toList()
            assertTrue(files.size >= 6, files.toString())
            files.forEach { session.open(it.toPath(), it.readText()) }
            files.forEach { file ->
                assertEquals(
                    emptyList(),
                    session.diagnostics(file.toPath()) { it.isEmpty() }.map { it.message },
                )
            }
        }
    }

    @Test
    fun `an edit shows a diagnostic and undoing it clears it`() {
        val examples = copyExamples()
        val orders = examples.resolve("shop/orders.schemata")
        val text = orders.toFile().readText()
        session(examples).use { session ->
            session.open(orders, text)
            session.diagnostics(orders) { it.isEmpty() }
            session.change(
                orders,
                text.replace("customer:  Customer", "customer:  Custmer"),
            )
            val shown = session.diagnostics(orders) { it.isNotEmpty() }
            // The misspelled name is unknown, and the import it no longer uses is reported too.
            assertEquals(setOf("SCH1006", "SCH1012"), shown.map { it.code.left }.toSet())
            session.change(orders, text)
            session.diagnostics(orders) { it.isEmpty() }
        }
    }

    @Test
    fun `go to definition crosses the import from orders to customers`() {
        val examples = copyExamples()
        val orders = examples.resolve("shop/orders.schemata")
        val customers = examples.resolve("shop/customers.schemata")
        val text = orders.toFile().readText()
        session(examples).use { session ->
            session.open(orders, text)
            val at =
                position(text, "customer:  Customer").let { Position(it.line, it.character + 12) }
            val found =
                session.server.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(session.uri(orders)), at))
                    .get(10, TimeUnit.SECONDS)
            val target = found.left.single()
            assertEquals(session.uri(customers), target.uri)
            assertEquals(position(customers.toFile().readText(), "Customer {"), target.range.start)
        }
    }

    /** [edits] applied to [text], last first so the earlier positions stay valid. */
    private fun apply(text: String, edits: List<TextEdit>): String {
        val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        fun offset(p: Position) = starts[p.line] + p.character
        return edits
            .sortedByDescending { offset(it.range.start) }
            .fold(text) { acc, edit ->
                acc.replaceRange(offset(edit.range.start), offset(edit.range.end), edit.newText)
            }
    }

    @Test
    fun `renaming Customer edits both shop files and the result analyses cleanly`() {
        val examples = copyExamples()
        val orders = examples.resolve("shop/orders.schemata")
        val customers = examples.resolve("shop/customers.schemata")
        val text = orders.toFile().readText()
        session(examples).use { session ->
            session.open(orders, text)
            session.diagnostics(orders) { it.isEmpty() }
            val at =
                position(text, "customer:  Customer").let { Position(it.line, it.character + 12) }
            val edit =
                session.server.textDocumentService
                    .rename(RenameParams(TextDocumentIdentifier(session.uri(orders)), at, "Client"))
                    .get(10, TimeUnit.SECONDS)
            assertEquals(setOf(session.uri(orders), session.uri(customers)), edit.changes.keys)
            assertEquals(1, edit.changes.getValue(session.uri(customers)).size)

            // The editor applies the edit: the open file through the buffer, the closed one on
            // disk, which its file watcher reports.
            val renamed = apply(text, edit.changes.getValue(session.uri(orders)))
            assertTrue("customer:  Client" in renamed, renamed)
            session.change(orders, renamed)
            val file = customers.toFile()
            file.writeText(apply(file.readText(), edit.changes.getValue(session.uri(customers))))
            assertTrue("record Client {" in file.readText(), file.readText())
            session.watched(customers, FileChangeType.Changed)
            session.settle(orders)
            assertEquals(emptyList(), session.diagnostics(orders) { it.isEmpty() })
            assertEquals(emptyList(), session.diagnostics(customers) { it.isEmpty() })
            val found =
                session.server.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(session.uri(orders)), at))
                    .get(10, TimeUnit.SECONDS)
            assertEquals(position(file.readText(), "Client {"), found.left.single().range.start)
        }
    }

    @Test
    fun `formatting an example changes nothing, as fmt --check agrees`() {
        val examples = copyExamples()
        val ledger = examples.resolve("ledger/journal.schemata")
        session(examples).use { session ->
            session.open(ledger, ledger.toFile().readText())
            val edits =
                session.server.textDocumentService
                    .formatting(
                        DocumentFormattingParams(
                            TextDocumentIdentifier(session.uri(ledger)),
                            FormattingOptions(2, true),
                        )
                    )
                    .get(10, TimeUnit.SECONDS)
            assertEquals(emptyList(), edits)
        }
    }

    @Test
    fun `the command is listed in the help`() {
        val result = Schemata().subcommands(LspCommand()).test("--help")
        assertTrue(result.stdout.contains("lsp"), result.stdout)
        assertTrue(
            result.stdout.contains("Run the language server on stdin and stdout"),
            result.stdout,
        )
    }
}
