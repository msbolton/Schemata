package io.schemata.lsp.workspace

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaSetsTest {
    private fun abs(path: String) = Paths.get(path).toAbsolutePath().normalize().toString()

    @Test
    fun `without roots a file belongs to its own directory and not to a parent's`() {
        val sets = SchemaSets(emptyList())
        val key = sets.keyOf(abs("/w/shop/orders.schemata"))
        assertEquals(SetKey(abs("/w/shop"), recursive = false), key)
        assertTrue(sets.contains(key, abs("/w/shop/customers.schemata")))
        assertFalse(sets.contains(key, abs("/w/shop/sub/x.schemata")))
        assertFalse(sets.contains(key, abs("/w/other.schemata")))
    }

    @Test
    fun `a file under a root belongs to the root's whole subtree`() {
        val sets = SchemaSets(listOf(Paths.get("/w/model")))
        val key = sets.keyOf(abs("/w/model/a/b/x.schemata"))
        assertEquals(SetKey(abs("/w/model"), recursive = true), key)
        assertTrue(sets.contains(key, abs("/w/model/y.schemata")))
        assertFalse(sets.contains(key, abs("/w/modelling/y.schemata")))
    }

    @Test
    fun `the nearest of two nested roots wins`() {
        val sets = SchemaSets(listOf(Paths.get("/w"), Paths.get("/w/model")))
        assertEquals(
            SetKey(abs("/w/model"), recursive = true),
            sets.keyOf(abs("/w/model/x.schemata")),
        )
        assertEquals(SetKey(abs("/w"), recursive = true), sets.keyOf(abs("/w/x.schemata")))
    }

    @Test
    fun `a file under the outer root but inside the inner root is not in the outer set`() {
        val sets = SchemaSets(listOf(Paths.get("/w"), Paths.get("/w/model")))
        val outer = SetKey(abs("/w"), recursive = true)
        assertFalse(sets.contains(outer, abs("/w/model/x.schemata")))
    }
}
