package io.schemata.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The reference-by-key mask leaves out what a reference changes and nothing else: over the shop
 * example's XSD, JSON Schema, and Protobuf outputs as 1.4.0 wrote them and as 2.0 writes them, the
 * two masked texts agree, keep the lines that have nothing to do with a reference, and still differ
 * when one of those lines changes.
 */
class ReferenceMaskTest {
    private val refs by lazy {
        val dir = File("../examples/shop")
        val sources =
            dir.listFiles { f -> f.extension == "schemata" }!!.map {
                    SourceInput(it.name, it.readText())
                }
        References.of(Pipeline.analyze(sources).schema!!)
    }

    /** The 1.4.0 golden at [path] under `examples/shop/expected`, and the current one. */
    private fun pair(path: String): Pair<String, String> {
        val old = git("show", "v1.4.0:examples/shop/expected/$path")
        assumeTrue(old != null, "the v1.4.0 tag is not available")
        return old!! to File("../examples/shop/expected/$path").readText()
    }

    private fun masked(path: String, before: String, after: String): Pair<String, String> {
        val notes = Regex("""((?://|--) schemata: ).*""")
        return ReferenceMask.mask(
            path,
            before.replace(notes, "$1…"),
            after.replace(notes, "$1…"),
            refs,
        )
    }

    @Test
    fun `the xsd mask keeps the schema root, sibling elements, and docs`() {
        val (before, after) = pair("xsd/shop/orders.xsd")
        val (a, b) = masked("xsd/shop/orders.xsd", before, after)
        assertEquals(a, b)
        assertTrue(a.lines().size > 40, a)
        assertTrue("<xs:schema xmlns:xs=" in a, a)
        assertTrue("</xs:schema>" in a, a)
        assertTrue("<xs:element name=\"status\"" in a, a)
        assertTrue("<xs:documentation>" in a, a)
        assertTrue("customer_id" !in b && "ns1:CustomerType" !in a, a)
        val changed = after.replace("<xs:element name=\"status\"", "<xs:element name=\"state\"")
        assertNotEquals(a, masked("xsd/shop/orders.xsd", before, changed).second)
    }

    @Test
    fun `the json mask keeps sibling properties and descriptions`() {
        val (before, after) = pair("jsonschema/shop/orders.schema.json")
        val (a, b) = masked("jsonschema/shop/orders.schema.json", before, after)
        assertEquals(a, b)
        assertTrue("\"status\": {" in a && "\"description\":" in a && "\"\$defs\": {" in a, a)
        assertTrue("\"customer" !in a, a)
        val changed = after.replace("\"placed_at\"", "\"placed\"")
        assertNotEquals(a, masked("jsonschema/shop/orders.schema.json", before, changed).second)
    }

    @Test
    fun `the proto mask keeps sibling fields and doc comments`() {
        val (before, after) = pair("proto/shop/orders.proto")
        val (a, b) = masked("proto/shop/orders.proto", before, after)
        assertEquals(a, b)
        assertTrue(
            "message Order {" in a && "Status status = 3;" in a && "// A customer's order" in a,
            a,
        )
        assertTrue("customer" !in a.substringAfter("message Order {").substringBefore("}"), a)
        val changed = after.replace("Status status = 3;", "Status status = 30;")
        assertNotEquals(a, masked("proto/shop/orders.proto", before, changed).second)
    }

    @Test
    fun `a name is masked only inside the model that references by key`() {
        val source =
            SourceInput(
                "s.schemata",
                """
                schema s

                model Customer { #1 id uuid { id } }

                model Order { #1 id uuid { id }  #2 customer Customer }

                model Note { #1 id uuid { id }  #2 customer string }
                """
                    .trimIndent(),
            )
        val schema = Pipeline.analyze(listOf(source)).schema!!
        val proto =
            Pipeline.compile(listOf(source), listOf(Pipeline.targetNamed("proto")!!))
                .targets
                .single()
                .files
                .single()
        val (masked, _) =
            ReferenceMask.mask(
                "proto/${proto.path}",
                proto.content,
                proto.content,
                References.of(schema),
            )
        val order = masked.substringAfter("message Order {").substringBefore("}")
        val note = masked.substringAfter("message Note {").substringBefore("}")
        assertTrue("customer" !in order, masked)
        assertTrue("string customer = 2;" in note, masked)
    }

    private fun git(vararg args: String): String? {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(File(".."))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val out = process.inputStream.bufferedReader().readText()
        return if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) out else null
    }
}
