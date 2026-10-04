package io.schemata.importer.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoSymbolsTest {
    private val orders =
        ProtoReader.read(
            "corpus/orders.proto",
            """
            syntax = "proto3";
            package corpus.orders;
            message Card { bool top = 1; }
            message Order {
              message Line { int64 qty = 1; }
              message Card { bool nested = 1; }
              enum Kind { KIND_UNSPECIFIED = 0; }
            }
            """
                .trimIndent(),
        )
    private val audit =
        ProtoReader.read(
            "corpus/audit.proto",
            """
            syntax = "proto3";
            package corpus.audit;
            message Audit {}
            """
                .trimIndent(),
        )
    private val timestamp =
        ProtoReader.read(
            "google/protobuf/timestamp.proto",
            """
            syntax = "proto3";
            package google.protobuf;
            message Timestamp { int64 seconds = 1; }
            """
                .trimIndent(),
        )
    private val symbols = ProtoSymbols(listOf(orders, audit, timestamp))
    private val inOrder = listOf("corpus", "orders", "Order")

    @Test
    fun `a nested message resolves by its simple name from inside its parent`() {
        assertEquals("corpus.orders.Order.Line", symbols.resolve("Line", inOrder)?.fullName)
        assertEquals("corpus.orders.Order.Kind", symbols.resolve("Kind", inOrder)?.fullName)
        assertEquals(orders, symbols.resolve("Order.Kind", listOf("corpus", "orders"))?.file)
    }

    @Test
    fun `a fully qualified name resolves from a sibling package`() {
        val found = symbols.resolve(".corpus.orders.Order.Line", listOf("corpus", "audit", "Audit"))
        assertEquals("corpus.orders.Order.Line", found?.fullName)
        assertEquals("Line", found?.message?.name)
    }

    @Test
    fun `a nested message shadows a top-level one`() {
        assertEquals("corpus.orders.Order.Card", symbols.resolve("Card", inOrder)?.fullName)
        assertEquals(
            "corpus.orders.Card",
            symbols.resolve("Card", listOf("corpus", "orders"))?.fullName,
        )
    }

    @Test
    fun `an unknown name is null`() {
        assertNull(symbols.resolve("Missing", inOrder))
        assertNull(symbols.resolve("Order.Missing", inOrder))
    }

    @Test
    fun `a name starting with a package root resolves through the package`() {
        assertEquals(
            "google.protobuf.Timestamp",
            symbols.resolve("google.protobuf.Timestamp", listOf("corpus", "orders"))?.fullName,
        )
        assertEquals(
            "corpus.audit.Audit",
            symbols.resolve("audit.Audit", listOf("corpus", "orders"))?.fullName,
        )
    }
}
