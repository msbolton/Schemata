package io.schemata.testkit

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.SAXException

/**
 * Compiles rendered `.xsd` files with the JDK's XML Schema 1.0 processor. Imports are served from
 * the same map, resolved relative to the importing file's path, so nothing touches disk.
 */
object Xsd {
    private const val MEMORY_PREFIX = "memory:/"

    /** @return null when every schema compiles; otherwise the first error the processor reports. */
    fun validate(files: Map<String, String>): String? {
        files.keys.sorted().forEach { path ->
            try {
                factory(files).newSchema(source(files, path))
            } catch (e: SAXException) {
                return "$path: ${e.message}"
            }
        }
        return null
    }

    /** @return null when [xml] is valid against the schema at [rootPath]; otherwise the error. */
    fun validateDocument(files: Map<String, String>, rootPath: String, xml: String): String? =
        try {
            factory(files)
                .newSchema(source(files, rootPath))
                .newValidator()
                .validate(StreamSource(StringReader(xml)))
            null
        } catch (e: SAXException) {
            e.message
        }

    private fun factory(files: Map<String, String>): SchemaFactory =
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            resourceResolver = LSResourceResolver { _, _, _, systemId, baseURI ->
                val base = baseURI?.removePrefix(MEMORY_PREFIX) ?: ""
                val resolved = resolve(base, systemId ?: return@LSResourceResolver null)
                val content = files[resolved] ?: return@LSResourceResolver null
                Input(content, "$MEMORY_PREFIX$resolved")
            }
        }

    private fun source(files: Map<String, String>, path: String) =
        StreamSource(StringReader(files.getValue(path)), "$MEMORY_PREFIX$path")

    /** `o/orders.xsd` + `../c/customers.xsd` -> `c/customers.xsd`. */
    internal fun resolve(base: String, relative: String): String {
        val dir =
            base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        relative.split('/').forEach { part ->
            when (part) {
                "",
                "." -> Unit
                ".." -> if (dir.isNotEmpty()) dir.removeAt(dir.lastIndex)
                else -> dir += part
            }
        }
        return dir.joinToString("/")
    }

    private class Input(private val text: String, private val id: String) : LSInput {
        override fun getCharacterStream() = StringReader(text)

        override fun setCharacterStream(r: java.io.Reader?) = Unit

        override fun getByteStream() = ByteArrayInputStream(text.toByteArray())

        override fun setByteStream(s: java.io.InputStream?) = Unit

        override fun getStringData() = text

        override fun setStringData(s: String?) = Unit

        override fun getSystemId() = id

        override fun setSystemId(s: String?) = Unit

        override fun getPublicId(): String? = null

        override fun setPublicId(s: String?) = Unit

        override fun getBaseURI() = id

        override fun setBaseURI(s: String?) = Unit

        override fun getEncoding() = "UTF-8"

        override fun setEncoding(s: String?) = Unit

        override fun getCertifiedText() = false

        override fun setCertifiedText(b: Boolean) = Unit
    }
}
