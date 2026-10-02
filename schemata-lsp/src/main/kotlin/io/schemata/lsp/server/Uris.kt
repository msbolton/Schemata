package io.schemata.lsp.server

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Paths

/** `file:` URIs to the absolute, normalized paths the workspace keys files by, and back. */
object Uris {
    /** Null for anything that is not a `file:` URI the platform can turn into a path. */
    fun toPath(uri: String): String? =
        try {
            val parsed = URI(uri)
            if (parsed.scheme != "file") null
            else Paths.get(parsed).toAbsolutePath().normalize().toString()
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: FileSystemNotFoundException) {
            null
        }

    fun toUri(path: String): String = Paths.get(path).toUri().toString()
}
