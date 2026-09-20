package io.schemata.target.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class NamingTest {
    @Test
    fun `converts UpperCamel to lower_snake`() {
        assertEquals("user", Naming.snakeCase("User"))
        assertEquals("order_line", Naming.snakeCase("OrderLine"))
        assertEquals("http2_server", Naming.snakeCase("Http2Server"))
    }
}
