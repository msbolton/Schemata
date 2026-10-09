package io.schemata.lang.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatterServicesTest {
    private fun fmt(text: String) =
        (Formatter.format(text, "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `services format canonically and idempotently`() {
        val input =
            """
            schema t
            model A { #1 x int32 }
            // before
            /// Orders.
            service   Orders   {
                #1   get(A) :   A    get    "/a/{x}"   // trailing
              #10 list( A ) : stream   A
              #2 up(stream A)
              #3 ping()  post "/ping"
              reserved #4,  "old"
            }
            service Empty {}
            """
                .trimIndent() + "\n"
        val expected =
            """
            schema t

            model A { #1 x int32 }

            // before
            /// Orders.
            service Orders {
              #1  get(A): A  get "/a/{x}"  // trailing
              #10 list(A): stream A
              #2  up(stream A)
              #3  ping()  post "/ping"
              reserved #4, "old"
            }

            service Empty {}
            """
                .trimIndent() + "\n"
        val once = fmt(input)
        assertEquals(expected, once)
        assertEquals(expected, fmt(once))
    }

    @Test
    fun `a service keeps its position among declarations and its comments`() {
        val input =
            "schema t\nmodel A { #1 x int32 }\nservice S {\n  // inside\n  #1 a(A): A\n  // end\n}\nmodel B { #1 y int32 }\n"
        assertEquals(
            "schema t\n\nmodel A { #1 x int32 }\n\nservice S {\n  // inside\n  #1 a(A): A\n  // end\n}\n\nmodel B { #1 y int32 }\n",
            fmt(input),
        )
    }

    @Test
    fun `annotations docs and header comments on a service and its operations`() {
        val input =
            "schema t\nmodel A { #1 x int32 }\n@deprecated service S { // header\n  /// Gets.\n  @deprecated #1 a(A): A /* c */\n  @a(x: 1, y: 2) // on ann\n  #2 b() delete \"/b\"\n} // after\nservice E {\n  // only\n}\n"
        val expected =
            "schema t\n\nmodel A { #1 x int32 }\n\n@deprecated\nservice S {  // header\n  /// Gets.\n  @deprecated #1 a(A): A  /* c */\n  @a(x: 1, y: 2)  // on ann\n  #2 b()  delete \"/b\"\n}  // after\n\nservice E {\n  // only\n}\n"
        val once = fmt(input)
        assertEquals(expected, once)
        assertEquals(expected, fmt(once))
    }

    @Test
    fun `a path longer than the width stays whole on one line`() {
        val path = "/" + "segment-".repeat(14) + "end/{identifier}"
        val out =
            fmt("schema t\nmodel A { #1 x int32 }\nservice S {\n  #1 a(A): A  get \"$path\"\n}\n")
        assertTrue("    get \"$path\"\n" in out, out)
        assertEquals(out, fmt(out))
    }

    @Test
    fun `an operation written across lines keeps its binding on its own line`() {
        val input =
            "schema t\nmodel A { #1 x int32 }\nservice S {\n  #1 a(A): A\n      get \"/a\"\n  #2 b(A): A  get \"/b\"\n}\n"
        val expected =
            "schema t\n\nmodel A { #1 x int32 }\n\nservice S {\n  #1 a(A): A\n    get \"/a\"\n  #2 b(A): A  get \"/b\"\n}\n"
        val out = fmt(input)
        assertEquals(expected, out)
        assertEquals(out, fmt(out))
    }

    @Test
    fun `a long operation wraps its binding`() {
        val long =
            "#1 find_the_order_by_its_identifier(OrderIdentifierRequest): OrderIdentifierResponse  get \"/orders/by-identifier/{identifier}/details\""
        val input =
            "schema t\nmodel OrderIdentifierRequest { #1 identifier string }\nmodel OrderIdentifierResponse { #1 x int32 }\nservice S {\n  $long\n}\n"
        val out = fmt(input)
        assertTrue(
            "  #1 find_the_order_by_its_identifier(OrderIdentifierRequest): OrderIdentifierResponse\n    get \"/orders/by-identifier/{identifier}/details\"\n" in
                out,
            out,
        )
        assertEquals(out, fmt(out))
    }
}
