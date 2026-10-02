package io.schemata.lsp.server

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import io.schemata.testkit.LspSession
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.io.TempDir

class ServerSessionTest {
    @TempDir lateinit var dir: Path

    private val customers = "namespace shop.customers\n\nrecord Customer { #1 id: uuid }\n"
    private val orders =
        "namespace shop.orders\n\nimport shop.customers\n\nrecord Order { #1 who: Customer }\n"

    private fun write(relative: String, text: String): Path {
        val path = dir.resolve(relative)
        path.parent.createDirectories()
        path.writeText(text)
        return path
    }

    private fun session(options: Map<String, Any?> = emptyMap()): LspSession {
        val session =
            LspSession.inProcess { input, output ->
                SchemataServer.launch(input, output, AnnotationRegistry.CORE) {}
            }
        session.initialize(dir, options)
        return session
    }

    private fun <T> wait(future: java.util.concurrent.CompletableFuture<T>): T =
        future.get(10, TimeUnit.SECONDS)

    private fun id(session: LspSession, path: Path) = TextDocumentIdentifier(session.uri(path))

    @Test
    fun `initialize advertises full sync and exactly the providers the server has`() {
        LspSession.inProcess { input, output ->
                SchemataServer.launch(input, output, AnnotationRegistry.CORE) {}
            }
            .use { session ->
                val caps = session.initialize(dir).capabilities
                assertEquals(TextDocumentSyncKind.Full, caps.textDocumentSync.left)
                assertEquals(true, caps.definitionProvider.left)
                assertEquals(true, caps.hoverProvider.left)
                assertEquals(true, caps.referencesProvider.left)
                assertEquals(true, caps.renameProvider.right.prepareProvider)
                assertEquals(true, caps.documentFormattingProvider.left)
                assertEquals(true, caps.documentSymbolProvider.left)
                assertNull(caps.completionProvider)
                assertNull(caps.codeActionProvider)
                assertNull(caps.documentRangeFormattingProvider)
                assertNull(caps.workspaceSymbolProvider)
            }
    }

    @Test
    fun `an error is published with its code, severity, source, and help`() {
        val a = write("m/a.schemata", "namespace m\n\nrecord R { #1 x: Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            val only = session.diagnostics(a) { it.isNotEmpty() }.single()
            assertEquals("SCH1006", only.code.left)
            assertEquals(DiagnosticSeverity.Error, only.severity)
            assertEquals("schemata", only.source)
            assertTrue(only.message.startsWith("unknown type 'Missing'\n\nhelp: "), only.message)
            assertEquals(Range(Position(2, 17), Position(2, 24)), only.range)
        }
    }

    @Test
    fun `diagnostics clear when the text is fixed`() {
        val a = write("m/a.schemata", "namespace m\n\nrecord R { #1 x: Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
            session.change(a, "namespace m\n\nrecord R { #1 x: int32 }\n")
            session.diagnostics(a) { it.isEmpty() }
        }
    }

    @Test
    fun `a sibling that stops parsing does not raise errors in the file importing it`() {
        val c = write("shop/customers.schemata", customers)
        val o = write("shop/orders.schemata", orders)
        session().use { session ->
            session.open(c, customers)
            session.open(o, orders)
            session.diagnostics(o) { it.isEmpty() }
            val before = session.publishCount(o)
            session.change(c, "namespace shop.customers\n\nrecord Customer {")
            session.diagnostics(c) { it.isNotEmpty() }
            session.diagnostics(o) { session.publishCount(o) > before && it.isEmpty() }
        }
    }

    @Test
    fun `definition crosses files and answers from an edit made a moment ago`() {
        val c = write("shop/customers.schemata", customers)
        val o = write("shop/orders.schemata", orders)
        session().use { session ->
            session.open(o, orders)
            session.change(
                o,
                "namespace shop.orders\n\nimport shop.customers\n\n" +
                    "record Order { #1 who: Customer }\n\nrecord Extra { #1 again: Customer }\n",
            )
            val found =
                wait(
                    session.server.textDocumentService.definition(
                        DefinitionParams(id(session, o), Position(6, 27))
                    )
                )
            val location = found.left.single()
            assertEquals(session.uri(c), location.uri)
            assertEquals(Range(Position(2, 7), Position(2, 15)), location.range)
        }
    }

