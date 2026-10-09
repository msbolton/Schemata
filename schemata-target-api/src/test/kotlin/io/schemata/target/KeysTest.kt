package io.schemata.target

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Relation
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.lang.Parser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MODELS =
    """
schema t

enum Level { low high }

model User { #1 id uuid { id } }

model Account { #1 user_id uuid  @@id(user_id) }

model Order { #1 id uuid { id } }

model Line { #1 order_id uuid  #2 n int32  @@id(order_id, n) }

model Price { #1 level Level  #2 amount decimal(10, 2)  @@id(level, amount) }

model Grade { #1 level Level { id } }

model Code { #1 value string { id, max 8, match "^[A-Z]+$" } }

model Point { #1 x int32  #2 y int32 }

model Use {
  #1 account Account
  #2 line    Line
  #3 grade   Grade?
  #4 code    Code
  #5 buyer   User    @t(name: "client")
  #6 codes   Code[]
  #7 lines   Line[]
  #8 point   Point
  #9 copy    User    { embed }
  #10 labels map<string, User>
  #11 owners map<string, Line>
  #12 price  Price
}

union Party = User | Line | Point

union Hit = #1 Order { embed } | #2 Code

model Query { #1 text string }

service Search { #1 find(Query): Party  #2 hit(Query): Hit }
"""

class KeysTest {
    private val nameSpec =
        AnnotationSpec(
            "t",
            "name",
            setOf(Element.RECORD, Element.FIELD),
            ValueKind.STRING,
            Role.NAME,
        )

    private fun compile(text: String): Schema {
        val analysis =
            Analyzer.analyze(
                listOf(Parser.parse(text, "t.schemata").file!!),
                AnalysisOptions(annotations = AnnotationRegistry(CoreAnnotations.specs + nameSpec)),
            )
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!
    }

    private val schema by lazy { compile(MODELS) }
    private val keyed by lazy { schema.referencesByKey() }

    private fun qn(vararg path: String) = QualifiedName("t", path.toList())

    private fun record(schema: Schema, name: String) = schema.lookup(qn(name)) as RecordType

    private fun shape(record: RecordType): List<Triple<Int, String, Type>> =
        record.fields.map { Triple(it.ordinal, it.name, it.type) }

    @Test
    fun `the key of a model is its key fields and a value type has none`() {
        assertEquals(listOf("order_id", "n"), schema.keyOf(qn("Line"))!!.map { it.name })
        assertEquals(listOf("id"), schema.keyOf(qn("User"))!!.map { it.name })
        assertNull(schema.keyOf(qn("Point")))
        assertNull(schema.keyOf(qn("Level")))
        assertEquals(
            "buyer_id",
            referenceName(record(schema, "Use").fields[4], schema.keyOf(qn("User"))!!.single()),
        )
    }

    @Test
    fun `references carry the key and keep their ordinal and nullability`() {
        val uuid = Scalar(Builtin.UUID)
        val code = Scalar(Builtin.STRING, Refinements(max = BigDecimal(8), pattern = "^[A-Z]+$"))
        val use = record(keyed, "Use")
        assertEquals(
            listOf(
                Triple(1, "account_user_id", uuid),
                Triple(2, "line", Ref(qn("LineKey"))),
                Triple(3, "grade_level", Ref(qn("Level"))),
                Triple(4, "code_value", code),
                Triple(5, "buyer_id", uuid),
                Triple(6, "codes", ListOf(code, false)),
                Triple(7, "lines", ListOf(Ref(qn("LineKey")), false)),
                Triple(8, "point", Ref(qn("Point"))),
                Triple(9, "copy", record(schema, "Use").fields[8].type),
                Triple(10, "labels", MapOf(Scalar(Builtin.STRING), uuid, false)),
                Triple(11, "owners", MapOf(Scalar(Builtin.STRING), Ref(qn("LineKey")), false)),
                Triple(12, "price", Ref(qn("PriceKey"))),
            ),
            shape(use),
        )
        assertEquals(
            listOf(true),
            use.fields.filter { it.name == "grade_level" }.map { it.nullable },
        )
        assertEquals(
            AnnotationValue.Str("client_id"),
            use.fields.single { it.name == "buyer_id" }.annotations["t"]["name"],
        )
    }

