package io.schemata.target.proto

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.Schema
import io.schemata.lang.Parser
import io.schemata.target.Names
import io.schemata.target.ProtoPackages
import io.schemata.target.ProtoUnit
import io.schemata.target.referencesByKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoTypeFilesTest {
    /** Analyses [sources], each in a file named for its namespace, expecting no diagnostics. */
    private fun analysed(vararg sources: String): Schema {
        val files =
            sources.map { text ->
                val ns = Regex("""schema\s+([\w.]+)""").find(text)!!.groupValues[1]
                Parser.parse(text, "$ns.schemata").file!!
            }
        val analysis =
            Analyzer.analyze(
                files,
                AnalysisOptions(
                    annotations = AnnotationRegistry(CoreAnnotations.specs + ProtoAnnotations.specs)
                ),
            )
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!.referencesByKey()
    }

    /** The unit of the schema named [namespace] in the key-rewritten schema of [sources]. */
    private fun unit(namespace: String, vararg sources: String): ProtoUnit =
        ProtoPackages.of(analysed(*sources)).unitOf(namespace)

    private fun split(namespace: String, vararg sources: String): List<TypeFile> {
        return ProtoTypeFiles.split(
            unit(namespace, *sources),
            { Names.snakeCase(it.name) },
            { Names.snakeCase(it.name) },
        )
    }

    /** Each file as its path and the names of what it holds. */
    private fun List<TypeFile>.shapes(): List<Pair<String, List<String>>> = map { file ->
        file.path to file.declarations.map { it.name } + file.services.map { it.name }
    }

    @Test
    fun `directory is the package path whatever file the unit takes under the schema layout`() {
        val unit = ProtoUnit(emptyList(), "shop.orders.v1", "shop/orders.proto")
        assertEquals("shop/orders/v1/", ProtoTypeFiles.directory(unit))
    }

    @Test
    fun `whole holds every declaration and service at the unit path`() {
        val unit =
            unit(
                "shop.orders",
                "schema shop.orders\n\nmodel Order { #1 n int32 }\n\nenum Status { #1 placed }\n\n" +
                    "service Orders {\n  #1 get(Order): Order\n}\n",
            )
        val file = ProtoTypeFiles.whole(unit)
        assertEquals("shop/orders.proto", file.path)
        assertEquals(listOf("Order", "Status"), file.declarations.map { it.name })
        assertEquals(listOf("Orders"), file.services.map { it.name })
    }

    @Test
    fun `split gives every declaration its own file sorted by path`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Order { #1 n int32 }\n\nenum Status { #1 placed }\n\n" +
                    "model Customer { #1 n int32 }\n",
            )
        assertEquals(
            listOf(
                "shop/orders/customer.proto" to listOf("Customer"),
                "shop/orders/order.proto" to listOf("Order"),
                "shop/orders/status.proto" to listOf("Status"),
            ),
            files.shapes(),
        )
    }

    @Test
    fun `declarations referencing each other share the file of the first name`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Order { #1 customer Customer? }\n\n" +
                    "model Customer { #1 last Order? }\n",
            )
        assertEquals(
            listOf("shop/orders/customer.proto" to listOf("Order", "Customer")),
            files.shapes(),
        )
    }

    @Test
    fun `a cycle through a third declaration is one file and an outside reference is another`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel A { #1 b B? }\n\nmodel B { #1 c C? }\n\n" +
                    "model C { #1 a A? }\n\nmodel D { #1 a A }\n",
            )
        assertEquals(
            listOf(
                "shop/orders/a.proto" to listOf("A", "B", "C"),
                "shop/orders/d.proto" to listOf("D"),
            ),
            files.shapes(),
        )
    }

    @Test
    fun `a reference through a nested declaration ties the top-level declarations`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Order {\n  model Line { #1 item Item }\n}\n\n" +
                    "model Item { #1 order Order? }\n",
            )
        assertEquals(listOf("shop/orders/item.proto" to listOf("Order", "Item")), files.shapes())
    }

    @Test
    fun `a key record is its own file`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Pair { #1 a int32 { id }  #2 b int32 { id } }\n\n" +
                    "model A { #1 pair Pair }\n",
            )
        assertEquals(
            listOf(
                "shop/orders/a.proto" to listOf("A"),
                "shop/orders/pair.proto" to listOf("Pair"),
                "shop/orders/pair_key.proto" to listOf("PairKey"),
            ),
            files.shapes(),
        )
    }

    @Test
    fun `a reference to another unit ties nothing`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nimport shop.catalog\n\nmodel Order { #1 money Money }\n\n" +
                    "model Line { #1 money Money }\n",
                "schema shop.catalog\n\nmodel Money { #1 amount int64 }\n",
            )
        assertEquals(
            listOf(
                "shop/orders/line.proto" to listOf("Line"),
                "shop/orders/order.proto" to listOf("Order"),
            ),
            files.shapes(),
        )
    }

    @Test
    fun `each service is its own file`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Order { #1 n int32 }\n\n" +
                    "service Orders {\n  #1 get(Order): Order\n}\n",
            )
        assertEquals(
            listOf(
                "shop/orders/order.proto" to listOf("Order"),
                "shop/orders/orders.proto" to listOf("Orders"),
            ),
            files.shapes(),
        )
        val service = files.single { it.path == "shop/orders/orders.proto" }
        assertEquals(emptyList(), service.declarations)
    }

    @Test
    fun `a shared file is marked shared`() {
        val files =
            split(
                "shop.orders",
                "schema shop.orders\n\nmodel Order { #1 customer Customer? }\n\n" +
                    "model Customer { #1 last Order? }\n\nmodel Plain { #1 n int32 }\n",
            )
        assertTrue(files.single { it.path == "shop/orders/customer.proto" }.shared)
        assertFalse(files.single { it.path == "shop/orders/plain.proto" }.shared)
    }
}
