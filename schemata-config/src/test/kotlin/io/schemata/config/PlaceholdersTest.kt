package io.schemata.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceholdersTest {
    @Test
    fun `upper camel keeps the dots`() {
        assertEquals("Shop.Orders", Placeholders.upperCamelDotted("shop.orders"))
        assertEquals("Uc2SosAll", Placeholders.upperCamelDotted("uc2_sos_all"))
    }

    @Test
    fun `expand fills every placeholder`() {
        assertEquals(
            "com.example.shop.orders",
            Placeholders.expand("com.example.{schema}", "shop.orders"),
        )
        assertEquals("Example.Shop.Orders", Placeholders.expand("Example.{Schema}", "shop.orders"))
        assertEquals("shop_orders_v1", Placeholders.expand("{schema_}_v1", "shop.orders"))
        assertEquals(
            "shop.orders.v1",
            Placeholders.expand("{package}", "shop.orders", protoPackage = "shop.orders.v1"),
        )
        assertEquals(
            "Shop.Orders.V1",
            Placeholders.expand("{Package}", "shop.orders", protoPackage = "shop.orders.v1"),
        )
    }

    @Test
    fun `doubled braces are literal`() {
        assertEquals("{x}", Placeholders.expand("{{x}}", "shop"))
        assertNull(Placeholders.check("{{x}}", allowPackage = false))
    }

    @Test
    fun `check names the first unknown or unaccepted placeholder`() {
        assertEquals("foo", Placeholders.check("a.{schema}.{foo}.{bar}", allowPackage = true))
        assertEquals("package", Placeholders.check("{package}", allowPackage = false))
        assertEquals("Package", Placeholders.check("{schema}.{Package}", allowPackage = false))
        assertNull(Placeholders.check("{schema}.{Schema}.{schema_}", allowPackage = false))
        assertNull(Placeholders.check("{package}.{Package}", allowPackage = true))
        assertNull(Placeholders.check("com.example", allowPackage = false))
    }
}