    @Test
    fun `a composite key is one record declared beside its model`() {
        assertEquals(
            listOf(
                "Level",
                "User",
                "Account",
                "Order",
                "Line",
                "LineKey",
                "Price",
                "PriceKey",
                "Grade",
                "Code",
                "Point",
                "Use",
                "Party",
                "Hit",
                "Query",
            ),
            keyed.namespaces.single().declarations.map { it.name },
        )
        val key = record(keyed, "LineKey")
        assertEquals(
            listOf(
                Triple(1, "order_id", Scalar(Builtin.UUID)),
                Triple(2, "n", Scalar(Builtin.INT32)),
            ),
            shape(key),
        )
        assertTrue(key.fields.none { it.key })
        assertTrue(keyed.isKeyRecord(key))
        assertFalse(keyed.isKeyRecord(record(keyed, "Line")))
    }

    @Test
    fun `a model named like a key record is not one`() {
        val written =
            compile(
                "schema t\n\nmodel P { #1 a int32 { id }  #2 b int32 { id } }\n\nmodel PKey { #1 a int32  #2 b int32 }\n\nmodel U { #1 p P }\n"
            )
        assertFalse(written.isKeyRecord(record(written, "PKey")))
        assertFalse(written.isKeyRecord(record(written, "P")))
        val rewritten = written.referencesByKey()
        assertFalse(
            rewritten.isKeyRecord(
                rewritten.namespaces.single().declarations.last { it.name == "PKey" }
            )
        )
    }

    @Test
    fun `a self-referencing composite key terminates and marks one key record`() {
        val node =
            compile(
                    "schema t\n\nmodel Node { #1 a int32 { id }  #2 b int32 { id }  #3 parent Node? }\n"
                )
                .referencesByKey()
        assertEquals(
            listOf("Node" to false, "NodeKey" to true),
            node.namespaces.single().declarations.map { it.name to node.isKeyRecord(it) },
        )
    }

    @Test
    fun `a key record carries its model's name overrides`() {
        val keyed =
            compile(
                    "schema t\n\nmodel P { #1 a int32 { id }  #2 b int32 { id }  @@t(name: \"Duo\") }\n\nmodel U { #1 p P }\n"
                )
                .referencesByKey()
        assertEquals(AnnotationValue.Str("DuoKey"), record(keyed, "PKey").annotations["t"]["name"])
    }

    @Test
    fun `a schema without references to keyed models is unchanged`() {
        val plain = compile("schema t\n\nmodel P { #1 x int32 }\n\nmodel U { #1 p P }\n")
        assertEquals(plain, plain.referencesByKey())
    }

    @Test
    fun `a union member carries the key and keeps its model's name`() {
        val party = keyed.lookup(qn("Party")) as UnionType
        assertEquals(
            listOf(
                Triple(Scalar(Builtin.UUID), qn("User"), "user"),
                Triple(Ref(qn("LineKey")), qn("Line"), "line"),
                Triple(Ref(qn("Point")), null, "point"),
            ),
            party.members.map {
                Triple(it.type, it.byKey, unionMemberStem(it.named, keyed) { null })
            },
        )
    }

    @Test
    fun `a composite key of an enum and a decimal keeps both types in key order`() {
        val key = record(keyed, "PriceKey")
        assertEquals(
            listOf(
                Triple(1, "level", Ref(qn("Level"))),
                Triple(2, "amount", Scalar(Builtin.DECIMAL, Refinements(precision = 10, scale = 2))),
            ),
            shape(key),
        )
    }

    @Test
    fun `a union used as a payload carries keys and an embedded member copies the record`() {
        val hit = keyed.lookup(qn("Hit")) as UnionType
        assertEquals(
            listOf(
                Triple(Ref(qn("Order"), Relation(embed = true)), null, "order"),
                Triple(
                    Scalar(Builtin.STRING, Refinements(max = BigDecimal(8), pattern = "^[A-Z]+$")),
                    qn("Code"),
                    "code",
                ),
            ),
            hit.members.map { Triple(it.type, it.byKey, unionMemberStem(it.named, keyed) { null }) },
        )
        val party = keyed.lookup(qn("Party")) as UnionType
        assertEquals(qn("User"), party.members.first().byKey)
    }
}
