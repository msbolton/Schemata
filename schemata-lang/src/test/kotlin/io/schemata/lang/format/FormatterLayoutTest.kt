package io.schemata.lang.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatterLayoutTest {
    private fun fmt(text: String) =
        (Formatter.format(text.trimIndent(), "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `fields align in four columns and block attributes close the body`() {
        assertEquals(
            """
            schema shop.orders @sql(schema: "shop")

            model Order {
              #1 id       uuid     { id }
              #2 customer Customer @relation(onDelete: restrict)
              #3 note     string?  { max 500 } @deprecated("why") = "x"
              #4 tags     string[] { minItems 1, max 20 }

              @@timestamps
              @@sql(table: "orders")
            }
            """
                .trimIndent() + "\n",
            fmt(
                """
                schema shop.orders @sql(schema: "shop")
                model Order {
                #1 id uuid {id}
                  #2 customer Customer @relation(onDelete: restrict)
                #3 note string? {max 500} @deprecated("why") = "x"
                #4 tags string[] {minItems 1, max 20}
                @@timestamps @@sql(table: "orders") }
            """
            ),
        )
    }

    @Test
    fun `a one-member body stays on one line and inline types print inline when they fit`() {
        assertEquals(
            "schema s\n\nmodel Line { sku string { max 64 }  quantity int32 { min 1 } }\n\nmodel Order {\n  status   enum { pending paid } = pending\n  shipping { street string { max 200 }  city string }\n}\n",
            fmt(
                "schema s\nmodel Line { sku string {max 64}   quantity int32 {min 1} }\nmodel Order { status enum {pending paid} = pending\n shipping {street string {max 200} city string} }"
            ),
        )
    }

    @Test
    fun `comments inside options and block attributes survive formatting`() {
        val text =
            """
            schema s

            model M {
              a int32  // trailing
              b int32 { min 0 }  // after options

              @@sql(table: "m")  // block comment
            }
            """
                .trimIndent() + "\n"
        assertEquals(text, fmt(text))
    }

    @Test
    fun `formatting is idempotent over the parser tests' sources`() {
        val once =
            fmt(
                "schema s\nmodel M { a string?[]?  b map<string { max 10 }, int32>  c decimal(19, 4) }"
            )
        assertEquals(
            "schema s\n\nmodel M { a string?[]?  b map<string { max 10 }, int32>  c decimal(19, 4) }\n",
            once,
        )
        assertEquals(once, fmt(once))
    }

    @Test
    fun `an inline shape too wide for its line opens a body one level deeper`() {
        val out =
            fmt(
                "schema s\nmodel Order { #1 shipping { street string { max 200 }  city string { max 100 }  postcode string { max 20 } }? @deprecated(\"x\") }"
            )
        assertEquals(
            """
            schema s

            model Order {
              #1 shipping {
                street   string { max 200 }
                city     string { max 100 }
                postcode string { max 20 }
              }? @deprecated("x")
            }
            """
                .trimIndent() + "\n",
            out,
        )
        assertEquals(out, fmt(out))
    }

    @Test
    fun `a multi-line inline enum keeps its comments`() {
        val text =
            """
            schema s

            model M {
              status enum {  // the states
                pending  // not yet
                paid
              } = pending
            }
            """
                .trimIndent() + "\n"
        assertEquals(text, fmt(text))
    }

    @Test
    fun `leading attributes stay above and trailing ones never wrap`() {
        val long = "x".repeat(90)
        val out =
            fmt(
                "schema s @sql(schema: \"$long\")\nmodel M {\n@deprecated(\"old\")\na int32 @doc(\"$long\")\nb string\n}"
            )
        assertEquals(
            "schema s @sql(schema: \"$long\")\n\nmodel M {\n  @deprecated(\"old\")\n  a int32  @doc(\"$long\")\n  b string\n}\n",
            out,
        )
        assertEquals(out, fmt(out))
    }

    @Test
    fun `enum values print without commas and a declared enum breaks like a model`() {
        assertEquals(
            "schema s\n\nenum Status { pending paid shipped }\n\nenum Size {\n  /// small\n  #1 s\n  #2 m\n  reserved #3\n}\n",
            fmt(
                "schema s\nenum Status { pending, paid, shipped }\nenum Size { /// small\n#1 s #2 m reserved #3 }"
            ),
        )
    }

    @Test
    fun `aliases unions services and header comments print in the 2 surface`() {
        val text =
            """
            // file comment
            /// The doc.
            schema s @sql(schema: "x")  // header

            import other.ns as o

            alias Email = string { max 254, match "^[^@]+@[^@]+$" }

            union Payment = #1 Card | #2 Cash

            service Orders {
              @http(timeout: 5) #1 get(Email): Payment  get "/orders/{id}"
            }
            """
                .trimIndent() + "\n"
        assertEquals(text, fmt(text))
    }

    @Test
    fun `a union member's options print after its type`() {
        assertEquals(
            "schema s\n\nunion U = #1 string { max 34 } | #2 Card\n",
            fmt("schema s\nunion U = #1 string {max 34}|#2 Card"),
        )
    }

    @Test
    fun `a nested model and a reserved statement keep their places`() {
        val out =
            fmt(
                "schema s\nmodel A { #1 a int32 reserved #2 model B { x int32 } @@sql(table: \"a\") }"
            )
        assertEquals(
            "schema s\n\nmodel A {\n  #1 a int32\n  reserved #2\n\n  model B { x int32 }\n\n  @@sql(table: \"a\")\n}\n",
            out,
        )
        assertEquals(out, fmt(out))
    }

    @Test
    fun `a 1 file does not format as 2`() {
        val r = Formatter.format("namespace s\nrecord R { a: int32 }", "t.schemata")
        assertTrue(r is FormatResult.Failed)
        assertEquals(listOf("SCH0008"), r.diagnostics.map { it.code.id })
    }
}
