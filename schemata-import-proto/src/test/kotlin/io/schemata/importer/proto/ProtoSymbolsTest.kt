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

    private fun file(path: String, source: String) = ProtoReader.read(path, source.trimIndent())

    private val validators =
        file(
            "envoy/extensions/config/validators/v3/x.proto",
            """
            syntax = "proto3";
            package envoy.extensions.config.validators.v3;
            message Validator {}
            """,
        )
    private val base =
        file(
            "envoy/config/core/v3/base.proto",
            """
            syntax = "proto3";
            package envoy.config.core.v3;
            message DataSource {}
            """,
        )
    private val lua =
        file(
            "envoy/extensions/filters/http/lua/v3/lua.proto",
            """
            syntax = "proto3";
            package envoy.extensions.filters.http.lua.v3;
            import "envoy/config/core/v3/base.proto";
            message Lua { config.core.v3.DataSource source = 1; }
            """,
        )
    private val inLua = listOf("envoy", "extensions", "filters", "http", "lua", "v3")

    @Test
    fun `a package prefix from a file outside the import closure does not capture a name`() {
        val all = ProtoSymbols(listOf(validators, base, lua), mapOf(lua.path to listOf(base)))
        assertNull(all.resolve("config.core.v3.DataSource", inLua))
        assertEquals(
            "envoy.config.core.v3.DataSource",
            all.resolve("config.core.v3.DataSource", inLua, lua)?.fullName,
        )
    }

    @Test
    fun `a name resolves through a direct import and through import public`() {
        val c = file("c.proto", "syntax = \"proto3\"; package c; message C {}")
        val b =
            file(
                "b.proto",
                "syntax = \"proto3\"; package b; import public \"c.proto\"; message B {}",
            )
        val a = file("a.proto", "syntax = \"proto3\"; package a; import \"b.proto\"; message A {}")
        val s =
            ProtoSymbols(
                listOf(a, b, c),
                mapOf(a.path to listOf(b), b.path to listOf(c)),
                mapOf(b.path to setOf(c.path)),
            )
        assertEquals(setOf("a.proto", "b.proto", "c.proto"), s.visibleFrom(a))
        assertEquals("b.B", s.resolve("b.B", listOf("a"), a)?.fullName)
        assertEquals("c.C", s.resolve("c.C", listOf("a"), a)?.fullName)
    }

    @Test
    fun `a name declared in a file the importing file does not import is not visible`() {
        val bare =
            file(
                "envoy/extensions/filters/http/lua/v3/bare.proto",
                """
                syntax = "proto3";
                package envoy.extensions.filters.http.lua.v3;
                message Bare {}
                """,
            )
        val s = ProtoSymbols(listOf(bare, base, validators), mapOf(bare.path to emptyList()))
        assertNull(s.resolve(".envoy.config.core.v3.DataSource", inLua, bare))
        assertNull(s.resolve("config.core.v3.DataSource", inLua, bare))
        assertEquals(base, s.declaringFile("envoy.config.core.v3.DataSource"))
    }

    @Test
    fun `a transitive non-public import is not visible`() {
        val c = file("c.proto", "syntax = \"proto3\"; package c; message C {}")
        val b = file("b.proto", "syntax = \"proto3\"; package b; import \"c.proto\"; message B {}")
        val a = file("a.proto", "syntax = \"proto3\"; package a; import \"b.proto\"; message A {}")
        val s = ProtoSymbols(listOf(a, b, c), mapOf(a.path to listOf(b), b.path to listOf(c)))
        assertEquals(setOf("a.proto", "b.proto"), s.visibleFrom(a))
        assertNull(s.resolve("c.C", listOf("a"), a))
    }

    @Test
    fun `a nested message still shadows a package prefix inside its own file`() {
        val x =
            file("x.proto", "syntax = \"proto3\"; package p; message config { message Inner {} }")
        val y = file("y.proto", "syntax = \"proto3\"; package config; message Inner {}")
        val s = ProtoSymbols(listOf(x, y), mapOf(x.path to listOf(y)))
        assertEquals(
            "p.config.Inner",
            s.resolve("config.Inner", listOf("p", "config"), x)?.fullName,
        )
    }

    @Test
    fun `referencedFiles lists the files that declare the types a file names`() {
        fun decl(pkg: String, type: String) =
            file("$pkg.proto", "syntax = \"proto3\"; package $pkg; message $type {}")
        val mapped = decl("mapped", "V")
        val listed = decl("listed", "E")
        val member = decl("member", "O")
        val payload = decl("payload", "P")
        val unused = decl("unused", "U")
        val svc =
            file(
                "svc.proto",
                """
                syntax = "proto3";
                package svc;
                message Top {
                  message In { map<string, mapped.V> m = 1; string s = 2; }
                  repeated listed.E d = 1;
                  oneof o { member.O self = 2; }
                }
                service S { rpc Do(payload.P) returns (google.protobuf.Empty); }
                """,
            )
        val all = listOf(mapped, listed, member, payload, unused, svc)
        val s = ProtoSymbols(all, mapOf(svc.path to listOf(mapped, listed, member, payload)))
        assertEquals(
            setOf(mapped.path, listed.path, member.path, payload.path),
            s.referencedFiles(svc),
        )
    }
}
