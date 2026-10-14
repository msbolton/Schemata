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
                schema t

                model OrderId { #1 id uuid }

                model Order { #1 id uuid }

                model Chunk { #1 b bytes }

                model Receipt { #1 n int64 }

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
        assertEquals(Span("t.schemata", 15, 27, 15, 29), binding.verbSpan)
        assertEquals(Span("t.schemata", 15, 31, 15, 44), binding.pathSpan)
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
                "schema t\n" +
                    "\n" +
                    "model R { #1 get string  #2 post int32  #3 delete bool }\n" +
                    "\n" +
                    "enum E { get put }"
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
                "schema t\nmodel A { #1 x int32 }\nservice S {\n  #1 a(A): A  fetch \"/a\"\n  #2 b(A): A  get \"a/{X}\"\n  #3 c(A): A  get \"/a?x=1\"\n}"
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
            parse("schema t\nmodel A { #1 x int32 }\nservice S { #1 a(A): A  get \"$path\" }")
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
    fun `a path is checked as written so an escape cannot hide a bad character`() {
        fun problem(path: String) =
            parse("schema t\nmodel A { #1 x int32 }\nservice S { #1 a(A): A  get \"$path\" }")
                .diagnostics
                .map { "${it.code.id} ${it.message}" }
        // `\u{2f}` decodes to `/`, which would split a segment the author wrote as one.
        assertEquals(
            listOf(
                "SCH0007 path \"/a\\u{2f}b\" is malformed: segment \"a\\u{2f}b\" holds a character outside A-Z a-z 0-9 . _ ~ -"
            ),
            problem("/a\\u{2f}b"),
        )
        // A `)` or a `}` inside a path segment is just a bad character.
        assertEquals(
            listOf(
                "SCH0007 path \"/a)b\" is malformed: segment \"a)b\" holds a character outside A-Z a-z 0-9 . _ ~ -"
            ),
            problem("/a)b"),
        )
        assertEquals(
            listOf(
                "SCH0007 path \"/a\\u{29}\" is malformed: segment \"a\\u{29}\" holds a character outside A-Z a-z 0-9 . _ ~ -"
            ),
            problem("/a\\u{29}"),
        )
        assertEquals(listOf("SCH0007 path \"\" is malformed: it must start with /"), problem(""))
    }

    @Test
    fun `request and response are told apart by the closing parenthesis`() {
        val r =
            parse(
                "schema t\nmodel A { #1 x int32 }\nservice S { #1 a(A): A\n #2 b(A)\n #3 c(): A }"
            )
        val ops = r.file!!.services.single().operations
        assertEquals(listOf(true, true, false), ops.map { it.request != null })
        assertEquals(listOf(true, false, true), ops.map { it.response != null })
    }

    @Test
    fun `diagnostics in a service and a later record come out in source order`() {
        val r =
            parse(
                "schema t\nmodel A { #1 x int32 }\nservice S { #1 a(A): A  fetch \"/a\" }\nmodel B { #1 x string = \"\\q\" }"
            )
        assertEquals(listOf("SCH0006", "SCH0004"), r.diagnostics.map { it.code.id })
    }

    @Test
    fun `operation stays reserved at top level and a service may not nest`() {
        val r = parse("schema t\noperation Foo {}\noperation Bar")
        assertEquals(listOf("SCH0002", "SCH0002"), r.diagnostics.map { it.code.id })
        val nested = parse("schema t\nmodel R { service S {} }")
        assertEquals("SCH0001", nested.diagnostics.first().code.id)
    }

    @Test
    fun `success codes and errors clauses parse in every position`() {
        val r =
            parse(
                """
                schema t

                model A { #1 x int32 }

                model Problem { #1 title string }

                service Orders {
                  errors { 500 Problem  default Problem }
                  #1 get(A): A  get "/a/{x}"  errors { 404 Problem  410 }
                  #2 create(A): A  post "/a" 201
                  #3 errors(A): A
                  #4 default(): A  put "/b" 202  errors {
                    409 shop.Problem?
                    default
                  }
                  #5 ping()  errors {}
                }
                """
            )
        assertEquals(emptyList(), r.diagnostics)
        val s = r.file!!.services.single()
        assertEquals(
            listOf("get", "create", "errors", "default", "ping"),
            s.operations.map { it.name },
        )
        val service = s.errors.single()
        assertEquals(listOf(500, null), service.responses.map { it.code })
        assertEquals(listOf("Problem", "Problem"), service.responses.map { it.body?.name })
        val (get, create, errors, default, ping) = s.operations
        val clause = get.errors!!
        assertEquals(listOf(404, 410), clause.responses.map { it.code })
        assertEquals(listOf("Problem", null), clause.responses.map { it.body?.name })
        assertEquals(Span("t.schemata", 9, 40, 9, 42), clause.responses[0].codeSpan)
        assertEquals(Span("t.schemata", 9, 40, 9, 50), clause.responses[0].span)
        assertEquals(Span("t.schemata", 9, 31, 9, 57), clause.span)
        assertEquals(Span("t.schemata", 9, 3, 9, 57), get.span)
        assertNull(get.binding!!.status)
        assertEquals(201, create.binding!!.status)
        assertEquals(Span("t.schemata", 10, 30, 10, 32), create.binding!!.statusSpan)
        assertNull(create.errors)
        assertNull(errors.errors)
        assertEquals("A", errors.request!!.type.name)
        assertEquals(202, default.binding!!.status)
        val wide = default.errors!!.responses
        assertEquals(listOf(409, null), wide.map { it.code })
        assertEquals("shop.Problem", wide[0].body!!.name)
        assertTrue(wide[0].body!!.nullable)
        assertNull(wide[1].body)
        assertEquals(emptyList(), ping.errors!!.responses)
    }

    @Test
    fun `a clause on a line after its operation belongs to the service`() {
        val r =
            parse(
                "schema t\nmodel A { #1 x int32 }\nservice S {\n  #1 a(A): A\n  errors { 404 }\n" +
                    "  #2 b(A): A  get \"/b\"\n  errors { 500 }\n}"
            )
        assertEquals(emptyList(), r.diagnostics)
        val s = r.file!!.services.single()
        assertEquals(listOf(null, null), s.operations.map { it.errors })
        assertEquals(
            listOf(listOf(404), listOf(500)),
            s.errors.map { e -> e.responses.map { it.code } },
        )
        assertEquals(Span("t.schemata", 4, 3, 4, 12), s.operations[0].span)
        assertEquals(Span("t.schemata", 6, 3, 6, 22), s.operations[1].span)
    }

    @Test
    fun `errors and default stay ordinary names`() {
        val r =
            parse(
                "schema t\n" +
                    "model errors { #1 errors string  #2 default int32 }\n" +
                    "enum default { errors default }\n" +
                    "alias Errs = errors\n" +
                    "service S {\n" +
                    "  #1 errors(errors): default\n" +
                    "  #2 default()  errors { 404 errors }\n" +
                    "}"
            )
        assertEquals(emptyList(), r.diagnostics)
        val file = r.file!!
        assertEquals(
            listOf("errors", "default"),
            (file.declarations[0] as RecordDecl).fields.map { it.name },
        )
        val (first, second) = file.services.single().operations
        assertEquals("errors", first.name)
        assertEquals("default", first.response!!.type.name)
        assertNull(first.errors)
        assertEquals("default", second.name)
        assertEquals("errors", second.errors!!.responses.single().body!!.name)
    }

    @Test
    fun `a malformed code or clause word is SCH0010 at what is wrong`() {
        val r =
            parse(
                "schema t\nmodel A { #1 x int32 }\nservice S {\n  oops { 404 }\n" +
                    "  #1 a(A): A  post \"/a\" -201  errors { 404 A  Problem  \"409\"  0410 }\n}"
            )
        assertEquals(
            listOf(
                "SCH0010 service 'S': 'oops' is not a clause",
                "SCH0010 operation 'a': success code '-201' is not a plain integer",
                "SCH0010 operation 'a': error code 'Problem' is not an integer or default",
                "SCH0010 operation 'a': error code '\"409\"' is not an integer or default",
                "SCH0010 operation 'a': error code '0410' is not a plain integer",
            ),
            r.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(
            listOf(
                Span("t.schemata", 4, 3, 4, 6),
                Span("t.schemata", 5, 25, 5, 28),
                Span("t.schemata", 5, 47, 5, 53),
                Span("t.schemata", 5, 56, 5, 60),
                Span("t.schemata", 5, 63, 5, 66),
            ),
            r.diagnostics.map { it.span },
        )
        assertNull(r.file)
    }
}
