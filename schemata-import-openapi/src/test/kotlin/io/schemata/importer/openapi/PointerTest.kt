package io.schemata.importer.openapi

import kotlin.test.Test
import kotlin.test.assertEquals

class PointerTest {
    @Test
    fun `the root is a bare hash`() {
        assertEquals("#", Pointer.ROOT.toString())
        assertEquals(emptyList(), Pointer.ROOT.segments)
    }

    @Test
    fun `a segment escapes tilde and slash`() {
        val p = Pointer.ROOT / "paths" / "/orders/{id}" / "get" / "a~b"
        assertEquals("#/paths/~1orders~1{id}/get/a~0b", p.text)
        assertEquals(listOf("paths", "/orders/{id}", "get", "a~b"), p.segments)
    }

    @Test
    fun `an index is a plain segment`() {
        val p = Pointer.ROOT / "paths" / "/orders" / "get" / "parameters" / 2
        assertEquals("#/paths/~1orders/get/parameters/2", p.toString())
    }

    @Test
    fun `segments read back an escaped pointer`() {
        assertEquals(listOf("a/b", "c~d"), Pointer("#/a~1b/c~0d").segments)
        assertEquals(listOf("~1"), Pointer("#/~01").segments)
    }
}
