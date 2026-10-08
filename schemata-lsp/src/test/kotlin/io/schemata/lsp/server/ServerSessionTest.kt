package io.schemata.lsp.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import io.schemata.testkit.LspSession
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
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
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.io.TempDir

class ServerSessionTest {
    @TempDir lateinit var dir: Path

    private val customers = "schema shop.customers\n\nmodel Customer { #1 id uuid }\n"
    private val orders =
        "schema shop.orders\n\nimport shop.customers\n\nmodel Order { #1 who Customer }\n"

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

    private fun <T> wait(future: CompletableFuture<T>): T = future.get(10, TimeUnit.SECONDS)

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
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            val only = session.diagnostics(a) { it.isNotEmpty() }.single()
            assertEquals("SCH1006", only.code.left)
            assertEquals(DiagnosticSeverity.Error, only.severity)
            assertEquals("schemata", only.source)
            assertTrue(only.message.startsWith("unknown type 'Missing'\n\nhelp: "), only.message)
            assertEquals(Range(Position(2, 15), Position(2, 22)), only.range)
        }
    }

    @Test
    fun `diagnostics clear when the text is fixed`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
            session.change(a, "schema m\n\nmodel R { #1 x int32 }\n")
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
            session.change(c, "schema shop.customers\n\nmodel Customer {")
            session.diagnostics(c) { it.isNotEmpty() }
            session.settle(o)
            assertEquals(emptyList(), session.latest(o))
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
                "schema shop.orders\n\nimport shop.customers\n\n" +
                    "model Order { #1 who Customer }\n\nmodel Extra { #1 again Customer }\n",
            )
            val found =
                wait(
                    session.server.textDocumentService.definition(
                        DefinitionParams(id(session, o), Position(6, 27))
                    )
                )
            val location = found.left.single()
            assertEquals(session.uri(c), location.uri)
            assertEquals(Range(Position(2, 6), Position(2, 14)), location.range)
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
                "```schemata\nmodel shop.customers.Customer\n```",
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
    fun `a service is an interface whose operations are methods with their payloads as detail`() {
        val text = "schema m\n\nmodel A { #1 x int32 }\n\nservice S { #1 get(A): A }\n"
        val a = write("m/a.schemata", text)
        session().use { session ->
            session.open(a, text)
            val symbols =
                wait(
                    session.server.textDocumentService.documentSymbol(
                        DocumentSymbolParams(id(session, a))
                    )
                )
            val service = symbols.single().right.children.single { it.name == "S" }
            assertEquals(SymbolKind.Interface, service.kind)
            val get = service.children.single()
            assertEquals(
                listOf("get", SymbolKind.Method, "(A): A"),
                listOf(get.name, get.kind, get.detail),
            )
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
                    wait(service.rename(RenameParams(id(session, o), Position(4, 24), "model")))
                }
            val cause = failure.cause as ResponseErrorException
            assertEquals("'model' is a keyword", cause.responseError.message)
        }
    }

    @Test
    fun `formatting returns one whole-document edit and nothing when already formatted`() {
        val messy = "schema m\nmodel   R {   #1 x  int32 }\n"
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
            session.change(a, "schema m\nmodel R {")
            assertEquals(emptyList(), wait(service.formatting(params)))
            session.change(a, formatted.replace("\n", "\r\n"))
            assertEquals(emptyList(), wait(service.formatting(params)))
        }
    }

    @Test
    fun `a document that is not a file is ignored and the server keeps answering`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x int32 }\n")
        session().use { session ->
            session.openUri("untitled:Untitled-1", "schema u\nmodel U { #1 x Missing }\n")
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
        val a = write("my shop/a.schemata", "schema m\n\nmodel R { #1 x Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            assertEquals(1, session.diagnostics(a) { it.isNotEmpty() }.size)
        }
    }

    @Test
    fun `a file deleted on disk has its diagnostics cleared`() {
        val c = write("shop/customers.schemata", "schema shop.customers\n\nmodel C { #1 x Nope }\n")
        val o = write("shop/orders.schemata", "schema shop.orders\n\nmodel O { #1 x int32 }\n")
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
    fun `the server registers a watcher for schema files`() {
        session().use { session ->
            val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x int32 }\n")
            session.open(a, a.toFile().readText())
            session.settle(a)
            assertEquals(listOf("workspace/didChangeWatchedFiles"), session.registrations())
        }
    }

    @Test
    fun `an edit that leaves the diagnostics as they were publishes nothing`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x Missing }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
            val before = session.publishCount(a)
            session.change(a, "schema m\n\nmodel R { #1 x Missing }\n\n")
            session.settle(a)
            assertEquals(before, session.publishCount(a))
            session.change(a, "schema m\n\nmodel R { #1 x int32 }\n")
            session.diagnostics(a) { it.isEmpty() }
        }
    }

    @Test
    fun `a file closed and then deleted has its diagnostics cleared`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x Missing }\n")
        val b = write("m/b.schemata", "schema m\n\nmodel S { #1 x int32 }\n")
        session().use { session ->
            session.open(b, b.toFile().readText())
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
            a.deleteExisting()
            session.close(a)
            session.diagnostics(a) { it.isEmpty() }
        }
    }

    @Test
    fun `a closed file that leaves its set when the roots change has its diagnostics cleared`() {
        val c = write("model/customers/c.schemata", "schema c\n\nmodel C { #1 x Nope }\n")
        val o = write("model/orders/o.schemata", "schema o\n\nmodel O { #1 x int32 }\n")
        session(mapOf("roots" to listOf("model"))).use { session ->
            session.open(o, o.toFile().readText())
            session.diagnostics(c) { it.isNotEmpty() }
            configure(session, JsonObject().apply { add("schemata", roots()) })
            session.diagnostics(c) { it.isEmpty() }
        }
    }

    private fun roots(vararg roots: String) =
        JsonObject().apply { add("roots", JsonArray().apply { roots.forEach(::add) }) }

    private fun configure(session: LspSession, settings: JsonObject) {
        session.server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(settings)
        )
    }

    @Test
    fun `settings with no schemata section and a section with no strict keep strict on`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { x int32 }\n")
        session(mapOf("strict" to true)).use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
            configure(session, JsonObject())
            configure(session, JsonObject().apply { add("schemata", roots()) })
            session.change(a, "schema m\n\nmodel R { y int32 }\n")
            session.diagnostics(a) { it.isNotEmpty() && "'y'" in it.single().message }
        }
    }

    @Test
    fun `roots resolve against the root uri when the client sends no folders`() {
        val c = write("model/customers/c.schemata", "schema c\n\nmodel C { #1 x Nope }\n")
        val o = write("model/orders/o.schemata", "schema o\n\nmodel O { #1 x int32 }\n")
        bare().use { session ->
            initializeWithout(session, rootUri = session.uri(dir))
            session.open(o, o.toFile().readText())
            session.diagnostics(c) { it.isNotEmpty() }
        }
    }

    @Test
    fun `relative roots are ignored when there is no folder at all`() {
        val c = write("model/customers/c.schemata", "schema c\n\nmodel C { #1 x Nope }\n")
        val o = write("model/orders/o.schemata", "schema o\n\nmodel O { #1 x int32 }\n")
        bare().use { session ->
            initializeWithout(session, rootUri = null)
            session.open(o, o.toFile().readText())
            session.diagnostics(o) { it.isEmpty() }
            session.settle(o)
            assertEquals(0, session.publishCount(c))
        }
    }

    private fun bare() =
        LspSession.inProcess { input, output ->
            SchemataServer.launch(input, output, AnnotationRegistry.CORE) {}
        }

    /** Initializes with `roots: ["model"]` and no workspace folders. */
    private fun initializeWithout(session: LspSession, rootUri: String?) {
        val params =
            InitializeParams().apply {
                @Suppress("DEPRECATION")
                this.rootUri = rootUri
                initializationOptions = mapOf("roots" to listOf("model"))
            }
        wait(session.server.initialize(params))
        session.server.initialized(InitializedParams())
    }

    @Test
    fun `shutdown answers null and a request after it is invalid`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x int32 }\n")
        val session = bare()
        session.initialize(dir)
        session.open(a, a.toFile().readText())
        assertNull(wait(session.server.shutdown()))
        val failure =
            assertFailsWith<ExecutionException> {
                wait(
                    session.server.textDocumentService.hover(
                        HoverParams(id(session, a), Position(2, 8))
                    )
                )
            }
        val cause = failure.cause as ResponseErrorException
        assertEquals(ResponseErrorCode.InvalidRequest.value, cause.responseError.code)
        // Closing the session asks for shutdown again, which is a request after shutdown too.
        assertFailsWith<ExecutionException> { session.close() }
    }

    @Test
    fun `a handler failure is logged with its stack and the server keeps answering`() {
        // Deep enough that parsing it overflows the stack: the server reads it from disk while it
        // analyses the set, inside the request.
        val depth = 20_000
        write(
            "m/deep.schemata",
            "schema m\n\nmodel D { #1 x: " +
                "list<".repeat(depth) +
                "int32" +
                ">".repeat(depth) +
                " }\n",
        )
        val a = write("m/a.schemata", "schema m\n\nmodel R { #1 x int32 }\n")
        session().use { session ->
            session.open(a, a.toFile().readText())
            // The failure surfaces in whichever analysis runs first: this request's, or the one
            // the pause after didOpen schedules. Either way it is logged before the answer arrives.
            wait(
                session.server.textDocumentService.hover(
                    HoverParams(id(session, a), Position(2, 8))
                )
            )
            val logged = session.logged()
            assertTrue(logged.isNotEmpty())
            assertTrue(logged.all { it.contains("StackOverflowError") }, logged.first().take(200))
            assertTrue(logged.all { it.contains("\tat ") }, logged.first().take(200))
            val symbols =
                wait(
                    session.server.textDocumentService.documentSymbol(
                        DocumentSymbolParams(id(session, a))
                    )
                )
            assertEquals(listOf("m"), symbols.map { it.right.name })
        }
    }

    @Test
    fun `a reopened file is published again even when nothing changed`() {
        val text = "schema m\n\nmodel R { #1 x Missing }\n"
        val a = write("m/a.schemata", text)
        session().use { session ->
            session.open(a, text)
            session.diagnostics(a) { it.isNotEmpty() }
            val before = session.publishCount(a)
            session.close(a)
            session.open(a, text)
            session.diagnostics(a) { session.publishCount(a) > before && it.isNotEmpty() }
        }
    }

    @Test
    fun `serving ends with 1 when the input closes before shutdown and 0 after it`() {
        assertEquals(1, serveUntilClosed(shutdownFirst = false))
        assertEquals(0, serveUntilClosed(shutdownFirst = true))
    }

    private fun serveUntilClosed(shutdownFirst: Boolean): Int {
        val code = CompletableFuture<Int>()
        val serverIn = PipedInputStream(1 shl 16)
        val clientOut = PipedOutputStream(serverIn)
        val clientIn = PipedInputStream(1 shl 16)
        val serverOut = PipedOutputStream(clientIn)
        Thread {
                code.complete(SchemataServer.serve(serverIn, serverOut, AnnotationRegistry.CORE) {})
            }
            .start()
        val client =
            LSPLauncher.createClientLauncher(
                object : LanguageClient {
                    override fun telemetryEvent(value: Any?) = Unit

                    override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit

                    override fun showMessage(params: MessageParams) = Unit

                    override fun showMessageRequest(params: ShowMessageRequestParams) =
                        CompletableFuture.completedFuture<MessageActionItem>(null)

                    override fun logMessage(params: MessageParams) = Unit
                },
                clientIn,
                clientOut,
            )
        client.startListening()
        wait(client.remoteProxy.initialize(InitializeParams()))
        if (shutdownFirst) wait(client.remoteProxy.shutdown())
        clientOut.close()
        return code.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `strict from the initialization options reports implicit ordinals`() {
        val a = write("m/a.schemata", "schema m\n\nmodel R { x int32 }\n")
        session(mapOf("strict" to true)).use { session ->
            session.open(a, a.toFile().readText())
            session.diagnostics(a) { it.isNotEmpty() }
        }
    }
}
