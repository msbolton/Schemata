package io.schemata.lang.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {
    private fun fmt(text: String) =
        (Formatter.format(text, "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `a byte-order mark is read and never written`() {
        assertEquals(
            "schema a\n\nmodel R { #1 x bool }\n",
            fmt("\uFEFFschema a\nmodel R { #1 x bool }"),
        )
    }

    @Test
    fun `file layout namespace imports declarations one blank line between`() {
        val input =
            "schema a.b\nimport x.y\nimport z as q\nmodel R { #1 a bool }\nenum E { #1 v }\n"
        val expected =
            "schema a.b\n\nimport x.y\nimport z as q\n\nmodel R { #1 a bool }\n\nenum E { #1 v }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a multi-line record aligns ordinals names and types`() {
        val input =
            "schema t\nmodel R {\n#1 id int64\n#12 name string? { max 100 }\n#3 tags string[] { minItems 1 }\n#4 notes string? { max 2000 }\n}\n"
        val expected =
            "schema t\n\nmodel R {\n  #1  id    int64\n  #12 name  string?  { max 100 }\n  #3  tags  string[] { minItems 1 }\n  #4  notes string?  { max 2000 }\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a short record stays on one line and a long one breaks`() {
        val short = "schema t\nmodel R {\n#1 a bool\n}\n"
        assertEquals("schema t\n\nmodel R { #1 a bool }\n", fmt(short))
        val long =
            "schema t\nmodel R { #1 a_very_long_field_name_number_one string { max 254, match \"^[^@]+@[^@]+$\" }  #2 another_long_field_name string[] { minItems 1, maxItems 10 } }\n"
        assertEquals(
            "schema t\n\nmodel R {\n  #1 a_very_long_field_name_number_one string   { max 254, match \"^[^@]+@[^@]+$\" }\n  #2 another_long_field_name           string[] { minItems 1, maxItems 10 }\n}\n",
            fmt(long),
        )
    }

    @Test
    fun `doc comments and multi-arg annotations force a multi-line body`() {
        val input =
            "schema t\nmodel R { /// the id\n@deprecated(\"old\") #1 id int64\n@sql(column: \"c\", type: \"text\") #2 code string }\n"
        val expected =
            "schema t\n\nmodel R {\n  /// the id\n  @deprecated(\"old\")\n  #1 id   int64\n  @sql(column: \"c\", type: \"text\")\n  #2 code string\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `declaration annotations go above and doc comes first`() {
        val input =
            "schema t\n/// A record.\n@sql(table: \"r\") @proto(name: \"RR\") model R { #1 a bool }\n"
        assertEquals(
            "schema t\n\n/// A record.\n@sql(table: \"r\")\n@proto(name: \"RR\")\nmodel R { #1 a bool }\n",
            fmt(input),
        )
    }

    @Test
    fun `enums align ordinals and unions break at the width`() {
        val input =
            "schema t\nenum E {\n#1 a,\n#10 bb\nreserved #2\n}\nunion U = #1 E | #2 R\nmodel R { #1 a bool }\n"
        assertEquals(
            "schema t\n\nenum E {\n  #1  a\n  #10 bb\n  reserved #2\n}\n\nunion U = #1 E | #2 R\n\nmodel R { #1 a bool }\n",
            fmt(input),
        )
    }

    @Test
    fun `reserved statements keep their place among fields`() {
        val input =
            "schema t\nmodel R {\n#1 a bool\nreserved #2, \"old\"\n#3 b bool\nreserved #4..#6\n}\n"
        assertEquals(
            "schema t\n\nmodel R {\n  #1 a bool\n  reserved #2, \"old\"\n  #3 b bool\n  reserved #4..#6\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `reserved statements keep their own trailing comments`() {
        val input = "schema t\nmodel R {\nreserved #7 // r1\n#4 d bool\nreserved #8, // r2\n#9\n}\n"
        assertEquals(
            "schema t\n\nmodel R {\n  reserved #7  // r1\n  #4 d bool\n  reserved #8, #9  // r2\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `a comment inside a member's type trails the member and formatting is idempotent`() {
        val union = "schema t\nunion U = #1 list<\n// why\nA> | #2 B\n"
        val unionOut = "schema t\n\nunion U =\n  #1 list<A> |  // why\n  #2 B\n"
        assertEquals(unionOut, fmt(union))
        assertEquals(unionOut, fmt(unionOut))
        val record = "schema t\nmodel R {\n#1 a bool\n#2 b list<\n// why\nint32>\n}\n"
        val recordOut = "schema t\n\nmodel R {\n  #1 a bool\n  #2 b list<int32>  // why\n}\n"
        assertEquals(recordOut, fmt(record))
        assertEquals(recordOut, fmt(recordOut))
    }

    @Test
    fun `a union broken only by a comment prints it on the member's line`() {
        val input = "schema t\nunion U = #1 A // first\n| #2 B\n"
        val expected = "schema t\n\nunion U =\n  #1 A |  // first\n  #2 B\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `a comment trailing a union's last member reads as the union's own and keeps one line`() {
        val input = "schema t\nunion U = #1 A | #2 list<\n// why\nB>\n"
        val expected = "schema t\n\nunion U = #1 A | #2 list<B>  // why\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `two line comments due on one line keep the last there and the first above`() {
        val record = "schema t\nmodel R {\n#1 a list<\n// c\nint32> // d\n}\n"
        val recordOut = "schema t\n\nmodel R {\n  // c\n  #1 a list<int32>  // d\n}\n"
        assertEquals(recordOut, fmt(record))
        assertEquals(recordOut, fmt(recordOut))
        val union = "schema t\nunion U = #1 A | #2 list<\n// m\nB> // u\n"
        val unionOut = "schema t\n\nunion U =\n  #1 A |\n  // m\n  #2 list<B>  // u\n"
        assertEquals(unionOut, fmt(union))
        assertEquals(unionOut, fmt(unionOut))
    }

    @Test
    fun `a comment on the opening brace's line stays there and breaks the body`() {
        val input = "schema t // ns\nmodel R { // c\n#1 a bool\n}\nenum E // e\n{ #1 v }\n"
        val expected =
            "schema t  // ns\n\nmodel R {  // c\n  #1 a bool\n}\n\nenum E {  // e\n  #1 v\n}\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `file header comments keep their place`() {
        val input = "// top\n/// doc\n// before ns\nschema t @a @b // on b\n"
        val expected = "// top\n/// doc\n// before ns\nschema t @a @b  // on b\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `width counts code points so astral characters do not break a line early`() {
        val rockets = "\uD83D\uDE80".repeat(72)
        val oneLine = "model R { #1 s string = \"$rockets\" }"
        assertEquals(100, oneLine.codePointCount(0, oneLine.length))
        assertEquals(
            "schema t\n\n$oneLine\n",
            fmt("schema t\nmodel R {\n#1 s string = \"$rockets\"\n}\n"),
        )
    }

    @Test
    fun `nested declarations keep source order and indent`() {
        val input = "schema t\nmodel O {\n#1 l L[]\nmodel L { #1 sku string }\n#2 n int32\n}\n"
        assertEquals(
            "schema t\n\nmodel O {\n  #1 l L[]\n\n  model L { #1 sku string }\n\n  #2 n int32\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `literals keep their spelling`() {
        val input =
            "schema t\nmodel R { #1 p decimal(19,4)=1.00 #2 s string=\"a\\\"b\" #3 f float64 = 1.50 }\n"
        assertEquals(
            "schema t\n\nmodel R { #1 p decimal(19, 4) = 1.00  #2 s string = \"a\\\"b\"  #3 f float64 = 1.50 }\n",
            fmt(input),
        )
    }

    @Test
    fun `comments are printed where they were attached`() {
        val input =
            "// top\nschema t\n// about R\nmodel R {\n  // lead a\n  #1 a bool // trail a\n  #2 b bool\n  // end\n}\n// bye\n"
        val expected =
            "// top\nschema t\n\n// about R\nmodel R {\n  // lead a\n  #1 a bool  // trail a\n  #2 b bool\n  // end\n}\n\n// bye\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `formatting is idempotent on its own output`() {
        val once = fmt("schema t\nmodel R {\n#1 a bool\n/// d\n#2 b string? { max 3 }\n}\n")
        assertEquals(once, fmt(once))
    }

    @Test
    fun `an empty record or enum prints braces with no inner space`() {
        assertEquals("schema t\n\nmodel Cash {}\n", fmt("schema t\nmodel Cash {}\n"))
        assertEquals("schema t\n\nenum Empty {}\n", fmt("schema t\nenum Empty {}\n"))
    }

    @Test
    fun `comments on imports and every declaration kind are kept`() {
        val input =
            "schema t\n// lead-import\nimport x.y  // trail-import\nmodel R { #1 a bool }  // trail-record\nenum E { #1 v }  // trail-enum\nunion U = #1 R | #2 E  // trail-union\nalias A = int64  // trail-alias\n"
        val expected =
            "schema t\n\n// lead-import\nimport x.y  // trail-import\n\nmodel R { #1 a bool }  // trail-record\n\nenum E { #1 v }  // trail-enum\n\nunion U = #1 R | #2 E  // trail-union\n\nalias A = int64  // trail-alias\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a broken union has no leading pipe and appends it to every member but the last`() {
        val input =
            "schema t\nunion U = #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | #2 bbb\n"
        val expected =
            "schema t\n\nunion U =\n  #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa |\n  #2 bbb\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a broken union prints a documented member's doc above its own line`() {
        val input =
            "schema t\nunion U = #1 A |\n/// second\n#2 B\nmodel A { #1 a bool }\nmodel B { #1 b bool }\n"
        val expected =
            "schema t\n\nunion U =\n  #1 A |\n  /// second\n  #2 B\n\nmodel A { #1 a bool }\n\nmodel B { #1 b bool }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a comment between a union member's doc and ordinal prints above its line and reparses`() {
        val input =
            "schema t\nunion U = #1 A |\n/// d\n// c\n#2 B\nmodel A { #1 a bool }\nmodel B { #1 b bool }\n"
        val expected =
            "schema t\n\nunion U =\n  #1 A |\n  // c\n  /// d\n  #2 B\n\nmodel A { #1 a bool }\n\nmodel B { #1 b bool }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `astral characters before a literal do not shift its slice`() {
        val input =
            "schema t\nmodel R {\n#1 x string { max 10 } @sql(column: \"🚀 fast\") = \"y\"\n}\n"
        val expected =
            "schema t\n\nmodel R { #1 x string { max 10 } @sql(column: \"🚀 fast\") = \"y\" }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a comment on an annotation line and one before a declaration keyword print where they attach`() {
        val input =
            "schema t\n@sql(table: \"x\")\n// note\nmodel R {\n  @deprecated  // pk\n  #1 id int64\n  #2 code string\n}\n"
        val expected =
            "schema t\n\n// note\n@sql(table: \"x\")\nmodel R {\n  @deprecated  // pk\n  #1 id   int64\n  #2 code string\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `doc text keeps indentation past the canonical single leading space`() {
        val input = "schema t\nmodel R {\n///     code\n#1 a bool\n}\n"
        val expected = "schema t\n\nmodel R {\n  ///     code\n  #1 a bool\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `every fixture's canonical output is idempotent`() {
        val fixtures =
            listOf(
                "schema a.b\n\nimport x.y\nimport z as q\n\nmodel R { #1 a bool }\n\nenum E { #1 v }\n",
                "schema t\n\nmodel R {\n  #1  id    int64\n  #12 name  string?  { max 100 }\n  #3  tags  string[] { minItems 1 }\n  #4  notes string?  { max 2000 }\n}\n",
                "schema t\n\nmodel R { #1 a bool }\n",
                "schema t\n\nmodel R {\n  #1 a_very_long_field_name_number_one string   { max 254, match \"^[^@]+@[^@]+$\" }\n  #2 another_long_field_name           string[] { minItems 1, maxItems 10 }\n}\n",
                "schema t\n\nmodel R {\n  /// the id\n  @deprecated(\"old\")\n  #1 id   int64\n  @sql(column: \"c\", type: \"text\")\n  #2 code string\n}\n",
                "schema t\n\n/// A record.\n@sql(table: \"r\")\n@proto(name: \"RR\")\nmodel R { #1 a bool }\n",
                "schema t\n\nenum E {\n  #1  a\n  #10 bb\n  reserved #2\n}\n\nunion U = #1 E | #2 R\n\nmodel R { #1 a bool }\n",
                "schema t\n\nmodel R {\n  #1 a bool\n  reserved #2, \"old\"\n  #3 b bool\n  reserved #4..#6\n}\n",
                "schema t\n\nmodel O {\n  #1 l L[]\n\n  model L { #1 sku string }\n\n  #2 n int32\n}\n",
                "schema t\n\nmodel R { #1 p decimal(19, 4) = 1.00  #2 s string = \"a\\\"b\"  #3 f float64 = 1.50 }\n",
                "// top\nschema t\n\n// about R\nmodel R {\n  // lead a\n  #1 a bool  // trail a\n  #2 b bool\n  // end\n}\n\n// bye\n",
                "schema t\n\nmodel Cash {}\n",
                "schema t\n\nenum Empty {}\n",
                "schema t\n\n// lead-import\nimport x.y  // trail-import\n\nmodel R { #1 a bool }  // trail-record\n\nenum E { #1 v }  // trail-enum\n\nunion U = #1 R | #2 E  // trail-union\n\nalias A = int64  // trail-alias\n",
                "schema t\n\nunion U =\n  #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa |\n  #2 bbb\n",
                "schema t\n\nunion U =\n  #1 A |\n  /// second\n  #2 B\n\nmodel A { #1 a bool }\n\nmodel B { #1 b bool }\n",
                "schema t\n\nmodel R { #1 x string { max 10 } @sql(column: \"🚀 fast\") = \"y\" }\n",
                "schema t\n\n// note\n@sql(table: \"x\")\nmodel R {\n  @deprecated  // pk\n  #1 id   int64\n  #2 code string\n}\n",
                "schema t\n\nmodel R {\n  ///     code\n  #1 a bool\n}\n",
                "schema t\n\nunion U =\n  #1 A |\n  // c\n  /// d\n  #2 B\n\nmodel A { #1 a bool }\n\nmodel B { #1 b bool }\n",
            )
        fixtures.forEach { assertEquals(it, fmt(it), "not idempotent: $it") }
    }

    @Test
    fun `CRLF and lone CR line endings become LF even inside a comment`() {
        val input = "schema t\r\nmodel R {\r\n#1 a bool /* x\r\ny */\r#2 b bool\r\n}\r\n"
        val expected = "schema t\n\nmodel R {\n  #1 a bool  /* x\ny */\n  #2 b bool\n}\n"
        assertEquals(expected, fmt(input))
    }
}
