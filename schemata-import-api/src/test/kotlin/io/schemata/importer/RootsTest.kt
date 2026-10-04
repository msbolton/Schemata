package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class RootsTest {
    @Test
    fun `a relative path names the namespace`() {
        assertEquals(
            "corpus.orders" to false,
            Roots.namespaceFor(
                ImportInput("x/corpus/orders.proto", "", "corpus/orders.proto"),
                "other.pkg",
                "orders",
            ),
        )
    }

    @Test
    fun `a bad segment is fixed and flagged`() {
        assertEquals(
            "my_protos.orders" to true,
            Roots.namespaceFor(ImportInput("p", "", "My-Protos/orders.proto"), null, "orders"),
        )
    }

    @Test
    fun `a lone file takes its package when it is a namespace name`() {
        assertEquals(
            "google.type" to false,
            Roots.namespaceFor(ImportInput("money.proto", ""), "google.type", "money"),
        )
    }

    @Test
    fun `a lone file with an unusable package takes its stem`() {
        assertEquals(
            "money" to true,
            Roots.namespaceFor(ImportInput("Money.proto", ""), "Google.Type", "Money"),
        )
    }
}
