package io.schemata.lang.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {
    private fun fmt(text: String) =
        (Formatter.format(text, "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `a byte-order mark is read and never written`() {
        assertEquals(
            "namespace a\n\nrecord R { #1 x: bool }\n",
            fmt("\uFEFFnamespace a\nrecord R { #1 x: bool }"),
        )
    }

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
    fun `reserved statements keep their place among fields`() {
        val input =
            "namespace t\nrecord R {\n#1 a: bool\nreserved #2, \"old\"\n#3 b: bool\nreserved #4..#6\n}\n"
        assertEquals(
            "namespace t\n\nrecord R {\n  #1 a: bool\n  reserved #2, \"old\"\n  #3 b: bool\n  reserved #4..#6\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `reserved statements keep their own trailing comments`() {
        val input =
            "namespace t\nrecord R {\nreserved #7 // r1\n#4 d: bool\nreserved #8, // r2\n#9\n}\n"
        assertEquals(
            "namespace t\n\nrecord R {\n  reserved #7  // r1\n  #4 d: bool\n  reserved #8, #9  // r2\n}\n",
            fmt(input),
        )
    }

    @Test
    fun `a comment inside a member's type trails the member and formatting is idempotent`() {
        val union = "namespace t\nunion U = #1 list<\n// why\nA> | #2 B\n"
        val unionOut = "namespace t\n\nunion U =\n  #1 list<A> |  // why\n  #2 B\n"
        assertEquals(unionOut, fmt(union))
        assertEquals(unionOut, fmt(unionOut))
        val record = "namespace t\nrecord R {\n#1 a: bool\n#2 b: list<\n// why\nint32>\n}\n"
        val recordOut = "namespace t\n\nrecord R {\n  #1 a: bool\n  #2 b: list<int32>  // why\n}\n"
        assertEquals(recordOut, fmt(record))
        assertEquals(recordOut, fmt(recordOut))
    }

    @Test
    fun `a union broken only by a comment prints it on the member's line`() {
        val input = "namespace t\nunion U = #1 A // first\n| #2 B\n"
        val expected = "namespace t\n\nunion U =\n  #1 A |  // first\n  #2 B\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `a comment trailing a union's last member reads as the union's own and keeps one line`() {
        val input = "namespace t\nunion U = #1 A | #2 list<\n// why\nB>\n"
        val expected = "namespace t\n\nunion U = #1 A | #2 list<B>  // why\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `two line comments due on one line keep the last there and the first above`() {
        val record = "namespace t\nrecord R {\n#1 a: list<\n// c\nint32> // d\n}\n"
        val recordOut = "namespace t\n\nrecord R {\n  // c\n  #1 a: list<int32>  // d\n}\n"
        assertEquals(recordOut, fmt(record))
        assertEquals(recordOut, fmt(recordOut))
        val union = "namespace t\nunion U = #1 A | #2 list<\n// m\nB> // u\n"
        val unionOut = "namespace t\n\nunion U =\n  #1 A |\n  // m\n  #2 list<B>  // u\n"
        assertEquals(unionOut, fmt(union))
        assertEquals(unionOut, fmt(unionOut))
    }

    @Test
    fun `a comment on the opening brace's line stays there and breaks the body`() {
        val input = "namespace t // ns\nrecord R { // c\n#1 a: bool\n}\nenum E // e\n{ #1 v }\n"
        val expected =
            "namespace t  // ns\n\nrecord R {  // c\n  #1 a: bool\n}\n\nenum E {  // e\n  #1 v\n}\n"
        assertEquals(expected, fmt(input))
        assertEquals(expected, fmt(expected))
    }

    @Test
    fun `file header comments keep their place`() {
        val input = "// top\n/// doc\n// between\n@a\n@b // on b\n// before ns\nnamespace t\n"
        val expected = "// top\n/// doc\n// between\n@a\n@b  // on b\n// before ns\nnamespace t\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `width counts code points so astral characters do not break a line early`() {
        val rockets = "\uD83D\uDE80".repeat(70)
        val oneLine = "record R { #1 s: string = \"$rockets\" }"
        assertEquals(100, oneLine.codePointCount(0, oneLine.length))
        assertEquals(
            "namespace t\n\n$oneLine\n",
            fmt("namespace t\nrecord R {\n#1 s: string = \"$rockets\"\n}\n"),
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

    @Test
    fun `an empty record or enum prints braces with no inner space`() {
        assertEquals("namespace t\n\nrecord Cash {}\n", fmt("namespace t\nrecord Cash {}\n"))
        assertEquals("namespace t\n\nenum Empty {}\n", fmt("namespace t\nenum Empty {}\n"))
    }

    @Test
    fun `comments on imports and every declaration kind are kept`() {
        val input =
            "namespace t\n// lead-import\nimport x.y  // trail-import\nrecord R { #1 a: bool }  // trail-record\nenum E { #1 v }  // trail-enum\nunion U = #1 R | #2 E  // trail-union\nalias A = int64  // trail-alias\n"
        val expected =
            "namespace t\n\n// lead-import\nimport x.y  // trail-import\n\nrecord R { #1 a: bool }  // trail-record\n\nenum E { #1 v }  // trail-enum\n\nunion U = #1 R | #2 E  // trail-union\n\nalias A = int64  // trail-alias\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a broken union has no leading pipe and appends it to every member but the last`() {
        val input =
            "namespace t\nunion U = #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | #2 bbb\n"
        val expected =
            "namespace t\n\nunion U =\n  #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa |\n  #2 bbb\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a broken union prints a documented member's doc above its own line`() {
        val input =
            "namespace t\nunion U = #1 A |\n/// second\n#2 B\nrecord A { #1 a: bool }\nrecord B { #1 b: bool }\n"
        val expected =
            "namespace t\n\nunion U =\n  #1 A |\n  /// second\n  #2 B\n\nrecord A { #1 a: bool }\n\nrecord B { #1 b: bool }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a comment between a union member's doc and ordinal prints above its line and reparses`() {
        val input =
            "namespace t\nunion U = #1 A |\n/// d\n// c\n#2 B\nrecord A { #1 a: bool }\nrecord B { #1 b: bool }\n"
        val expected =
            "namespace t\n\nunion U =\n  #1 A |\n  // c\n  /// d\n  #2 B\n\nrecord A { #1 a: bool }\n\nrecord B { #1 b: bool }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `astral characters before a literal do not shift its slice`() {
        val input = "namespace t\nrecord R {\n@sql(note = \"🚀 fast\") #1 x: string(max = 10)\n}\n"
        val expected =
            "namespace t\n\nrecord R { @sql(note = \"🚀 fast\") #1 x: string(max = 10) }\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `a comment on an annotation line and one before a declaration keyword print where they attach`() {
        val input =
            "namespace t\n@sql(table = \"x\")\n// note\nrecord R {\n  @sql(key)  // pk\n  #1 id: int64\n  #2 code: string\n}\n"
        val expected =
            "namespace t\n\n// note\n@sql(table = \"x\")\nrecord R {\n  @sql(key)  // pk\n  #1 id:   int64\n  #2 code: string\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `doc text keeps indentation past the canonical single leading space`() {
        val input = "namespace t\nrecord R {\n///     code\n#1 a: bool\n}\n"
        val expected = "namespace t\n\nrecord R {\n  ///     code\n  #1 a: bool\n}\n"
        assertEquals(expected, fmt(input))
    }

    @Test
    fun `every fixture's canonical output is idempotent`() {
        val fixtures =
            listOf(
                "namespace a.b\n\nimport x.y\nimport z as q\n\nrecord R { #1 a: bool }\n\nenum E { #1 v }\n",
                "namespace t\n\nrecord R {\n  #1  id:    int64\n  #12 name:  string(max = 100)?\n  #3  tags:  list<string>(min = 1)\n  #4  notes: string(max = 2000)?\n}\n",
                "namespace t\n\nrecord R { #1 a: bool }\n",
                "namespace t\n\nrecord R {\n  #1 a_very_long_field_name_number_one: string(max = 254, pattern = \"^[^@]+@[^@]+$\")\n  #2 another_long_field_name:           list<string>(min = 1, max = 10)\n}\n",
                "namespace t\n\nrecord R {\n  /// the id\n  @sql(key) #1 id:   int64\n  @sql(unique, index)\n  #2 code: string\n}\n",
                "namespace t\n\n/// A record.\n@sql(table = \"r\")\n@proto(name = \"RR\")\nrecord R { #1 a: bool }\n",
                "namespace t\n\nenum E {\n  #1  a\n  #10 bb\n  reserved #2\n}\n\nunion U = #1 E | #2 R\n\nrecord R { #1 a: bool }\n",
                "namespace t\n\nrecord R {\n  #1 a: bool\n  reserved #2, \"old\"\n  #3 b: bool\n  reserved #4..#6\n}\n",
                "namespace t\n\nrecord O {\n  #1 l: list<L>\n\n  record L { #1 sku: string }\n\n  #2 n: int32\n}\n",
                "namespace t\n\nrecord R { #1 p: decimal(19, 4) = 1.00 #2 s: string = \"a\\\"b\" #3 f: float64 = 1.50 }\n",
                "// top\nnamespace t\n\n// about R\nrecord R {\n  // lead a\n  #1 a: bool  // trail a\n  #2 b: bool\n  // end\n}\n\n// bye\n",
                "namespace t\n\nrecord Cash {}\n",
                "namespace t\n\nenum Empty {}\n",
                "namespace t\n\n// lead-import\nimport x.y  // trail-import\n\nrecord R { #1 a: bool }  // trail-record\n\nenum E { #1 v }  // trail-enum\n\nunion U = #1 R | #2 E  // trail-union\n\nalias A = int64  // trail-alias\n",
                "namespace t\n\nunion U =\n  #1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa |\n  #2 bbb\n",
                "namespace t\n\nunion U =\n  #1 A |\n  /// second\n  #2 B\n\nrecord A { #1 a: bool }\n\nrecord B { #1 b: bool }\n",
                "namespace t\n\nrecord R { @sql(note = \"🚀 fast\") #1 x: string(max = 10) }\n",
                "namespace t\n\n// note\n@sql(table = \"x\")\nrecord R {\n  @sql(key)  // pk\n  #1 id:   int64\n  #2 code: string\n}\n",
                "namespace t\n\nrecord R {\n  ///     code\n  #1 a: bool\n}\n",
                "namespace t\n\nunion U =\n  #1 A |\n  // c\n  /// d\n  #2 B\n\nrecord A { #1 a: bool }\n\nrecord B { #1 b: bool }\n",
            )
        fixtures.forEach { assertEquals(it, fmt(it), "not idempotent: $it") }
    }

    @Test
    fun `CRLF and lone CR line endings become LF even inside a comment`() {
        val input = "namespace t\r\nrecord R {\r\n#1 a: bool /* x\r\ny */\r#2 b: bool\r\n}\r\n"
        val expected = "namespace t\n\nrecord R {\n  #1 a: bool  /* x\ny */\n  #2 b: bool\n}\n"
        assertEquals(expected, fmt(input))
    }
}
