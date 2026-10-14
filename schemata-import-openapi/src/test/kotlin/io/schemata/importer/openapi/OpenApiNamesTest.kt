package io.schemata.importer.openapi

import io.schemata.importer.openapi.OpenApiNames.Split
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiNamesTest {
    @Test
    fun `a dotted component name splits into its schema and declaration path`() {
        assertEquals(Split("shop.orders", listOf("Order")), OpenApiNames.split("shop.orders.Order"))
        assertEquals(
            Split("shop.orders", listOf("Order", "Line")),
            OpenApiNames.split("shop.orders.Order.Line"),
        )
        assertEquals(Split("com.example", listOf("Pet")), OpenApiNames.split("com.example.Pet"))
    }

    @Test
    fun `a name that is not written as a schema and path is one declaration of the document`() {
        assertEquals(Split(null, listOf("Pet")), OpenApiNames.split("Pet"))
        assertEquals(Split(null, listOf("pet_info")), OpenApiNames.split("pet_info"))
        assertEquals(Split(null, listOf("Order.Line")), OpenApiNames.split("Order.Line"))
        assertEquals(Split(null, listOf("shop.Order_line")), OpenApiNames.split("shop.Order_line"))
    }

    @Test
    fun `the document's own schema is given as null`() {
        assertEquals(Split(null, listOf("Pet")), OpenApiNames.split("shop.Pet", own = "shop"))
        assertEquals(
            Split("shop.orders", listOf("Order")),
            OpenApiNames.split("shop.orders.Order", own = "shop"),
        )
    }

    @Test
    fun `a name that starts with a known schema splits there whatever follows`() {
        val known = setOf("shop", "shop.orders")
        assertEquals(
            Split(null, listOf("pet_info")),
            OpenApiNames.split("shop.pet_info", known, "shop"),
        )
        assertEquals(
            Split("shop.orders", listOf("Order", "line_item")),
            OpenApiNames.split("shop.orders.Order.line_item", known, "shop"),
        )
        assertEquals(Split(null, listOf("shop..X")), OpenApiNames.split("shop..X", known))
    }

    @Test
    fun `the strict form needs lower snake segments then UpperCamel ones`() {
        assertEquals(Split("shop", listOf("Pet", "Toy")), OpenApiNames.strict("shop.Pet.Toy"))
        assertEquals(null, OpenApiNames.strict("shop.pet_info"))
        assertEquals(null, OpenApiNames.strict("Pet"))
    }

    @Test
    fun `upper camel names start with a capital and hold only letters and digits`() {
        assertTrue(OpenApiNames.isUpperCamel("HTTPError"))
        assertTrue(OpenApiNames.isUpperCamel("Pet2"))
        assertFalse(OpenApiNames.isUpperCamel("pet"))
        assertFalse(OpenApiNames.isUpperCamel("Pet_Info"))
    }

    @Test
    fun `a scope suffixes a taken name with the first free number`() {
        val scope = Scope()
        assertEquals("Pet", scope.claim("Pet"))
        assertEquals("Pet2", scope.claim("Pet"))
        assertEquals("Pet3", scope.claim("Pet"))
        assertTrue(scope.taken("Pet2"))
        assertFalse(scope.taken("Pet4"))
    }
}