    @Test
    fun `references, hover, and symbols answer over the protocol`() {
        val c = write("shop/customers.schemata", customers)
        val o = write("shop/orders.schemata", orders)
        session().use { session ->
            session.open(c, customers)
            session.open(o, orders)
            val service = session.server.textDocumentService
            val refs =
                wait(
                    service.references(
                        ReferenceParams(id(session, c), Position(2, 8), ReferenceContext(true))
                    )
                )
            assertEquals(listOf(session.uri(c), session.uri(o)), refs.map { it.uri })
            val hover = wait(service.hover(HoverParams(id(session, o), Position(4, 24))))
            assertEquals("markdown", hover.contents.right.kind)
            assertEquals(
                "```schemata\nrecord shop.customers.Customer\n```",
                hover.contents.right.value,
            )
            assertNull(wait(service.hover(HoverParams(id(session, o), Position(4, 1)))))
            val symbols = wait(service.documentSymbol(DocumentSymbolParams(id(session, o))))
            val namespace = symbols.single().right
            assertEquals(SymbolKind.Namespace, namespace.kind)
            assertEquals(listOf("Order"), namespace.children.map { it.name })
            assertEquals(SymbolKind.Struct, namespace.children.single().kind)
            assertEquals(SymbolKind.Field, namespace.children.single().children.single().kind)
        }
    }

    @Test
    fun `rename returns edits for both files and refuses a bad name with a message`() {
        val c = write("shop/customers.schemata", customers)
        val o = write("shop/orders.schemata", orders)
        session().use { session ->
            session.open(c, customers)
            session.open(o, orders)
            val service = session.server.textDocumentService
            val edit = wait(service.rename(RenameParams(id(session, o), Position(4, 24), "Client")))
            assertEquals(setOf(session.uri(c), session.uri(o)), edit.changes.keys)
            assertEquals("Client", edit.changes.getValue(session.uri(c)).single().newText)
            val failure =
                assertFailsWith<ExecutionException> {
                    wait(service.rename(RenameParams(id(session, o), Position(4, 24), "record")))
                }
            val cause = failure.cause as ResponseErrorException
            assertEquals("'record' is a keyword", cause.responseError.message)
        }
    }

    @Test
    fun `formatting returns one whole-document edit and nothing when already formatted`() {
        val messy = "namespace m\nrecord   R {   #1 x:int32 }\n"
        val a = write("m/a.schemata", messy)
        val formatted = (Formatter.format(messy, "a.schemata") as FormatResult.Formatted).text
        session().use { session ->
            session.open(a, messy)
            val service = session.server.textDocumentService
            val params = DocumentFormattingParams(id(session, a), FormattingOptions(8, false))
            val edit = wait(service.formatting(params)).single()
            assertEquals(formatted, edit.newText)
            assertEquals(Range(Position(0, 0), Position(2, 0)), edit.range)
            session.change(a, formatted)
            assertEquals(emptyList(), wait(service.formatting(params)))
            session.change(a, "namespace m\nrecord R {")
            assertEquals(emptyList(), wait(service.formatting(params)))
        }
    }

    @Test
    fun `a document that is not a file is ignored and the server keeps answering`() {
        val a = write("m/a.schemata", "namespace m\n\nrecord R { #1 x: int32 }\n")
        session().use { session ->
            session.openUri("untitled:Untitled-1", "namespace u\nrecord U { #1 x: Missing }\n")
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isEmpty() }
            assertEquals(0, session.publishCountUri("untitled:Untitled-1"))
            val hover =
                wait(
                    session.server.textDocumentService.hover(
                        HoverParams(TextDocumentIdentifier("untitled:Untitled-1"), Position(1, 8))
                    )
                )
            assertNull(hover)
            assertEquals(emptyList(), session.logged())
        }
    }

    @Test
    fun `a path with a space publishes under the uri the editor sent`() {
        val a = write("my shop/a.schemata", "namespace m\n\nrecord R { #1 x: Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            assertEquals(1, session.diagnostics(a) { it.isNotEmpty() }.size)
        }
    }

    @Test
    fun `a file deleted on disk has its diagnostics cleared`() {
        val c =
            write(
                "shop/customers.schemata",
                "namespace shop.customers\n\nrecord C { #1 x: Nope }\n",
            )
        val o = write("shop/orders.schemata", "namespace shop.orders\n\nrecord O { #1 x: int32 }\n")
        session().use { session ->
            session.open(o, o.toFile().readText())
            session.diagnostics(c) { it.isNotEmpty() }
            c.deleteExisting()
            session.watched(c, FileChangeType.Deleted)
            session.diagnostics(c) { it.isEmpty() }
        }
    }

    @Test
    fun `roots from the initialization options gather a subtree into one set`() {
        val c = write("model/customers/c.schemata", customers)
        val o = write("model/orders/o.schemata", orders)
        session(mapOf("roots" to listOf("model"), "strict" to false)).use { session ->
            session.open(o, orders)
            session.diagnostics(o) { it.isEmpty() }
            session.diagnostics(c) { it.isEmpty() }
        }
    }

    @Test
    fun `strict from the initialization options reports implicit ordinals`() {
        val a = write("m/a.schemata", "namespace m\n\nrecord R { x: int32 }\n")
        session(mapOf("strict" to true)).use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
        }
    }
}
