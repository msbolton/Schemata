package io.schemata.lang

import io.schemata.lang.ast.BindingDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceParseTest {
    private fun parse(src: String) = Parser.parse(src.trimIndent(), "t.schemata")

    @Test
    fun `a service parses with every operation shape`() {
        val r =
            parse(
                """
                namespace t
                record OrderId { #1 id: uuid }
                record Order { #1 id: uuid }
                record Chunk { #1 b: bytes }
                record Receipt { #1 n: int64 }
                /// Place and read orders.
                @deprecated
                service Orders {
                  /// Fetch one order.
                  #1 get(OrderId): Order  get "/orders/{id}"
                  #2 list(OrderId): stream Order  get "/orders"
                  #3 place(Order): Order  post "/orders"
                  #4 cancel(OrderId)  delete "/orders/{id}"
                  #5 upload(stream Chunk): Receipt
                  #6 ping()
                  reserved #7, "archive"
                }
                """
            )
        assertEquals(emptyList(), r.diagnostics)
        val file = r.file!!
        assertEquals(4, file.declarations.size)
        val s = file.services.single()
        assertEquals("Orders", s.name)
        assertEquals("Place and read orders.", s.doc)
        assertEquals(listOf("deprecated"), s.annotations.map { it.name })
        assertEquals(
            listOf("get", "list", "place", "cancel", "upload", "ping"),
            s.operations.map { it.name },
        )
        val get = s.operations[0]
        assertEquals(1, get.ordinal)
        assertEquals("Fetch one order.", get.doc)
        assertEquals("OrderId", get.request!!.type.name)
        assertFalse(get.request!!.stream)
        assertEquals("Order", get.response!!.type.name)
        val binding = get.binding!!
        assertEquals(
            BindingDecl(
                "get",
                binding.verbSpan,
                "/orders/{id}",
                binding.pathSpan,
                listOf("id"),
                binding.span,
            ),
            binding,
        )
        assertEquals(Span("t.schemata", 10, 27, 10, 29), binding.verbSpan)
        assertEquals(Span("t.schemata", 10, 31, 10, 44), binding.pathSpan)
        assertTrue(s.operations[1].response!!.stream)
        assertNull(s.operations[3].response)
        assertEquals("delete", s.operations[3].binding!!.verb)
        assertTrue(s.operations[4].request!!.stream)
        assertNull(s.operations[4].binding)
        assertNull(s.operations[5].request)
        assertNull(s.operations[5].response)
        assertEquals(
            listOf(
                ReservedItem.Ordinals(7, 7, s.reserved[0].span),
                ReservedItem.Name("archive", s.reserved[1].span),
            ),
            s.reserved,
        )
    }

    @Test
    fun `fields and values named like verbs still parse`() {
        val r =
            parse(
                "namespace t\nrecord R { #1 get: string #2 post: int32 #3 delete: bool }\nenum E { get, put }"
            )
        assertEquals(emptyList(), r.diagnostics)
        assertEquals(
            listOf("get", "post", "delete"),
            (r.file!!.declarations[0] as RecordDecl).fields.map { it.name },
        )
    }

    @Test
    fun `an unknown verb is SCH0006 and a malformed path is SCH0007`() {
        val r =
            parse(
                "namespace t\nrecord A { #1 x: int32 }\nservice S {\n  #1 a(A): A  fetch \"/a\"\n  #2 b(A): A  get \"a/{X}\"\n  #3 c(A): A  get \"/a?x=1\"\n}"
            )
        assertEquals(
            listOf(
                "SCH0006 'fetch' is not an HTTP verb",
                "SCH0007 path \"a/{X}\" is malformed: it must start with /",
                "SCH0007 path \"/a?x=1\" is malformed: segment \"a?x=1\" holds a character outside A-Z a-z 0-9 . _ ~ -",
            ),
            r.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(Span("t.schemata", 4, 15, 4, 19), r.diagnostics[0].span)
    }

    @Test
    fun `every malformed path shape is named`() {
        fun problem(path: String) =
            parse("namespace t\nrecord A { #1 x: int32 }\nservice S { #1 a(A): A  get \"$path\" }")
                .diagnostics
                .map { it.message }
        assertEquals(emptyList(), problem("/"))
        assertEquals(emptyList(), problem("/a/{order_id}/b.c_d~e-f"))
        assertEquals(listOf("path \"/a/\" is malformed: it must not end with /"), problem("/a/"))
        assertEquals(
            listOf("path \"/a//b\" is malformed: it has an empty segment"),
            problem("/a//b"),
        )
        assertEquals(
            listOf("path \"/a/{Id}\" is malformed: parameter \"Id\" is not lower_snake"),
            problem("/a/{Id}"),
        )
    }

    @Test
    fun `diagnostics in a service and a later record come out in source order`() {
        val r =
            parse(
                "namespace t\nrecord A { #1 x: int32 }\nservice S { #1 a(A): A  fetch \"/a\" }\nrecord B { #1 x: string = \"\\q\" }"
            )
        assertEquals(listOf("SCH0006", "SCH0004"), r.diagnostics.map { it.code.id })
    }

    @Test
    fun `operation and stream stay reserved at top level and a service may not nest`() {
        val r = parse("namespace t\noperation Foo {}\nstream Bar")
        assertEquals(listOf("SCH0002", "SCH0002"), r.diagnostics.map { it.code.id })
        val nested = parse("namespace t\nrecord R { service S {} }")
        assertEquals("SCH0001", nested.diagnostics.first().code.id)
    }
}
