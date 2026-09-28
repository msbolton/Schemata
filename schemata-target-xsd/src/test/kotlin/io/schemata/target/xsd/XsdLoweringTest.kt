package io.schemata.target.xsd

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Schema
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class XsdLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun xsd(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("xsd" to pairs.toMap()))

    private fun namespace(
        name: String,
        annotations: Annotations = Annotations.NONE,
        line: Int = 1,
        declarations: List<io.schemata.core.ir.TypeDecl> = emptyList(),
    ) = Namespace(name, declarations, at(line), annotations)

    @Test
    fun `each namespace becomes one file with a urn target namespace`() {
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("shop.orders"), namespace("shop.customers"))))
        assertEquals(emptyList(), lowered.diagnostics)
        assertEquals(
            listOf("shop/orders.xsd", "shop/customers.xsd"),
            lowered.model.files.map { it.path },
        )
        assertEquals("urn:schemata:shop.orders", lowered.model.files[0].targetNamespace)
    }

    @Test
    fun `an xsd namespace override replaces the uri`() {
        val ns =
            namespace(
                "shop.orders",
                xsd("namespace" to AnnotationValue.Str("http://example.com/orders")),
            )
        val lowered = XsdLowering.lower(Schema(listOf(ns)))
        assertEquals("http://example.com/orders", lowered.model.files.single().targetNamespace)
    }

    @Test
    fun `two namespaces sharing a uri collide on the second`() {
        val a = namespace("a", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 1)
        val b = namespace("b", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 5)
        val d = XsdLowering.lower(Schema(listOf(a, b))).diagnostics.single()
        assertEquals(XsdCodes.NAMESPACE_COLLISION, d.code)
        assertEquals("namespaces a and b both lower to target namespace 'urn:x'", d.message)
        assertEquals(5, d.span.startLine)
        assertEquals("set `@xsd(namespace = \"…\")` on one of them", d.help)
    }

    @Test
    fun `a relative namespace override is rejected`() {
        val ns = namespace("shop.orders", xsd("namespace" to AnnotationValue.Str("orders")))
        val d = XsdLowering.lower(Schema(listOf(ns))).diagnostics.single()
        assertEquals(XsdCodes.INVALID_OVERRIDE, d.code)
        assertEquals(
            "namespace 'shop.orders': @xsd(namespace = \"orders\") is not an absolute URI",
            d.message,
        )
        assertEquals("use an absolute URI such as `urn:example:orders`", d.help)
    }
}
