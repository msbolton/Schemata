package io.schemata.testkit

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.fail
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer

/**
 * A scripted editor: talks the protocol to a language server over a pair of streams and remembers
 * every diagnostics publish, so a test can wait for the state it expects.
 */
class LspSession
private constructor(
    private val toServer: OutputStream,
    private val fromServer: InputStream,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private class Client : LanguageClient {
        val lock = ReentrantLock()
        val changed: Condition = lock.newCondition()
        val published = mutableMapOf<String, MutableList<List<Diagnostic>>>()
        val logged = mutableListOf<String>()

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            lock.withLock {
                published.getOrPut(params.uri) { mutableListOf() } += params.diagnostics
                changed.signalAll()
            }
        }

        override fun logMessage(params: MessageParams) {
            lock.withLock { logged += params.message }
        }

        override fun telemetryEvent(value: Any?) = Unit

        override fun showMessage(params: MessageParams) = Unit

        override fun showMessageRequest(
            params: ShowMessageRequestParams
        ): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)

        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)
    }

    private val client = Client()
    private val versions = mutableMapOf<String, Int>()
    val server: LanguageServer

    init {
        val launcher = LSPLauncher.createClientLauncher(client, fromServer, toServer)
        launcher.startListening()
        server = launcher.remoteProxy
    }

    fun uri(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()

    /** Sends initialize and initialized; [options] become the initialization options. */
    fun initialize(root: Path, options: Map<String, Any?> = emptyMap()): InitializeResult {
        val params =
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(uri(root), root.fileName.toString()))
                initializationOptions = options
            }
        val result = server.initialize(params).get(30, TimeUnit.SECONDS)
        server.initialized(InitializedParams())
        return result
    }

    fun open(path: Path, text: String) = openUri(uri(path), text)

    fun openUri(uri: String, text: String) {
        versions[uri] = 1
        server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "schemata", 1, text))
        )
    }

    fun change(path: Path, text: String) {
        val uri = uri(path)
        val version = versions.merge(uri, 1, Int::plus)!!
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, version),
                listOf(TextDocumentContentChangeEvent(text)),
            )
        )
    }

    fun close(path: Path) {
        server.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri(path)))
        )
    }

    /** Tells the server a file changed on disk, as the editor's file watcher would. */
    fun watched(path: Path, type: FileChangeType) {
        server.workspaceService.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(listOf(FileEvent(uri(path), type)))
        )
    }

    /** How many times diagnostics have been published for [path]. */
    fun publishCount(path: Path): Int = publishCountUri(uri(path))

    fun publishCountUri(uri: String): Int =
        client.lock.withLock { client.published[uri]?.size ?: 0 }

    /** Everything the server has logged to the client. */
    fun logged(): List<String> = client.lock.withLock { client.logged.toList() }

    /**
     * Waits until the latest publish for [path] satisfies [until] and returns it; fails after ten
     * seconds with the last thing published.
     */
    fun diagnostics(path: Path, until: (List<Diagnostic>) -> Boolean): List<Diagnostic> {
        val uri = uri(path)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        client.lock.withLock {
            while (true) {
                val latest = client.published[uri]?.lastOrNull()
                if (latest != null && until(latest)) return latest
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) {
                    fail(
                        "no matching diagnostics for $uri; last published: ${latest?.map { it.message }}"
                    )
                }
                client.changed.await(left, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Sends shutdown and exit, then releases the streams or the process. */
    override fun close() {
        try {
            server.shutdown().get(10, TimeUnit.SECONDS)
            server.exit()
        } finally {
            onClose()
        }
    }

    companion object {
        /** [serve] is handed the stream the server reads and the stream it writes. */
        fun inProcess(serve: (InputStream, OutputStream) -> Unit): LspSession {
            val serverIn = PipedInputStream(1 shl 16)
            val clientOut = PipedOutputStream(serverIn)
            val clientIn = PipedInputStream(1 shl 16)
            val serverOut = PipedOutputStream(clientIn)
            serve(serverIn, serverOut)
            return LspSession(clientOut, clientIn) {
                clientOut.close()
                serverOut.close()
            }
        }

        /** Talks to a server running as [process] on its standard streams. */
        fun overProcess(process: Process): LspSession =
            LspSession(process.outputStream, process.inputStream) {
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            }
    }
}
