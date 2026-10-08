package io.schemata.lang.upgrade

import io.schemata.lang.Parser
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpgraderTest {
    private fun up(text: String) =
        (Upgrader.upgrade(text.trimIndent(), "t.schemata") as FormatResult.Formatted).text

    @Test
    fun `the shop order upgrades to the 2 surface`() {
        assertEquals(
            """
            /// Order management for the storefront.
            schema shop.orders @sql(schema: "shop")

            import shop.customers

            alias Email = string { max 254, match "^[^@]+@[^@]+$" }

            enum Status { pending paid }

            union Payment = Card | Cash

            /// A customer's order.
            model Order {
              #1 id       uuid     { id }
              #2 customer Customer
              #3 lines    Line[]   { minItems 1 }
              #4 shipping Address  { embed }
              #5 note     string?  { max 500 }
              #6 created  instant? @deprecated("use placed_at")
              reserved #7, "legacy_ref"

              model Line { #1 sku string { max 64 }  #2 quantity int32 { min 1 } }
            }
            """
                .trimIndent() + "\n",
            up(
                """
                /// Order management for the storefront.
                @sql(schema = "shop")
                namespace shop.orders

                import shop.customers

                alias Email = string(max = 254, pattern = "^[^@]+@[^@]+$")

                enum Status { pending, paid }

                union Payment = Card | Cash

                /// A customer's order.
                record Order {
                  @sql(key) #1 id: uuid
                  #2 customer: Customer
                  #3 lines: list<Line>(min = 1)
                  @sql(strategy = embed) #4 shipping: Address
                  #5 note: string(max = 500)?
                  @deprecated("use placed_at") #6 created: instant?
                  reserved #7, "legacy_ref"

                  record Line { #1 sku: string(max = 64) #2 quantity: int32(min = 1) }
                }
            """
            ),
        )
    }

    @Test
    fun `record-level annotations become block attributes and a tuple key becomes id`() {
        assertEquals(
            "schema s\n\nmodel M {\n  a uuid\n  b string\n\n  @@id(a, b)\n  @@sql(table: \"m\")\n}\n",
            up("namespace s\n@sql(table = \"m\") @sql(key = (a, b)) record M { a: uuid b: string }"),
        )
    }

    @Test
    fun `list element refinements and list bounds land in one block`() {
        assertEquals(
            "schema s\n\nmodel M { tags string[] { minItems 1, maxItems 5, max 20 } }\n",
            up("namespace s\nrecord M { tags: list<string(max = 20)>(min = 1, max = 5) }"),
        )
    }

    @Test
    fun `comments and docs survive the upgrade`() {
        val out = up("namespace s\n// keep me\nrecord M {\n  a: int32  // trailing\n}\n")
        assertTrue(out.contains("// keep me"))
        assertTrue(out.contains("// trailing"))
    }

    @Test
    fun `a 2 file is left alone and a non-1 file fails`() {
        assertTrue(
            Upgrader.upgrade("schema s\nmodel M { a int32 }", "t.schemata")
                is FormatResult.Formatted
        )
        assertTrue(Upgrader.upgrade("not a schema", "t.schemata") is FormatResult.Failed)
    }

    @Test
    fun `a 2 file comes back byte for byte`() {
        val text = "schema s\nmodel M {   a int32 }"
        assertEquals(text, up(text))
    }

    @Test
    fun `list nullability maps and type arguments carry their options`() {
        assertEquals(
            "schema s\n\nmodel M { a string?[]?  b map<string { max 10 }, int32> { minItems 1 }  c decimal(19, 4) { min 0 } }\n",
            up(
                "namespace s\nrecord M { a: list<string?>? b: map<string(max = 10), int32>(min = 1) c: decimal(19, 4, min = 0) }"
            ),
        )
    }

    @Test
    fun `an sql annotation keeps its other arguments and loses the flags`() {
        assertEquals(
            "schema s\n\nmodel M { a uuid { id, unique } @sql(column: \"a_id\")  b string { index } @sql(strategy: json) }\n",
            up(
                "namespace s\nrecord M {\n@sql(key, unique, column = \"a_id\") a: uuid\n@sql(index, strategy = json) b: string\n}"
            ),
        )
    }

    @Test
    fun `a comment on a dropped annotation moves above its field`() {
        assertEquals(
            "schema s\n\nmodel M {\n  // the key\n  a uuid   { id }\n  @deprecated(\"x\")  // why\n  b string\n}\n",
            up(
                "namespace s\nrecord M {\n@sql(key)  // the key\na: uuid\n@deprecated(\"x\")  // why\nb: string\n}"
            ),
        )
    }

    @Test
    fun `a split record key keeps the remaining arguments in their place`() {
        assertEquals(
            "schema s\n\nmodel M {\n  a uuid\n  b string\n\n  @@id(a, b)\n  @@sql(table: \"m\")  // the table\n}\n",
            up(
                "namespace s\n@sql(key = (a, b), table = \"m\")  // the table\nrecord M { a: uuid b: string }"
            ),
        )
    }

    @Test
    fun `the upgrade is canonical 2 output`() {
        val out =
            up(
                "@sql(schema = \"x\")  // first\n@doc(\"y\")  // second\nnamespace s\nimport a.b\nrecord M { @sql(key) #1 a: uuid #2 tags: list<string(pattern = \"^a\")> }\nservice S { #1 get(M): M }"
            )
        val again = Formatter.format(out, "t.schemata")
        assertEquals(FormatResult.Formatted(out), again, out)
        assertTrue(
            out.startsWith("// first\nschema s @sql(schema: \"x\") @doc(\"y\")  // second\n"),
            out,
        )
    }

    @Test
    fun `a refinement with no 2 spelling fails the upgrade`() {
        val r =
            Upgrader.upgrade(
                "namespace s\nservice S { #1 get(string(max = 5)): string }",
                "t.schemata",
            )
        assertTrue(r is FormatResult.Failed && r.diagnostics.single().code.id == "SCH0001", "$r")
    }

    @Test
    fun `a refined union member upgrades to member options`() {
        assertEquals(
            "schema s\n\nunion U = #1 string { max 5, match \"^a\" } | #2 int32\n",
            up("namespace s\nunion U = #1 string(max = 5, pattern = \"^a\") | #2 int32"),
        )
    }

    @Test
    fun `a namespace named schema becomes schema_value with its sql name preserved`() {
        assertEquals(
            "schema schema_value @sql(schema: \"schema\")\n\nmodel R { a int32 }\n",
            up("namespace schema\nrecord R { a: int32 }"),
        )
        assertEquals(
            "schema shop.schema_value @sql(schema: \"x\")\n\nmodel R { a int32 }\n",
            up("@sql(schema = \"x\")\nnamespace shop.schema\nrecord R { a: int32 }"),
        )
        assertEquals(
            "schema schema_value.orders @sql(schema: \"orders\")\n\nimport schema_value\n\nmodel R { a schema_value.T }\n",
            up("namespace schema.orders\nimport schema\nrecord R { a: schema.T }"),
        )
    }

    @Test
    fun `a field named model is renamed with its column preserved`() {
        assertEquals(
            """
            schema s

            import other as model_value

            enum E {
              @proto(name: "E_MODEL")
              @xsd(name: "model")
              @jsonschema(name: "model")
              model_value
              other
            }

            model R { model_value string @sql(column: "model")  e E = model_value  t model_value.T }

            service S {
              #1 model_value(R): R
            }
            """
                .trimIndent() + "\n",
            up(
                """
                namespace s
                import other as model
                enum E { model, other }
                record R { model: string e: E = model t: model.T }
                service S { #1 model(R): R }
                """
                    .trimIndent()
            ),
        )
    }

    @Test
    fun `a rename that collides is an upgrade error`() {
        val r =
            Upgrader.upgrade(
                "namespace s\nrecord R { model: string model_value: int32 }\nenum E { schema, schema_value }",
                "t.schemata",
            )
        assertTrue(r is FormatResult.Failed, "$r")
        assertEquals(
            listOf(
                "cannot rename 'model': 'model_value' is already declared",
                "cannot rename 'schema': 'schema_value' is already declared",
            ),
            r.diagnostics.map { it.message },
        )
        assertEquals("SCH0001", r.diagnostics.first().code.id)
    }

    @Test
    fun `map moves refinements into options and flags`() {
        val file =
            Upgrader.map(
                Parser.parse1ForUpgrade(
                        "namespace s\nrecord M { @sql(key) a: string(max = 5) }",
                        "t",
                    )
                    .file!!
            )
        val a = (file.declarations.single() as RecordDecl).fields.single()
        assertEquals(listOf("id", "max"), a.options.map(Option::name))
        assertTrue(a.type.refinements.isEmpty() && a.annotations.isEmpty())
    }

    @Test
    fun `every keyword rename is reported as a warning naming both spellings`() {
        val r =
            Upgrader.upgrade(
                "namespace s.schema\nimport other as model\nenum E { model, other }\n" +
                    "record R { model: string }\nservice S { #1 schema(R): R }",
                "t.schemata",
            )
        assertTrue(r is FormatResult.Formatted, "$r")
        assertEquals(
            listOf(
                "schema 's.schema' is renamed 's.schema_value', since 2.0 keeps `schema` as a keyword",
                "import alias 'model' is renamed 'model_value', since 2.0 keeps `model` as a keyword",
                "enum value 'model' is renamed 'model_value', since 2.0 keeps `model` as a keyword",
                "field 'model' is renamed 'model_value', since 2.0 keeps `model` as a keyword",
                "operation 'schema' is renamed 'schema_value', since 2.0 keeps `schema` as a keyword",
            ),
            r.warnings.map { it.message },
        )
        assertTrue(r.warnings.all { it.code.id == "SCH0009" })
        assertTrue(r.warnings[2].help!!.contains("WHERE <column> = 'model'"), r.warnings[2].help)
    }

    @Test
    fun `a renamed enum value keeps the overrides it already has`() {
        val text =
            up(
                """
                namespace s
                @proto(name = "Kind")
                enum E { @xsd(name = "M") model, other }
                record R { e: E }
                """
            )
        assertTrue("@xsd(name: \"M\")" in text, text)
        assertTrue("@proto(name: \"KIND_MODEL\")" in text, text)
        assertEquals(1, Regex("@xsd").findAll(text).count(), text)
    }

    @Test
    fun `a schema renamed onto another file's schema is an upgrade error`() {
        val r =
            Upgrader.upgrade(
                "namespace shop.schema\nrecord R { a: int32 }",
                "t.schemata",
                setOf("shop.schema", "shop.schema_value"),
            )
        assertTrue(r is FormatResult.Failed, "$r")
        assertEquals(
            "cannot rename schema 'shop.schema': another file declares 'shop.schema_value', and the two would merge",
            r.diagnostics.single().message,
        )
        val alone =
            Upgrader.upgrade(
                "namespace shop.schema\nrecord R { a: int32 }",
                "t.schemata",
                setOf("shop.schema"),
            )
        assertTrue(alone is FormatResult.Formatted, "$alone")
    }

    @Test
    fun `a list of size-bounded maps keeps its outer list`() {
        assertEquals(
            "schema s\n\nmodel R { m list<map<string, int32> { maxItems 3 }> { maxItems 10 } }\n",
            up("namespace s\nrecord R { m: list<map<string, int32>(max = 3)>(max = 10) }"),
        )
    }

    @Test
    fun `header comments keep their order when annotations move onto the header`() {
        val text =
            up(
                "// on sql\n@sql(schema = \"x\")\n// on namespace\nnamespace s\nrecord R { a: int32 }"
            )
        assertTrue(text.indexOf("// on sql") < text.indexOf("// on namespace"), text)
    }

    @Test
    fun `the round-trip shape ignores layout and sees a changed type`() {
        fun shape(text: String) = Upgrader.shape(Parser.parse(text, "t.schemata").file!!)
        val one = "schema s\nmodel R { a int32 @deprecated(\"x\")  b string }\n"
        val many = "schema s\n\nmodel R {\n  @deprecated(\"x\")\n  a int32\n  b   string\n}\n"
        assertEquals(shape(one), shape(many))
        assertTrue(shape(one) != shape(one.replace("b string", "b int32")))
    }
}
