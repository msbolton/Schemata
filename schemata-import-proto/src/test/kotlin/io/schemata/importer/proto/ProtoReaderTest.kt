package io.schemata.importer.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoReaderTest {
    private fun read(src: String) = ProtoReader.read("t.proto", src.trimIndent())

    @Test
    fun `a proto3 file with messages enums oneofs maps reserved options and comments`() {
        val f =
            read(
                """
                syntax = "proto3";

                package corp.orders;

                import "google/protobuf/timestamp.proto";
                import public "shop/customers.proto";
                option java_package = "com.corp";

                // An order.
                // Two lines.
                message Order {
                  option deprecated = true;

                  // Stock keeping unit.
                  string sku_code = 1;
                  optional int32 legacy_id = 2 [deprecated = true, json_name = "legacyId"];
                  string id = 3;  // schemata: uuid
                  repeated Line lines = 4;  // schemata: Line[] { minItems 1 }
                  map<string, int32> counts = 5;
                  .google.protobuf.Timestamp placed_at = 6;  // schemata: instant?
                  oneof kind {
                    Card card = 7;
                    string cash = 8;  // plain trailing
                  }
                  message Line { int64 qty = 1; }
                  enum Status {
                    STATUS_UNSPECIFIED = 0;
                    STATUS_PAID = 1 [deprecated = true];
                    reserved 5 to 6;
                    reserved "STATUS_OLD";
                  }
                  reserved 11, 20 to max;
                  reserved "legacy_ref";
                  extensions 100 to 199;
                }
                service Orders { rpc Get (Order) returns (Order); }
                """
            )
        assertEquals("proto3", f.syntax)
        assertEquals("corp.orders", f.pkg)
        assertEquals(
            listOf("google/protobuf/timestamp.proto", "shop/customers.proto"),
            f.imports.map { it.path },
        )
        assertTrue(f.imports[1].public)
        assertEquals(listOf(ProtoOption("java_package", "com.corp")), f.options)
        val order = f.messages.single()
        assertEquals("An order.\nTwo lines.", order.doc)
        assertEquals(listOf(ProtoOption("deprecated", "true")), order.options)
        val sku = order.fields[0]
        assertEquals("Stock keeping unit.", sku.doc)
        assertEquals(Label.NONE, sku.label)
        assertEquals("string", sku.type)
        assertEquals(1, sku.number)
        val legacy = order.fields[1]
        assertEquals(Label.OPTIONAL, legacy.label)
        assertEquals(
            listOf(ProtoOption("deprecated", "true"), ProtoOption("json_name", "legacyId")),
            legacy.options,
        )
        assertEquals("uuid", order.fields[2].note)
        assertEquals("Line[] { minItems 1 }", order.fields[3].note)
        assertEquals(Label.REPEATED, order.fields[3].label)
        val counts = order.fields[4]
        assertEquals("map", counts.type)
        assertEquals("string", counts.mapKey)
        assertEquals("int32", counts.mapValue)
        assertEquals(".google.protobuf.Timestamp", order.fields[5].type)
        assertEquals("instant?", order.fields[5].note)
        assertEquals("kind", order.fields[6].oneof)
        assertEquals("kind", order.fields[7].oneof)
        assertEquals(" plain trailing", order.fields[7].trailing)
        assertNull(order.fields[7].note)
        assertEquals(listOf("kind"), order.oneofs)
        assertEquals("Line", order.messages.single().name)
        val status = order.enums.single()
        assertEquals(
            listOf("STATUS_UNSPECIFIED" to 0, "STATUS_PAID" to 1),
            status.values.map { it.name to it.number },
        )
        assertEquals(listOf(5 to 6), status.reserved[0].ranges)
        assertEquals(listOf("STATUS_OLD"), status.reserved[1].names)
        assertEquals(listOf(11 to 11, 20 to 536870911), order.reserved[0].ranges)
        assertEquals(listOf("legacy_ref"), order.reserved[1].names)
        assertEquals(listOf("extensions"), order.dropped.map { it.first })
        assertEquals(listOf("Get"), f.services.single().rpcs.map { it.name })
    }

    @Test
    fun `proto2 required optional groups extend and defaults`() {
        val f =
            read(
                """
                syntax = "proto2";
                message M {
                  required int32 a = 1 [default = 5];
                  optional string b = 2 [default = "x"];
                  optional group G = 3 { optional int32 c = 1; }
                  extensions 10 to 20;
                }
                extend M { optional int32 ext = 10; }
                """
            )
        assertEquals("proto2", f.syntax)
        assertEquals(Label.REQUIRED, f.messages[0].fields[0].label)
        assertEquals(listOf(ProtoOption("default", "5")), f.messages[0].fields[0].options)
        assertEquals(listOf("group", "extensions"), f.messages[0].dropped.map { it.first })
        assertEquals(listOf("extend"), f.dropped.map { it.first })
    }

    @Test
    fun `a file with no syntax line is proto2 and editions are read`() {
        assertEquals("proto2", read("message M {}").syntax)
        val e =
            read(
                "edition = \"2023\";\nmessage M { int32 a = 1 [features.field_presence = EXPLICIT]; }"
            )
        assertEquals("editions", e.syntax)
        assertEquals("2023", e.edition)
    }

    @Test
    fun `a leading comment separated by a blank line is not a doc and aggregate options are skipped`() {
        val f =
            read(
                "syntax = \"proto3\";\n// detached\n\nmessage M { option (my.opt) = { a: 1 b: [1, 2] }; int32 a = 1; }"
            )
        assertNull(f.messages[0].doc)
        assertEquals("(my.opt)", f.messages[0].options[0].name)
    }

    @Test
    fun `a syntax error reports line and column`() {
        val e =
            assertFailsWith<ProtoSyntaxError> {
                read("syntax = \"proto3\";\nmessage M { int32 = 1; }")
            }
        assertEquals(Pos(2, 19), e.pos)
        assertEquals("expected a field name", e.message)
    }

    @Test
    fun `a trailing comment on a statement is not a doc of the next declaration`() {
        val f = read("message M {\n  option deprecated = true;  // why\n  int32 a = 1;\n}")
        assertNull(f.messages[0].fields[0].doc)
    }

    @Test
    fun `an rpc keeps its types, streams, and note`() {
        val file =
            read(
                """
                syntax = "proto3";
                // Orders.
                service Orders {
                  // Fetch.
                  rpc Get(OrderId) returns (Order);  // schemata: get "/orders/{id}"
                  rpc List(ListOrders) returns (stream Order);
                  rpc Upload(stream Chunk) returns (google.protobuf.Empty);
                  // schemata: reserved #6, "archive"
                }
                """
            )
        val s = file.services.single()
        assertEquals("Orders.", s.doc)
        assertEquals(listOf("reserved #6, \"archive\""), s.reservedNotes.map { it.first })
        val (get, list, upload) = s.rpcs
        assertEquals(ProtoRpcType("OrderId", false), get.request)
        assertEquals(ProtoRpcType("Order", false), get.response)
        assertEquals("get \"/orders/{id}\"", get.note)
        assertEquals("Fetch.", get.doc)
        assertEquals(ProtoRpcType("Order", true), list.response)
        assertEquals(ProtoRpcType("Chunk", true), upload.request)
        assertEquals("google.protobuf.Empty", upload.response.name)
    }

    @Test
    fun `a note on an rpc with a body`() {
        val file =
            read(
                """
                syntax = "proto3";
                service S {
                  rpc Old(A) returns (B) {  // schemata: #4; get "/old"
                    option deprecated = true;
                  }
                  option deprecated = true;
                }
                """
            )
        val s = file.services.single()
        val old = s.rpcs.single()
        assertEquals("#4; get \"/old\"", old.note)
        assertTrue(old.options.any { it.name == "deprecated" && it.value == "true" })
        assertTrue(s.options.any { it.name == "deprecated" && it.value == "true" })
    }

    @Test
    fun `a standalone schemata reserved comment between rpcs is a reserved note and a plain one is a doc`() {
        val s =
            read(
                    """
                service S {
                  rpc A(M) returns (M);
                  // schemata: reserved "gone"

                  // Second.
                  rpc B(M) returns (M);  // schemata: #2
                }
                """
                )
                .services
                .single()
        assertEquals(listOf("reserved \"gone\"" to Pos(3, 3)), s.reservedNotes)
        assertNull(s.rpcs[0].note)
        assertEquals("Second.", s.rpcs[1].doc)
        assertEquals("#2", s.rpcs[1].note)
    }

    @Test
    fun `a standalone schemata comment that is not a reservation stays a doc`() {
        val s =
            read(
                    """
                service S {
                  // schemata: get "/a"
                  rpc A(M) returns (M);
                  // schemata: reservedly
                  rpc B(M) returns (M);
                }
                """
                )
                .services
                .single()
        assertEquals(emptyList(), s.reservedNotes)
        assertEquals("schemata: get \"/a\"", s.rpcs[0].doc)
        assertEquals("schemata: reservedly", s.rpcs[1].doc)
    }

    @Test
    fun `a type named stream is the type and not a stream marker`() {
        val rpc =
            read(
                    "service S { rpc A(stream) returns (stream.Item); rpc B(stream stream) returns (M); }"
                )
                .services
                .single()
                .rpcs
        assertEquals(ProtoRpcType("stream", false), rpc[0].request)
        assertEquals(ProtoRpcType("stream.Item", false), rpc[0].response)
        assertEquals(ProtoRpcType("stream", true), rpc[1].request)
    }

    @Test
    fun `an rpc body cut off by the end of the file is a syntax error`() {
        assertFailsWith<ProtoSyntaxError> {
            read("service S { rpc A(M) returns (M) { option x = 1;")
        }
    }

    @Test
    fun `a statement cut off by the end of the file is a syntax error`() {
        assertFailsWith<ProtoSyntaxError> { read("message M { extensions 1 to 5") }
    }

    @Test
    fun `a reserved note inside a doc block leaves the doc attached to the rpc`() {
        val s =
            read(
                    """
                service S {
                  // Fetch one.
                  // schemata: reserved #6
                  rpc A(M) returns (M);
                }
                """
                )
                .services
                .single()
        assertEquals(listOf("reserved #6" to Pos(3, 3)), s.reservedNotes)
        assertEquals("Fetch one.", s.rpcs.single().doc)
    }

    @Test
    fun `a bare schemata comment ending a doc block stays in the rpc doc`() {
        val s =
            read(
                    """
                service S {
                  // Fetch one.
                  // schemata:
                  rpc A(M) returns (M);
                }
                """
                )
                .services
                .single()
        assertEquals("Fetch one.\nschemata:", s.rpcs.single().doc)
        assertEquals(emptyList(), s.reservedNotes)
    }

    @Test
    fun `a nested block in an rpc body is read past and the options around it are kept`() {
        val s =
            read(
                    """
                service S {
                  rpc A(M) returns (M) {
                    option deprecated = true;
                    option (google.api.http) = { get: "/a" additional_bindings { get: "/b" } };
                    { { } }
                  }
                  rpc B(M) returns (M);
                }
                """
                )
                .services
                .single()
        assertEquals(listOf("A", "B"), s.rpcs.map { it.name })
        assertEquals(listOf("deprecated", "(google.api.http)"), s.rpcs[0].options.map { it.name })
    }

    @Test
    fun `a comment trailing the closing brace is not a doc of the next declaration`() {
        val f =
            read(
                """
                service S { rpc A(M) returns (M); }  // end of S
                message Next {}
                """
            )
        assertNull(f.messages.single().doc)
        val g =
            read(
                """
                service S {
                  rpc A(M) returns (M) {}  // end of A
                  rpc B(M) returns (M);
                }
                """
            )
        assertNull(g.services.single().rpcs[1].doc)
    }

    @Test
    fun `a service option before the first rpc is kept`() {
        val s =
            read(
                    """
                service S {
                  option deprecated = true;
                  // First.
                  rpc A(M) returns (M);
                }
                """
                )
                .services
                .single()
        assertEquals(listOf(ProtoOption("deprecated", "true")), s.options)
        assertEquals("First.", s.rpcs.single().doc)
    }

    @Test
    fun `an own-line schemata comment before the closing brace of a service is kept as a stray note`() {
        val s =
            read(
                    """
                service S {
                  rpc A(M) returns (M);
                  // schemata: get "/a"
                }
                """
                )
                .services
                .single()
        assertEquals(listOf("get \"/a\"" to Pos(3, 3)), s.strayNotes)
        assertEquals(emptyList(), s.reservedNotes)
    }

    @Test
    fun `an enum value keeps its schemata comment as a note`() {
        val e =
            read(
                    """
                enum E {
                  E_UNSPECIFIED = 0;
                  E_A = 1;  // schemata: a note
                  E_B = 2;  // plain
                }
                """
                )
                .enums
                .single()
        assertNull(e.values[0].note)
        assertEquals("a note", e.values[1].note)
        assertNull(e.values[1].trailing)
        assertNull(e.values[2].note)
        assertEquals(" plain", e.values[2].trailing)
    }

    @Test
    fun `an extensions statement is skipped to its own end even with a semicolon inside its options`() {
        val m =
            read(
                    """
                message M {
                  extensions 1 to 3, 5;
                  extensions 10 to 20 [declaration = { number: 12; full_name: ".a.b"; type: "int32" }];
                  int32 a = 100;
                }
                """
                )
                .messages
                .single()
        assertEquals(listOf("extensions", "extensions"), m.dropped.map { it.first })
        assertEquals(listOf("a"), m.fields.map { it.name })
    }

    @Test
    fun `a block comment doc keeps the indentation inside it`() {
        val f =
            read(
                """
                /**
                 * Example:
                 *     indented
                 *   less
                 */
                message M {}
                /* plain
                   second
                     deeper */
                message N {}
                """
            )
        assertEquals("Example:\n    indented\n  less", f.messages[0].doc)
        assertEquals("plain\nsecond\n  deeper", f.messages[1].doc)
    }

    @Test
    fun `a hex or exponent number with no digits is a syntax error at the number`() {
        val hex = assertFailsWith<ProtoSyntaxError> { read("message M { int32 a = 0x; }") }
        assertEquals(Pos(1, 23), hex.pos)
        val exp = assertFailsWith<ProtoSyntaxError> { read("option x = 1e;") }
        assertEquals(Pos(1, 12), exp.pos)
    }

    @Test
    fun `a byte order mark is skipped and a non-ASCII identifier is an error at its position`() {
        assertEquals("M", read("\uFEFFmessage M {}").messages.single().name)
        val e = assertFailsWith<ProtoSyntaxError> { read("message Caf\u00e9 {}") }
        assertEquals(Pos(1, 12), e.pos)
    }
}
