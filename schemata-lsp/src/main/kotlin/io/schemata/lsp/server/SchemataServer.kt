package io.schemata.lsp.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import io.schemata.lsp.workspace.LineIndex
import io.schemata.lsp.workspace.Queries
import io.schemata.lsp.workspace.RenameResult
import io.schemata.lsp.workspace.SetKey
import io.schemata.lsp.workspace.Workspace
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

/**
 * The protocol face of the workspace. Every notification and request runs on one thread, in arrival
 * order, so the workspace never sees two callers at once. An edit schedules its set's diagnostics
 * after a short pause; a request that arrives during the pause publishes first, so it answers from
 * the text the editor already holds. Only files whose diagnostics changed are published again.
 */
class SchemataServer(annotations: AnnotationRegistry, private val onExit: (Int) -> Unit) :
    LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {
    private val workspace = Workspace(annotations)
    private val queries = Queries(workspace)
    private val executor =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "schemata-lsp").apply { isDaemon = true }
        }
    private val pending = mutableMapOf<SetKey, ScheduledFuture<*>>()

    /** The URI the editor used for each path, so publishes name files the way it does. */
    private val uris = mutableMapOf<String, String>()

    /** What was last published for each path; a publish goes out only when it differs. */
    private val published = mutableMapOf<String, List<Diagnostic>>()
    private var client: LanguageClient? = null
    private var folder: Path? = null
    private var watchFiles = false
    private var roots: List<String> = emptyList()
    private var strict = false
    @Volatile private var shutdownRequested = false

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun getTextDocumentService(): TextDocumentService = this

    override fun getWorkspaceService(): WorkspaceService = this

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        request(InitializeResult(ServerCapabilities())) {
            @Suppress("DEPRECATION")
            val uri = params.workspaceFolders?.firstOrNull()?.uri ?: params.rootUri
            folder = uri?.let(Uris::toPath)?.let { Paths.get(it) }
            watchFiles =
                params.capabilities?.workspace?.didChangeWatchedFiles?.dynamicRegistration == true
            configure(params.initializationOptions as? JsonObject)
            val capabilities =
                ServerCapabilities().apply {
                    setTextDocumentSync(TextDocumentSyncKind.Full)
                    setDefinitionProvider(true)
                    setHoverProvider(true)
                    setReferencesProvider(true)
                    setRenameProvider(RenameOptions(true))
                    setDocumentFormattingProvider(true)
                    setDocumentSymbolProvider(true)
                }
            InitializeResult(capabilities, ServerInfo("schemata"))
        }

    override fun initialized(params: InitializedParams) = notify {
        if (watchFiles) {
            val options =
                DidChangeWatchedFilesRegistrationOptions(
                    listOf(FileSystemWatcher(Either.forLeft("**/*.schemata")))
                )
            client?.registerCapability(
                RegistrationParams(
                    listOf(
                        Registration("schemata-files", "workspace/didChangeWatchedFiles", options)
                    )
                )
            )
        }
    }

    override fun shutdown(): CompletableFuture<Any?> =
        request(null) {
            cancelPending()
            shutdownRequested = true
            null
        }

    override fun exit() {
        executor.shutdownNow()
        onExit(if (shutdownRequested) 0 else 1)
    }

    /**
     * `{"roots": ["model"], "strict": true}`; a key that is missing or of the wrong type keeps its
     * current value. A relative root resolves against the workspace folder, and is ignored when
     * there is none.
     */
    private fun configure(options: JsonObject?) {
        options
            ?.get("roots")
            ?.takeIf { it.isJsonArray }
            ?.let { roots = it.asJsonArray.mapNotNull(::text) }
        options
            ?.get("strict")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.let { strict = it.asBoolean }
        val base = folder
        val paths =
            roots.mapNotNull { root ->
                val path = Paths.get(root)
                if (path.isAbsolute) path else base?.resolve(path)
            }
        workspace.configure(paths, strict)
    }

    private fun text(element: JsonElement): String? =
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    // ---- documents ----------------------------------------------------------------------------

    private fun path(uri: String): String? = Uris.toPath(uri)?.also { uris[it] = uri }

    private fun uriOf(path: String): String = uris[path] ?: Uris.toUri(path)

    override fun didOpen(params: DidOpenTextDocumentParams) = notify {
        path(params.textDocument.uri)?.let {
            // An editor may have dropped a closed file's diagnostics; send them again on reopen.
            published.remove(it)
            touch(workspace.open(it, params.textDocument.text))
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) = notify {
        val path = path(params.textDocument.uri)
        val text = params.contentChanges.lastOrNull()?.text
        if (path != null && text != null) touch(workspace.change(path, text))
    }

    override fun didClose(params: DidCloseTextDocumentParams) = notify {
        path(params.textDocument.uri)?.let { touch(workspace.close(it)) }
    }

    override fun didSave(params: DidSaveTextDocumentParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = notify {
        params.changes.forEach { change ->
            path(change.uri)?.let { touch(workspace.diskChanged(it)) }
        }
    }

    /**
     * Settings without a `schemata` section change nothing. A file that was published with
     * diagnostics and is in none of the sets published afterwards is cleared, since nothing would
     * publish it again.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = notify {
        val section =
            (params.settings as? JsonObject)?.get("schemata") as? JsonObject ?: return@notify
        cancelPending()
        configure(section)
        val members = workspace.openSets().flatMap(::publish).toSet()
        published
            .filter { (path, diagnostics) -> diagnostics.isNotEmpty() && path !in members }
            .keys
            .toList()
            .forEach { send(it, emptyList()) }
    }

    // ---- diagnostics --------------------------------------------------------------------------

    private fun touch(key: SetKey) {
        pending.remove(key)?.cancel(false)
        pending[key] =
            executor.schedule(
                {
                    guarded {
                        pending.remove(key)
                        publish(key)
                    }
                },
                DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
    }

    /** Publishes now if [path]'s set is waiting out its pause. */
    private fun flush(path: String) {
        val key = workspace.keyOf(path)
        pending.remove(key)?.let {
            it.cancel(false)
            publish(key)
        }
    }

    private fun cancelPending() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }

    /** Publishes what changed in [key]'s diagnostics and returns the set's members. */
    private fun publish(key: SetKey): List<String> {
        val analysis = workspace.analysis(key)
        analysis.members.forEach { document ->
            // Syntax errors point into the current text; everything else into the snapshot, which
            // is the current text whenever the file parses.
            val lines =
                if (document.broken) LineIndex(document.text)
                else document.snapshot?.lines ?: LineIndex(document.text)
            val diagnostics = analysis.diagnostics[document.path] ?: emptyList()
            send(document.path, diagnostics.map { it.toLsp(lines) })
        }
        analysis.gone.forEach {
            published.remove(it)
            client?.publishDiagnostics(PublishDiagnosticsParams(uriOf(it), emptyList()))
        }
        return analysis.members.map { it.path }
    }

    private fun send(path: String, diagnostics: List<Diagnostic>) {
        if (published[path] == diagnostics) return
        published[path] = diagnostics
        client?.publishDiagnostics(PublishDiagnosticsParams(uriOf(path), diagnostics))
    }

    // ---- requests -----------------------------------------------------------------------------

    /** The path behind [uri], with its set's diagnostics brought up to date. */
    private fun current(uri: String): String? = path(uri)?.also(::flush)

    private fun location(location: io.schemata.lsp.workspace.Location) =
        Location(uriOf(location.path), location.range.toLsp())

    override fun definition(
        params: DefinitionParams
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        request(Either.forLeft(mutableListOf())) {
            val path = current(params.textDocument.uri)
            val found =
                path?.let { queries.definition(it, params.position.toText()) } ?: emptyList()
            Either.forLeft(found.map(::location).toMutableList())
        }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> =
        request(mutableListOf()) {
            val path = current(params.textDocument.uri)
            val found =
                path?.let {
                    queries.references(
                        it,
                        params.position.toText(),
                        params.context?.isIncludeDeclaration == true,
                    )
                } ?: emptyList()
            found.map(::location).toMutableList()
        }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        request(null) {
            current(params.textDocument.uri)
                ?.let { queries.hover(it, params.position.toText()) }
                ?.let { Hover(MarkupContent(MarkupKind.MARKDOWN, it.markdown), it.range.toLsp()) }
        }

    override fun documentSymbol(
        params: DocumentSymbolParams
    ): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> =
        request(mutableListOf()) {
            val path = current(params.textDocument.uri)
            (path?.let(queries::symbols) ?: emptyList())
                .map { Either.forRight<SymbolInformation, DocumentSymbol>(it.toLsp()) }
                .toMutableList()
        }

    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> =
        request(null) {
            current(params.textDocument.uri)
                ?.let { queries.prepareRename(it, params.position.toText()) }
                ?.let { Either3.forFirst(it.toLsp()) }
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
        request(null) {
            val path = current(params.textDocument.uri) ?: refuse("nothing to rename here")
            when (val result = queries.rename(path, params.position.toText(), params.newName)) {
                is RenameResult.Refused -> refuse(result.message)
                is RenameResult.Edits ->
                    WorkspaceEdit(
                        result.edits
                            .map { (file, edits) ->
                                uriOf(file) to edits.map { TextEdit(it.range.toLsp(), it.newText) }
                            }
                            .toMap()
                    )
            }
        }

    override fun formatting(
        params: DocumentFormattingParams
    ): CompletableFuture<MutableList<out TextEdit>> =
        request(mutableListOf()) {
            // The text alone decides the edit, so this does not wait for the set's diagnostics.
            val document = path(params.textDocument.uri)?.let(workspace::document)
            val formatted =
                document?.let { Formatter.format(it.text, it.path) as? FormatResult.Formatted }
            // The formatter writes `\n`; line endings alone are left to the editor.
            val same = formatted?.text == document?.text?.replace("\r\n", "\n")
            if (document == null || formatted == null || same) {
                mutableListOf()
            } else {
                val whole = Range(Position(0, 0), LineIndex(document.text).end().toLsp())
                mutableListOf(TextEdit(whole, formatted.text))
            }
        }

    // ---- plumbing -----------------------------------------------------------------------------

    private fun refuse(message: String): Nothing =
        throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

    /**
     * Runs [body] on the server's thread; a failure is logged and answered with [fallback]. After
     * shutdown every request is invalid.
     */
    private fun <T> request(fallback: T, body: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                if (shutdownRequested) {
                    throw ResponseErrorException(
                        ResponseError(
                            ResponseErrorCode.InvalidRequest,
                            "the server is shutting down",
                            null,
                        )
                    )
                }
                try {
                    body()
                } catch (e: ResponseErrorException) {
                    throw e
                } catch (e: Throwable) {
                    log(e)
                    fallback
                }
            },
            executor,
        )

    /** Runs [body] on the server's thread, unless the server is shutting down. */
    private fun notify(body: () -> Unit) {
        executor.execute { if (!shutdownRequested) guarded(body) }
    }

    /**
     * A failure is logged, whatever it is: on the executor, an uncaught error such as a stack
     * overflow would vanish into a future nobody reads.
     */
    private fun guarded(body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun log(e: Throwable) {
        client?.logMessage(MessageParams(MessageType.Error, "schemata: ${e.stackTraceToString()}"))
    }

    companion object {
        const val DEBOUNCE_MS = 200L

        /**
         * Serves one client on the given streams and returns a future that completes when the input
         * ends. [onExit] receives 0 after a clean shutdown and 1 otherwise.
         */
        fun launch(
            input: InputStream,
            output: OutputStream,
            annotations: AnnotationRegistry,
            onExit: (Int) -> Unit,
        ): Future<Void> = start(input, output, annotations, onExit).second

        /**
         * Serves one client on the given streams until the input ends, and returns the exit code: 0
         * when the client asked for shutdown first, 1 otherwise. [onExit] receives the same code
         * when the client sends exit.
         */
        fun serve(
            input: InputStream,
            output: OutputStream,
            annotations: AnnotationRegistry,
            onExit: (Int) -> Unit,
        ): Int {
            val (server, listening) = start(input, output, annotations, onExit)
            listening.get()
            return if (server.shutdownRequested) 0 else 1
        }

        private fun start(
            input: InputStream,
            output: OutputStream,
            annotations: AnnotationRegistry,
            onExit: (Int) -> Unit,
        ): Pair<SchemataServer, Future<Void>> {
            val server = SchemataServer(annotations, onExit)
            val launcher = LSPLauncher.createServerLauncher(server, input, output)
            server.connect(launcher.remoteProxy)
            return server to launcher.startListening()
        }
    }
}
