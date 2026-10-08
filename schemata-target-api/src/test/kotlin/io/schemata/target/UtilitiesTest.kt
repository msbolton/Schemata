package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.declarationPath
import io.schemata.core.ir.kindWord
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtilitiesTest {
    private val code = DiagnosticCode("SCH9999", Severity.ERROR, Category.SEMANTIC, "test")

    private fun at(line: Int) = Span("s.schemata", line, 1, line, 5)

    private fun ann(target: String, vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf(target to pairs.toMap()))

    private fun record(
        ns: String,
        name: String,
        path: List<String> = listOf(name),
        nested: List<RecordType> = emptyList(),
        annotations: Annotations = Annotations.NONE,
        line: Int = 1,
    ) =
        RecordType(
            QualifiedName(ns, path),
            name,
            emptyList(),
            Reserved.NONE,
            false,
            nested,
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(
        ns: String,
        name: String,
        vararg values: Pair<String, Annotations>,
        line: Int = 10,
    ) =
        EnumType(
            QualifiedName(ns, listOf(name)),
            name,
            values.mapIndexed { i, (v, a) ->
                EnumValue(i + 1, v, null, at(line + 1 + i), at(line + 1 + i), a)
            },
            Reserved.NONE,
            emptyList(),
            null,
            at(line),
            at(line),
            Annotations.NONE,
        )

    @Test
    fun `annotation readers`() {
        val a =
            Annotations(
                mapOf(
                    "x" to
                        mapOf(
                            "name" to AnnotationValue.Str("n"),
                            "open" to AnnotationValue.Flag,
                            "root" to AnnotationValue.Bool(false),
                        ),
                    "" to mapOf("deprecated" to AnnotationValue.Flag),
                )
            )
        assertEquals("n", a.string("x", "name"))
        assertNull(a.string("x", "open"))
        assertEquals(true, a.flag("x", "open"))
        assertEquals(false, a.flag("x", "name"))
        assertEquals(false, a.bool("x", "root"))
        assertNull(a.bool("x", "name"))
        assertEquals(true, a.deprecated)
        assertEquals(false, Annotations.NONE.deprecated)
    }

    @Test
    fun `kind word and declaration path`() {
        val line = record("s", "Line", path = listOf("Order", "Line"))
        val order = record("s", "Order", nested = listOf(line))
        val schema = Schema(listOf(Namespace("s", listOf(order), at(1))))
        assertEquals("model", order.kindWord)
        assertEquals(
            listOf(order, line),
            schema.declarationPath(QualifiedName("s", listOf("Order", "Line"))),
        )
    }

    @Test
    fun `colliding namespaces are grouped in source order`() {
        val a = Namespace("a", emptyList(), at(1), ann("t", "id" to AnnotationValue.Str("x")))
        val b = Namespace("b", emptyList(), at(2))
        val c = Namespace("c", emptyList(), at(3), ann("t", "id" to AnnotationValue.Str("x")))
        val groups =
            collidingNamespaces(listOf(a, b, c)) { it.annotations.string("t", "id") ?: it.name }
        assertEquals(listOf(listOf(a, c)), groups)
    }

    @Test
    fun `claims report the second holder with the first's location`() {
        val sink = mutableListOf<Diagnostic>()
        val claims = NameClaims(code, "rename one", sink)
        claims.claim("R", "x", "field 'R.x'", at(3), kind = "property")
        claims.claim("R", "x", "field 'R.y'", at(4), kind = "property")
        claims.claim("S", "x", "field 'S.x'", at(5), kind = "property")
        assertEquals(
            listOf(
                "SCH9999 field 'R.y' lowers to property 'x', already used by field 'R.x' (s.schemata:3)"
            ),
            sink.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(at(4), sink.single().span)
        assertEquals("rename one", sink.single().help)
    }

    @Test
    fun `override names validate once and fall back`() {
        val bad =
            record("s", "A", annotations = ann("t", "name" to AnnotationValue.Str("")), line = 1)
        val good =
            record("s", "B", annotations = ann("t", "name" to AnnotationValue.Str("Bee")), line = 2)
        val plain = record("s", "C", line = 3)
        val e =
            enum(
                "s",
                "E",
                "x" to ann("t", "name" to AnnotationValue.Str("X")),
                "y" to Annotations.NONE,
                "z" to ann("t", "name" to AnnotationValue.Str("")),
            )
        val sink = mutableListOf<Diagnostic>()
        val names =
            OverrideNames("t", code, sink, { if (it.isEmpty()) "is empty" else null }) {
                "give it a name"
            }
        assertNull(names.nameOverride(bad))
        assertNull(names.nameOverride(bad))
        assertEquals("Bee", names.nameOverride(good))
        assertNull(names.nameOverride(plain))
        assertEquals("X", names.enumValueName(e, e.values[0]))
        assertEquals("y", names.enumValueName(e, e.values[1]))
        assertEquals("X", names.enumValueOverride(e, e.values[0]))
        assertNull(names.enumValueOverride(e, e.values[1]))
        assertNull(names.enumValueOverride(e, e.values[2]))
        assertNull(
            names.overrideName(ann("t", "name" to AnnotationValue.Str("")), "field 'A.f'", at(7))
        )
        assertEquals(
            listOf(
                "SCH9999 model 'A': @t(name: \"\") is empty",
                "SCH9999 enum value 'E.z': @t(name: \"\") is empty",
                "SCH9999 field 'A.f': @t(name: \"\") is empty",
            ),
            sink.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(List(3) { "give it a name" }, sink.map { it.help })
    }

    @Test
    fun `a claim is unique within its own scope only`() {
        val sink = mutableListOf<Diagnostic>()
        val claims = NameClaims(code, "rename one", sink)
        claims.claim("a", "x", "field 'a.x'", at(1), kind = "property")
        claims.claim("b", "x", "field 'b.x'", at(2), kind = "property")
        assertEquals(emptyList(), sink)
        claims.claim("b", "x", "field 'b.y'", at(3), display = "X", kind = "property")
        assertEquals(
            listOf(
                "field 'b.y' lowers to property 'X', already used by field 'b.x' (s.schemata:2)"
            ),
            sink.map { it.message },
        )
    }

    @Test
    fun `an override under any key is validated the same way`() {
        val sink = mutableListOf<Diagnostic>()
        val names =
            OverrideNames("t", code, sink, { if (it.isEmpty()) "is empty" else null }) {
                "give it a name"
            }
        val bad = ann("t", "column" to AnnotationValue.Str(""))
        assertNull(names.overrideName(bad, "field 'A.f'", at(7), key = "column"))
        assertEquals(
            "c",
            names.overrideName(
                ann("t", "column" to AnnotationValue.Str("c")),
                "f",
                at(8),
                key = "column",
            ),
        )
        assertNull(names.overrideName(bad, "field 'A.f'", at(7)))
        assertEquals(listOf("field 'A.f': @t(column: \"\") is empty"), sink.map { it.message })
        assertEquals("give it a name", sink.single().help)
    }

    @Test
    fun `union member stems`() {
        val card =
            record("s", "Card", annotations = ann("t", "name" to AnnotationValue.Str("CreditCard")))
        val line = record("s", "Line", path = listOf("Order", "Line"))
        val order = record("s", "Order", nested = listOf(line))
        val schema = Schema(listOf(Namespace("s", listOf(card, order), at(1))))
        val override: (io.schemata.core.ir.TypeDecl) -> String? = {
            it.annotations.string("t", "name")
        }
        assertEquals("int64", unionMemberStem(Scalar(Builtin.INT64), schema, override))
        assertEquals(
            "CreditCard",
            unionMemberStem(Ref(QualifiedName("s", listOf("Card"))), schema, override),
        )
        assertEquals(
            "line",
            unionMemberStem(Ref(QualifiedName("s", listOf("Order", "Line"))), schema, override),
        )
        assertEquals(
            "card",
            unionMemberStem(Ref(QualifiedName("s", listOf("Card"))), schema) { null },
        )
    }
}
