package io.schemata.target.jsonschema

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSchemaLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun js(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("jsonschema" to pairs.toMap()))

    private fun core(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("" to pairs.toMap()))

    private fun namespace(
        name: String,
        annotations: Annotations = Annotations.NONE,
        line: Int = 1,
        declarations: List<TypeDecl> = emptyList(),
    ) = Namespace(name, declarations, at(line), annotations)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        default: Value? = null,
        line: Int = 10 + ordinal,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) = Field(ordinal, name, type, nullable, default, null, doc, at(line), at(line), annotations)

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        path: List<String> = listOf(name),
        nested: List<TypeDecl> = emptyList(),
        line: Int = 3,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            Reserved.NONE,
            false,
            nested,
            doc,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(
        ns: String,
        name: String,
        vararg values: String,
        line: Int = 30,
        annotations: Annotations = Annotations.NONE,
        valueDocs: Map<String, String> = emptyMap(),
        valueAnnotations: Map<String, Annotations> = emptyMap(),
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(
                    i + 1,
                    v,
                    valueDocs[v],
                    at(line + 1 + i),
                    at(line + 1 + i),
                    valueAnnotations[v] ?: Annotations.NONE,
                )
            },
            Reserved.NONE,
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun union(
        ns: String,
        name: String,
        vararg members: Type,
        line: Int = 40,
        memberDocs: List<String?> = emptyList(),
        annotations: Annotations = Annotations.NONE,
    ) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t ->
                UnionMember(i + 1, t, memberDocs.getOrNull(i), at(line + 1 + i))
            },
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun lower(vararg namespaces: Namespace) =
        JsonSchemaLowering.lower(Schema(namespaces.toList()))

    private fun document(vararg namespaces: Namespace) = lower(*namespaces).model.documents.single()

    private fun def(key: String, vararg namespaces: Namespace) =
        document(*namespaces).defs.single { it.key == key }.schema

    private fun messages(vararg namespaces: Namespace) =
        lower(*namespaces).diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `one document per namespace with path id and title`() {
        val doc = document(namespace("shop.orders"))
        assertEquals("shop/orders.schema.json", doc.path)
        assertEquals("urn:schemata:shop.orders", doc.id)
        assertEquals("shop.orders", doc.title)
        assertEquals(emptyList(), doc.defs)
    }

    @Test
    fun `documents come in namespace order`() {
        val model = lower(namespace("b"), namespace("a")).model
        assertEquals(listOf("b.schema.json", "a.schema.json"), model.documents.map { it.path })
    }

    @Test
    fun `an id override that is not an absolute uri is reported and the urn is kept`() {
        val ns = namespace("s", js("id" to AnnotationValue.Str("orders")))
        assertEquals(
            listOf("SCH2303 namespace 's': @jsonschema(id = \"orders\") is not an absolute URI"),
            messages(ns),
        )
        assertEquals("urn:schemata:s", document(ns).id)
    }

    @Test
    fun `two namespaces with one id are reported`() {
        val a = namespace("a", js("id" to AnnotationValue.Str("urn:x")))
        val b = namespace("b", js("id" to AnnotationValue.Str("urn:x")), line = 9)
        assertEquals(
            listOf("SCH2304 namespaces a and b both lower to \$id 'urn:x'"),
            messages(a, b),
        )
    }
}
