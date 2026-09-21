package io.schemata.core.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnnotationRegistryTest {
    private val keyOnField =
        AnnotationSpec("sql", "key", setOf(Element.FIELD), ValueKind.FLAG, Role.STRATEGY)
    private val keyOnRecord =
        AnnotationSpec("sql", "key", setOf(Element.RECORD), ValueKind.NAME_TUPLE, Role.STRATEGY)
    private val schema =
        AnnotationSpec("sql", "schema", setOf(Element.NAMESPACE), ValueKind.STRING, Role.NAME)
    private val protoName =
        AnnotationSpec(
            "proto",
            "name",
            setOf(Element.RECORD, Element.FIELD),
            ValueKind.STRING,
            Role.NAME,
        )

    private val registry =
        AnnotationRegistry(
            CoreAnnotations.specs + listOf(keyOnField, keyOnRecord, schema, protoName)
        )

    @Test
    fun `finds every spec for a target and key`() {
        assertEquals(listOf(keyOnField, keyOnRecord), registry.find("sql", "key"))
        assertEquals(listOf(schema), registry.find("sql", "schema"))
        assertEquals(emptyList(), registry.find("sql", "table"))
        assertEquals(emptyList(), registry.find("mongo", "index"))
    }

    @Test
    fun `lists targets, names, and keys in sorted order`() {
        assertTrue(registry.hasTarget("sql"))
        assertTrue(registry.hasTarget("proto"))
        assertTrue(!registry.hasTarget("deprecated"))
        assertEquals(listOf("deprecated", "proto", "sql"), registry.names())
        assertEquals(listOf("key", "schema"), registry.keys("sql"))
        assertEquals(listOf("deprecated"), registry.keys(""))
    }

    @Test
    fun `core owns the target-agnostic keys`() {
        val deprecated = AnnotationRegistry.CORE.find("", "deprecated").single()
        assertEquals(ValueKind.STRING, deprecated.valueKind)
        assertTrue(deprecated.optional)
        assertEquals(
            setOf(
                Element.RECORD,
                Element.ENUM,
                Element.UNION,
                Element.ALIAS,
                Element.FIELD,
                Element.ENUM_VALUE,
            ),
            deprecated.elements,
        )
        assertEquals(listOf("deprecated"), AnnotationRegistry.CORE.names())
    }

    @Test
    fun `two specs for one key may not share an element`() {
        val clash = AnnotationSpec("sql", "key", setOf(Element.FIELD), ValueKind.STRING, Role.NAME)
        assertFailsWith<IllegalArgumentException> { AnnotationRegistry(listOf(keyOnField, clash)) }
    }
}
