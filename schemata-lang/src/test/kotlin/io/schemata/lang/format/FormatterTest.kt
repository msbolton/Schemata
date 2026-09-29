package io.schemata.lang.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {
    private fun fmt(text: String) =
        (Formatter.format(text, "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `file layout namespace imports declarations one blank line between`() {
        val input =
            "namespace a.b\nimport x.y\nimport z as q\nrecord R { #1 a: bool }\nenum E { #1 v }\n"
        val expected =
            "namespace a.b\n\nimport x.y\nimport z as q\n\nrecord R { #1 a: bool }\n\nenum E { #1 v }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a multi-line record aligns ordinals names and types`() {
        val input =
            "namespace t\nrecord R {\n#1 id: int64\n#12 name:string(max=100)?\n#3 tags:list<string>(min=1)\n#4 notes:string(max=2000)?\n}\n"
        val expected =
            "namespace t\n\nrecord R {\n  #1  id:    int64\n  #12 name:  string(max = 100)?\n  #3  tags:  list<string>(min = 1)\n  #4  notes: string(max = 2000)?\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a short record stays on one line and a long one breaks`() {
        val short = "namespace t\nrecord R {\n#1 a: bool\n}\n"
        assertEquals("namespace t\n\nrecord R { #1 a: bool }\n", fmt(short))
        val long =
            "namespace t\nrecord R { #1 a_very_long_field_name_number_one: string(max = 254, pattern = \"^[^@]+@[^@]+$\")  #2 another_long_field_name: list<string>(min = 1, max = 10) }\n"
        assertEquals(
            "namespace t\n\nrecord R {\n  #1 a_very_long_field_name_number_one: string(max = 254, pattern = \"^[^@]+@[^@]+$\")\n  #2 another_long_field_name:           list<string>(min = 1, max = 10)\n}\n",
            fmt(long),
        )
    }

    @Test
    fun `doc comments and multi-arg annotations force a multi-line body`() {
        val input =
            "namespace t\nrecord R { /// the id\n@sql(key) #1 id: int64 @sql(unique, index) #2 code: string }\n"
        val expected =
            "namespace t\n\nrecord R {\n  /// the id\n  @sql(key) #1 id:   int64\n  @sql(unique, index)\n  #2 code: string\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `declaration annotations go above and doc comes first`() {
        val input =
            "namespace t\n/// A record.\n@sql(table = \"r\") @proto(name = \"RR\") record R { #1 a: bool }\n"
        assertEquals(
            "namespace t\n\n/// A record.\n@sql(table = \"r\")\n@proto(name = \"RR\")\nrecord R { #1 a: bool }\n",
            fmt(input),
        )
    }

    @Test
    fun `enums align ordinals and unions break at the width`() {
        val input =
            "namespace t\nenum E {\n#1 a,\n#10 bb\nreserved #2\n}\nunion U = #1 E | #2 R\nrecord R { #1 a: bool }\n"
        assertEquals(
            "namespace t\n\nenum E {\n  #1  a\n  #10 bb\n  reserved #2\n}\n\nunion U = #1 E | #2 R\n\nrecord R { #1 a: bool }\n",
            fmt(input),
        )
    }

    @Test
    fun `reserved keeps its place among fields and merges into one statement`() {
        val input =
            "namespace t\nrecord R {\n#1 a: bool\nreserved #2, \"old\"\n#3 b: bool\nreserved #4..#6\n}\n"
        assertEquals(
            "namespace t\n\nrecord R {\n  #1 a: bool\n  reserved #2, \"old\", #4..#6\n  #3 b: bool\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `nested declarations keep source order and indent`() {
        val input =
            "namespace t\nrecord O {\n#1 l: list<L>\nrecord L { #1 sku: string }\n#2 n: int32\n}\n"
        assertEquals(
            "namespace t\n\nrecord O {\n  #1 l: list<L>\n\n  record L { #1 sku: string }\n\n  #2 n: int32\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `literals keep their spelling`() {
        val input =
            "namespace t\nrecord R { #1 p: decimal(19,4)=1.00 #2 s: string=\"a\\\"b\" #3 f: float64 = 1.50 }\n"
        assertEquals(
            "namespace t\n\nrecord R { #1 p: decimal(19, 4) = 1.00 #2 s: string = \"a\\\"b\" #3 f: float64 = 1.50 }\n",
            fmt(input),
        )
    }

    @Test
    fun `comments are printed where they were attached`() {
        val input =
            "// top\nnamespace t\n// about R\nrecord R {\n  // lead a\n  #1 a: bool // trail a\n  #2 b: bool\n  // end\n}\n// bye\n"
        val expected =
            "// top\nnamespace t\n\n// about R\nrecord R {\n  // lead a\n  #1 a: bool  // trail a\n  #2 b: bool\n  // end\n}\n\n// bye\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `formatting is idempotent on its own output`() {
        val once = fmt("namespace t\nrecord R {\n#1 a: bool\n/// d\n#2 b: string(max=3)?\n}\n")
        assertEquals(once, fmt(once))
    }
}
