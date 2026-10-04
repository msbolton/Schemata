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
                  repeated Line lines = 4;  // schemata: list<Line>(min = 1)
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
        assertEquals("list<Line>(min = 1)", order.fields[3].note)
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
        assertEquals(listOf("Get"), f.services.single().rpcs)
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
    fun `a statement cut off by the end of the file is a syntax error`() {
        assertFailsWith<ProtoSyntaxError> { read("message M { extensions 1 to 5") }
    }
}
