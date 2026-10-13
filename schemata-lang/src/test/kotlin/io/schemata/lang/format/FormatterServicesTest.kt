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

    @Test
    fun `success codes and errors clauses format canonically and idempotently`() {
        val input =
            """
            schema t
            model A { #1 x int32 }
            model Problem { #1 title string }
            service Orders {
                errors {   500 Problem
              default   Problem }
              #1 get(A) :   A  get   "/a/{x}"   errors {404   Problem 410}
              #2 create( A ): A  post   "/a"   201
              #3 ping()   errors { }
            }
            """
                .trimIndent() + "\n"
        val expected =
            """
            schema t

            model A { #1 x int32 }

            model Problem { #1 title string }

            service Orders {
              errors {
                500 Problem
                default Problem
              }
              #1 get(A): A  get "/a/{x}"  errors { 404 Problem  410 }
              #2 create(A): A  post "/a" 201
              #3 ping()  errors {}
            }
            """
                .trimIndent() + "\n"
        val once = fmt(input)
        assertEquals(expected, once)
        assertEquals(expected, fmt(once))
    }

    @Test
    fun `a clause wider than the line or broken by its author takes one code per line`() {
        val long =
            "#1 create(NewOrderRequest): OrderResponse  post \"/orders\" 201  errors { 400 Problem  409 Conflict  422 ValidationProblem  default Problem }"
        val input = "schema t\nservice S {\n  $long\n  #2 a(A): A  errors {\n 404\n }\n}\n"
        val expected =
            "schema t\n\nservice S {\n" +
                "  #1 create(NewOrderRequest): OrderResponse  post \"/orders\" 201  errors {\n" +
                "    400 Problem\n    409 Conflict\n    422 ValidationProblem\n    default Problem\n  }\n" +
                "  #2 a(A): A  errors {\n    404\n  }\n}\n"
        val out = fmt(input)
        assertEquals(expected, out)
        assertEquals(out, fmt(out))
    }

    @Test
    fun `a service clause keeps its place among the operations`() {
        val input =
            "schema t\nservice S {\n  #1 a(A): A\n  errors   { 500  default }\n  #2 b(A): A  errors { 404 }\n}\n"
        val expected =
            "schema t\n\nservice S {\n  #1 a(A): A\n  errors { 500  default }\n  #2 b(A): A  errors { 404 }\n}\n"
        val out = fmt(input)
        assertEquals(expected, out)
        assertEquals(out, fmt(out))
    }

    @Test
    fun `comments inside and around errors clauses survive formatting`() {
        val input =
            """
            schema t
            service S {
              errors {  // header
                // lead
                500 Problem  // five hundred
                // end
              }  // after
              #1 a(A): A  get "/a"  errors { 404 Problem }  // op
              #2 b(A): A  errors {
                409 Conflict  /* c */
              }
              #3 c(A): A  // sig
                get "/c"  errors { 404 }
            }
            """
                .trimIndent() + "\n"
        val expected =
            """
            schema t

            service S {
              errors {  // header
                // lead
                500 Problem  // five hundred
                // end
              }  // after
              #1 a(A): A  get "/a"  errors { 404 Problem }  // op
              #2 b(A): A  errors {
                409 Conflict  /* c */
              }
              #3 c(A): A
                get "/c"  errors { 404 }  // sig
            }
            """
                .trimIndent() + "\n"
        val once = fmt(input)
        assertEquals(expected, once)
        assertEquals(expected, fmt(once))
    }

    @Test
    fun `a success code prints after the path when the binding wraps`() {
        val path = "/" + "segment-".repeat(6) + "end/{identifier}"
        val out =
            fmt(
                "schema t\nservice S {\n  #1 find_the_order_by_its_identifier(A): A  post \"$path\" 201  errors { 404 }\n}\n"
            )
        assertTrue("    post \"$path\" 201  errors { 404 }\n" in out, out)
        assertEquals(out, fmt(out))
    }
}
