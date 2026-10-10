package io.schemata.importer.proto

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoImportTest {
    private fun importText(vararg files: Pair<String, String>): ImportResult =
        ProtoImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) }
        )

    private fun text(result: ImportResult, path: String): String =
        result.files.single { it.path == path }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `messages scalars labels maps and well known types`() {
        val r =
            importText(
                "corp/orders.proto" to
                    """
                    syntax = "proto3";
                    package corp.orders;
                    import "google/protobuf/timestamp.proto";
                    import "google/protobuf/wrappers.proto";
                    message Order {
                      string id = 1;
                      optional int32 n = 2;
                      repeated string tags = 3;
                      map<string, int64> counts = 4;
                      .google.protobuf.Timestamp at = 5;
                      google.protobuf.StringValue nick = 6;
                      sint32 s = 7;
                      uint32 u = 8;
                      uint64 big = 9;
                      fixed64 f = 10;
                      Line line = 11;
                      Line maybe = 12;  // schemata: Line?
                      message Line { int64 qty = 1; }
                    }
                    """
            )
        assertEquals(
            """
            schema corp.orders

            model Order {
              #1  id     string
              #2  n      int32?
              #3  tags   string[]
              #4  counts map<string, int64>
              #5  at     instant
              #6  nick   string?
              #7  s      int32
              #8  u      int64              { min 0, max 4294967295 }
              #9  big    int64              { min 0 }
              #10 f      int64              { min 0 }
              #11 line   Line
              #12 maybe  Line?

              model Line { #1 qty int64 }
            }
            """
                .trimIndent() + "\n",
            text(r, "corp/orders.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 field 'Order.nick': google.protobuf.StringValue imported as string?; the regenerated field is optional, not a wrapper",
                "SCH2404 field 'Order.s': sint32 imported as int32",
                "SCH2404 field 'Order.u': uint32 imported as int64(min = 0, max = 4294967295)",
                "SCH2404 field 'Order.big': uint64 imported as int64(min = 0); the top bit is lost",
                "SCH2404 field 'Order.f': fixed64 imported as int64(min = 0); the top bit is lost",
            ),
            messages(r),
        )
    }

    @Test
    fun `notes override the proto type`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Status { STATUS_UNSPECIFIED = 0; STATUS_PENDING = 1; }
                    message M {
                      string f = 1;  // schemata: decimal(10, 2)
                      optional string d = 2;  // schemata: date?
                      int32 o = 3;  // schemata: int32 { min 0 }; default = 3
                      repeated string l = 4;  // schemata: string[] { maxItems 2, max 3 }
                      map<string, string> m = 5;  // schemata: map<string, decimal(19, 4)>
                      Status s = 6;  // schemata: default = STATUS_PENDING
                    }
                    """
            )
        assertEquals(
            """
            schema t

            enum Status { #1 pending }

            model M {
              #1 f decimal(10, 2)
              #2 d date?
              #3 o int32                       { min 0 } = 3
              #4 l string[]                    { maxItems 2, max 3 }
              #5 m map<string, decimal(19, 4)>
              #6 s Status                      = pending
            }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a note that does not parse or names an impossible scalar is approximated`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M {
                      int32 a = 1;  // schemata: uuid
                      string b = 2;  // schemata: (
                    }
                    """
            )
        assertEquals(
            """
            schema t

            model M { #1 a int32  #2 b string }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 field 'M.a': note 'uuid' does not fit int32; ignored",
                "SCH2403 field 'M.b': note '(' cannot be read; ignored",
            ),
            messages(r),
        )
    }

    @Test
    fun `a pattern and a default that both hold the separator`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M {
                      string f = 1;  // schemata: string { match "^a; b$" }; default = "x; y"
                    }
                    """
            )
        assertEquals(
            """
            schema t

            model M { #1 f string { match "^a; b$" } = "x; y" }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `enums`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Status {
                      STATUS_UNSPECIFIED = 0;
                      STATUS_PENDING = 1;
                      STATUS_PAID = 2 [deprecated = true];
                      reserved 5 to 6;
                      reserved "STATUS_OLD";
                    }
                    enum Color { RED = 0; GREEN = 1; }
                    enum K { UNSPECIFIED = 0; K_A = 1; }
                    enum E { option allow_alias = true; E_UNSPECIFIED = 0; E_A = 1; E_B = 1; }
                    enum V { V_UNSPECIFIED = 0; V_FOO_BAR = 1; CUSTOM = 2; }
                    """
            )
        assertEquals(
            """
            schema t

            enum Status {
              #1 pending
              @deprecated #2 paid
              reserved #5..#6, "old"
            }

            enum Color { @proto(name: "RED") red @proto(name: "GREEN") green }

            enum K { #1 a }

            enum E { #1 a }

            enum V { #1 foo_bar @proto(name: "CUSTOM") #2 custom }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 enum 'Color': values renumbered; 0 is not a Schemata ordinal",
                "SCH2403 enum 'K': zero value 'UNSPECIFIED' dropped; the regenerated enum names it 'K_UNSPECIFIED'",
                "SCH2405 enum value 'E.E_B': alias of 'E_A' dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `a renumbered enum drops its reserved numbers`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Lb {
                      ROUND_ROBIN = 0;
                      LEAST_REQUEST = 1;
                      reserved 4;
                      reserved "LB_OLD";
                      MAGLEV = 5;
                      OTHER = 7;
                    }
                    """
            )
        val out = text(r, "t.schemata")
        assertTrue(!out.contains("reserved #"), out)
        assertTrue(out.contains("reserved \"old\""), out)
        assertEquals(
            listOf(
                "SCH2403 enum 'Lb': values renumbered; 0 is not a Schemata ordinal",
                "SCH2403 enum 'Lb': reserved numbers dropped; the values were renumbered",
            ),
            messages(r),
        )
    }

    @Test
    fun `an enum that keeps its numbers keeps its reserved numbers`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Status {
                      STATUS_UNSPECIFIED = 0;
                      STATUS_PENDING = 1;
                      reserved 5 to 6;
                      reserved "STATUS_OLD";
                    }
                    """
            )
        val out = text(r, "t.schemata")
        assertTrue(out.contains("reserved #5..#6, \"old\""), out)
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a mixed-case zero value is dropped like an upper-case one`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Unit { Unspecified = 0; Bytes = 1; Seconds = 2; }
                    """
            )
        val out = text(r, "t.schemata")
        assertTrue(!out.contains("unspecified"), out)
        assertEquals(
            listOf(
                "SCH2403 enum 'Unit': zero value 'Unspecified' dropped; the regenerated enum names it 'UNIT_UNSPECIFIED'"
            ),
            messages(r),
        )
    }

    @Test
    fun `oneofs`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message Payment {
                      oneof kind {
                        Card card = 1;
                        Cash cash = 2;
                        string uuid = 3;  // schemata: uuid
                      }
                    }
                    message Card {}
                    message Cash {}
                    message P { oneof kind { Card c = 1; Cash cash = 2; } }
                    message Q { int32 x = 1; oneof which { string a = 2; string b = 3; } }
                    message S { oneof k { string a = 1; string b = 2; } }
                    """
            )
        assertEquals(
            """
            schema t

            union Payment = #1 Card | #2 Cash | #3 uuid

            model Card {}

            model Cash {}

            union P = #1 Card | #2 Cash

            model Q { #1 x int32  #2 a string?  #3 b string? }

            model S { #1 a string?  #2 b string? }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 union 'P': member element 'c' has no Schemata equivalent; the regenerated oneof names it 'card'",
                "SCH2403 model 'Q': oneof 'which' imported as nullable fields; at most one of them is set, which Schemata cannot say",
                "SCH2403 model 'S': oneof 'k' imported as nullable fields; at most one of them is set, which Schemata cannot say",
            ),
            messages(r),
        )
    }

    @Test
    fun `a union whose oneof is not named kind says the regenerated name`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message Card {}
                    message Cash {}
                    message P { oneof method { Card card = 1; Cash cash = 2; } }
                    """
            )
        assertEquals(
            listOf("SCH2403 union 'P': oneof 'method' is named 'kind' in the regenerated message"),
            messages(r),
        )
    }

    @Test
    fun `reserved deprecated options and docs`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    option java_package = "com.example";
                    option go_package = "example.com/t";
                    // A message.
                    // Second line.
                    message M {
                      option deprecated = true;
                      option (my.custom) = 3;
                      // The field.
                      string f = 1 [json_name = "x"];  // trailing words
                      int32 g = 2 [deprecated = true, (my.field) = "y"];
                      repeated int32 h = 3 [packed = true];
                      /* Block doc. */
                      string i = 4;
                      reserved 7, 9 to 11;
                      reserved "old", "older";
                    }
                    """
            )
        assertEquals(
            """
            schema t

            /// A message.
            /// Second line.
            model M {
              /// The field.
              ///
              /// trailing words
              #1 f string
              #2 g int32   @deprecated
              #3 h int32[]
              /// Block doc.
              #4 i string
              reserved #7, #9..#11, "old", "older"

              @@deprecated
            }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(listOf("SCH2405 field 'M.f': json_name dropped"), messages(r))
    }

    @Test
    fun `naming`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message foo_bar { string FooBaz = 1; string model = 2; string record = 3; }
                    """
            )
        assertEquals(
            """
            schema t

            model FooBar {
              #1 foo_baz     string @proto(name: "FooBaz")
              #2 model_value string @proto(name: "model")
              #3 record      string

              @@proto(name: "foo_bar")
            }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `proto2 and editions`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto2";
                    message M {
                      required int32 a = 1;
                      optional string b = 2 [default = "x"];
                      optional group G = 3 { optional int32 x = 1; }
                      extensions 10 to 20;
                    }
                    """
            )
        assertEquals(
            """
            schema t

            model M { #1 a int32  #2 b string = "x" }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf("SCH2405 message 'M': group dropped", "SCH2405 message 'M': extensions dropped"),
            messages(r),
        )
        val e =
            importText(
                "t.proto" to
                    """
                    edition = "2023";
                    message N { string a = 1; }
                    """
            )
        assertEquals(
            listOf(
                "SCH2403 t.proto: edition 2023 read as proto3; explicit presence assumed for optional fields only"
            ),
            messages(e),
        )
    }

    @Test
    fun `proto2 defaults of every kind`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto2";
                    enum Status { STATUS_UNSPECIFIED = 0; STATUS_PENDING = 1; }
                    message M {
                      optional int32 a = 1 [default = -3];
                      optional double b = 2 [default = 1.5];
                      optional bool c = 3 [default = true];
                      optional Status d = 4 [default = STATUS_PENDING];
                      optional string e = 5 [default = "a\"b"];
                      optional double f = 6 [default = inf];
                      optional int32 g = 7;
                    }
                    """
            )
        assertEquals(
            """
            schema t

            enum Status { #1 pending }

            model M {
              #1 a int32    = -3
              #2 b float64  = 1.5
              #3 c bool     = true
              #4 d Status   = pending
              #5 e string   = "a\"b"
              #6 f float64?
              #7 g int32?
            }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf("SCH2403 field 'M.f': default inf has no Schemata literal; dropped"),
            messages(r),
        )
    }

    @Test
    fun `any google protobuf import needs no file`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "google/protobuf/descriptor.proto";
                    extend google.protobuf.FieldOptions { string label = 50000; }
                    message M { string s = 1; }
                    """
            )
        assertEquals(listOf("SCH2405 t.proto: extend dropped"), messages(r))
        assertEquals("schema t\n\nmodel M { #1 s string }\n", text(r, "t.schemata"))
    }

    @Test
    fun `a google protobuf type outside the mapped ones is a string`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "google/protobuf/api.proto";
                    message M { google.protobuf.Api f = 1; repeated .google.protobuf.Method g = 2; }
                    """
            )
        assertEquals(
            listOf(
                "SCH2404 field 'M.f': google.protobuf.Api imported as string",
                "SCH2404 field 'M.g': google.protobuf.Method imported as string",
            ),
            messages(r),
        )
        assertEquals("schema t\n\nmodel M { #1 f string  #2 g string[] }\n", text(r, "t.schemata"))
    }

    @Test
    fun `extensions and extend are dropped`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto2";
                    message M { optional int32 a = 1; extensions 100 to 200; }
                    extend M { optional int32 x = 100; }
                    """
            )
        assertEquals(
            listOf("SCH2405 message 'M': extensions dropped", "SCH2405 t.proto: extend dropped"),
            messages(r),
        )
    }

    @Test
    fun `a service imports with its operations`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    import "google/protobuf/empty.proto";
                    message Id { string id = 1; }
                    message Order { string id = 1; }
                    // Orders.
                    service Orders {
                      // Fetch.
                      rpc Get(Id) returns (Order);  // schemata: get "/orders/{id}"
                      rpc List(Id) returns (stream Order);
                      rpc Cancel(Id) returns (google.protobuf.Empty) {  // schemata: #5; delete "/orders/{id}"
                        option deprecated = true;
                      }
                      rpc GetURL(Id) returns (Order);
                      // schemata: reserved #6, "archive"
                    }
                    """
            )
        assertEquals(
            """
            /// Orders.
            service Orders {
              /// Fetch.
              #1 get(Id): Order  get "/orders/{id}"
              #2 list(Id): stream Order
              @deprecated #5 cancel(Id)  delete "/orders/{id}"
              @proto(name: "GetURL") #4 get_url(Id): Order
              reserved #6, "archive"
            }
            """
                .trimIndent() + "\n",
            "/// Orders.\n" + text(r, "t.schemata").substringAfter("\n/// Orders.\n"),
        )
        assertEquals(
            listOf("SCH2402 service 'Orders': rpc 'GetURL': renamed to 'get_url'"),
            messages(r),
        )
    }

    @Test
    fun `an rpc on an enum is dropped`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    enum E { E_UNSPECIFIED = 0; E_A = 1; }
                    message M { int32 x = 1; }
                    service S { rpc Bad(E) returns (M); rpc Worse(M) returns (Missing); rpc Ok(M) returns (M); }
                    """
            )
        val out = text(r, "t.schemata")
        assertTrue("#1 ok(M): M" in out, out)
        assertFalse("bad(" in out, out)
        assertEquals(
            listOf(
                "SCH2405 service 'S': rpc 'Bad': request type 'E' is not a message; rpc dropped",
                "SCH2405 service 'S': rpc 'Worse': response type 'Missing' is not a message; rpc dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `a well-known payload other than a plain Empty drops the rpc`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    import "google/protobuf/empty.proto";
                    import "google/protobuf/timestamp.proto";
                    message M { int32 x = 1; }
                    service S {
                      rpc At(google.protobuf.Timestamp) returns (M);
                      rpc Ticks(M) returns (stream .google.protobuf.Empty);
                      rpc Ping(google.protobuf.Empty) returns (google.protobuf.Empty);
                    }
                    """
            )
        assertEquals(
            "schema t\n\nmodel M { #1 x int32 }\n\nservice S {\n  #1 ping()\n}\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 service 'S': rpc 'At': request type 'google.protobuf.Timestamp' has no Schemata model; rpc dropped",
                "SCH2405 service 'S': rpc 'Ticks': response stream of google.protobuf.Empty has no Schemata form; rpc dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `an rpc rename says how the proto name is kept`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S { rpc GetURL(M) returns (M); }
                    """
            )
        assertEquals(
            listOf("keep @proto(name) so the regenerated rpc keeps its proto name"),
            r.diagnostics.map { it.help },
        )
    }

    @Test
    fun `a service whose every rpc is dropped is kept empty`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    service S { rpc Bad(string) returns (string); }
                    """
            )
        assertEquals("schema t\n\nservice S {}\n", text(r, "t.schemata"))
        assertEquals(
            listOf(
                "SCH2405 service 'S': rpc 'Bad': request type 'string' is not a message; rpc dropped"
            ),
            messages(r),
        )
    }

    @Test
    fun `unreadable operation and reserved notes are ignored`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S {
                      rpc A(M) returns (M);  // schemata: fetch "/x"
                      // schemata: reserved #
                    }
                    """
            )
        assertTrue("#1 a(M): M\n" in text(r, "t.schemata"), text(r, "t.schemata"))
        assertEquals(
            listOf(
                "SCH2403 service 'S': rpc 'A': note 'fetch \"/x\"' cannot be read; ignored",
                "SCH2403 service 'S': note 'reserved #' cannot be read; ignored",
            ),
            messages(r),
        )
    }

    @Test
    fun `a backwards reserved range is a syntax error and a backwards reserved note is unreadable`() {
        val range =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; reserved 5 to 3; }
                    """
            )
        assertEquals(
            listOf("SCH2401 t.proto:3:26: cannot parse: reserved range 5 to 3 runs backwards"),
            messages(range),
        )
        val note =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S {
                      // schemata: reserved #5..#3
                    }
                    """
            )
        assertEquals(
            listOf("SCH2403 service 'S': note 'reserved #5..#3' cannot be read; ignored"),
            messages(note),
        )
    }

    @Test
    fun `an unreadable reserved note is reported at its own line`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S {
                      rpc A(M) returns (M);
                      // schemata: reserved #
                    }
                    """
            )
        assertEquals(listOf(6), r.diagnostics.map { it.span.startLine })
    }

    @Test
    fun `an ordinal a note repeats takes the next free one`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S {
                      rpc A(M) returns (M);  // schemata: #2
                      rpc B(M) returns (M);
                      rpc C(M) returns (M);
                    }
                    """
            )
        assertEquals(
            "schema t\n" +
                "\n" +
                "model M { #1 x int32 }\n" +
                "\n" +
                "service S {\n" +
                "  #2 a(M): M\n" +
                "  #3 b(M): M\n" +
                "  #4 c(M): M\n" +
                "}\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 service 'S': rpc 'B': ordinal #2 is already used; the next free ordinal is taken",
                "SCH2403 service 'S': rpc 'C': ordinal #3 is already used; the next free ordinal is taken",
            ),
            messages(r),
        )
    }

    @Test
    fun `an ordinal the service reserves takes the next free one`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S {
                      rpc A(M) returns (M);
                      rpc B(M) returns (M);
                      // schemata: reserved #2
                    }
                    """
            )
        assertEquals(
            "schema t\n" +
                "\n" +
                "model M { #1 x int32 }\n" +
                "\n" +
                "service S {\n" +
                "  #1 a(M): M\n" +
                "  #3 b(M): M\n" +
                "  reserved #2\n" +
                "}\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 service 'S': rpc 'B': ordinal #2 is already used; the next free ordinal is taken"
            ),
            messages(r),
        )
    }

    @Test
    fun `a payload from another namespace is imported`() {
        val r =
            importText(
                "a.proto" to
                    """
                    syntax = "proto3";
                    package a;
                    message Item { string name = 1; message Part { int32 n = 1; } }
                    """,
                "b.proto" to
                    """
                    syntax = "proto3";
                    package b;
                    import "a.proto";
                    service S { rpc Get(a.Item) returns (stream .a.Item.Part); }
                    """,
            )
        assertEquals(
            "schema b\n" +
                "\n" +
                "import a\n" +
                "\n" +
                "service S {\n" +
                "  #1 get(a.Item): stream a.Item.Part\n" +
                "}\n",
            text(r, "b.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a service and a message that lower to one name collide`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message orders { int32 x = 1; }
                    service Orders { rpc Get(orders) returns (orders); rpc get(orders) returns (orders); }
                    """
            )
        assertEquals(
            listOf(
                "SCH2401 t.proto: service 'Orders' and t.proto's message 'orders' both lower to " +
                    "service 'Orders'"
            ),
            messages(r),
        )
    }

    @Test
    fun `two rpcs that lower to one operation name collide`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { int32 x = 1; }
                    service S { rpc GetUrl(M) returns (M); rpc GetURL(M) returns (M); }
                    """
            )
        assertEquals(
            listOf("SCH2401 service 'S': rpc 'GetURL' and rpc 'GetUrl' both lower to 'get_url'"),
            messages(r),
        )
    }

    @Test
    fun `an import used only by options is not written to the schemata file`() {
        val r =
            importText(
                "opts.proto" to
                    """
                    syntax = "proto3";
                    package opts;
                    message Marker { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    import "opts.proto";
                    import public "x.proto";
                    message M { string id = 1; }
                    """,
                "x.proto" to
                    """
                    syntax = "proto3";
                    package x;
                    message X {}
                    """,
            )
        assertEquals("schema t\n\nmodel M { #1 id string }\n", text(r, "t.schemata"))
    }

    @Test
    fun `an import whose types are used is kept`() {
        val r =
            importText(
                "opts.proto" to
                    """
                    syntax = "proto3";
                    package opts;
                    message Marker { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    import "opts.proto";
                    message M { opts.Marker m = 1; }
                    """,
            )
        assertEquals(
            "schema t\n\nimport opts\n\nmodel M { #1 m opts.Marker }\n",
            text(r, "t.schemata"),
        )
    }

    @Test
    fun `imports name the namespace the imported file lowers to`() {
        val r =
            importText(
                "shop/customers.proto" to
                    """
                    syntax = "proto3";
                    package shop.customers;
                    message Customer { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "shop/customers.proto";
                    import public "x.proto";
                    import "google/protobuf/empty.proto";
                    import "google/protobuf/any.proto";
                    message M {
                      .shop.customers.Customer c = 1;
                      google.protobuf.Empty e = 2;
                      google.protobuf.Any a = 3;
                    }
                    """,
                "x.proto" to
                    """
                    syntax = "proto3";
                    message X {}
                    """,
            )
        assertEquals(
            """
            schema t

            import shop.customers

            model M { #1 c shop.customers.Customer  #2 e string  #3 a bytes }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2404 field 'M.e': google.protobuf.Empty imported as string",
                "SCH2404 field 'M.a': google.protobuf.Any imported as bytes",
            ),
            messages(r),
        )
        val missing =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "missing.proto";
                    message M {}
                    """
            )
        assertEquals(
            listOf("SCH2405 t.proto: import 'missing.proto' not found; dropped"),
            messages(missing),
        )
        assertEquals(listOf("t.schemata"), missing.files.map { it.path })
    }

    @Test
    fun `an unresolved import whose types are never used is a warning and the file is still emitted`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "udpa/annotations/status.proto";
                    message M { string id = 1; }
                    """
            )
        assertEquals(
            listOf("SCH2405 t.proto: import 'udpa/annotations/status.proto' not found; dropped"),
            messages(r),
        )
        assertEquals("add the directory that holds it with --include", r.diagnostics.single().help)
        assertEquals(2, r.diagnostics.single().span?.startLine)
        assertEquals(listOf("t.schemata"), r.files.map { it.path })
    }

    @Test
    fun `an unresolved type names the unresolved imports in its hint`() {
        val one =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "a.proto";
                    message M { a.A x = 1; }
                    """
            )
        assertEquals(listOf("SCH2405", "SCH2401"), one.diagnostics.map { it.code.id })
        assertEquals(
            "import 'a.proto' was not found; add its directory with --include",
            one.diagnostics.last().help,
        )
        val two =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    import "a.proto";
                    import "b.proto";
                    message M { a.A x = 1; }
                    """
            )
        assertEquals(
            "imports 'a.proto', 'b.proto' were not found; add their directory with --include",
            two.diagnostics.last().help,
        )
        val none =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M { a.A x = 1; }
                    """
            )
        assertEquals(
            "add the schema that declares it to the inputs, or fix the reference",
            none.diagnostics.single().help,
        )
    }

    @Test
    fun `a file found under a root is still emitted when nothing references it`() {
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "/r/a.proto",
                        "syntax = \"proto3\";\npackage a;\nimport \"b.proto\";\nmessage A {}\n",
                        relative = "a.proto",
                    )
                ),
                null,
            ) { path ->
                if (path == "/r/b.proto")
                    ImportInput(path, "syntax = \"proto3\";\npackage b;\nmessage B {}\n")
                else null
            }
        assertEquals(listOf("a.schemata", "b.schemata"), r.files.map { it.path })
    }

    @Test
    fun `a file found beside an importer is not emitted when nothing references it`() {
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "/in/a.proto",
                        "syntax = \"proto3\";\npackage a;\nimport \"b.proto\";\nmessage A {}\n",
                    )
                ),
                null,
            ) { path ->
                if (path == "/in/b.proto")
                    ImportInput(path, "syntax = \"proto3\";\npackage b;\nmessage B {}\n")
                else null
            }
        assertEquals(listOf("a.schemata"), r.files.map { it.path })
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a type from an unimported file is reported with the file that declares it`() {
        val r =
            importText(
                "shop/a.proto" to
                    """
                    syntax = "proto3";
                    package shop.a;
                    message A { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { shop.a.A a = 1; }
                    """,
            )
        assertEquals(
            listOf(
                "SCH2401 field 'M.a': type 'shop.a.A' cannot be resolved; " +
                    "'shop/a.proto' declares 'shop.a.A' but t.proto does not import it"
            ),
            messages(r),
        )
        assertEquals("add import \"shop/a.proto\" to t.proto", r.diagnostics.single().help)
    }

    @Test
    fun `import public makes the re-exported types visible to the importing file`() {
        val r =
            importText(
                "a.proto" to
                    """
                    syntax = "proto3";
                    package a;
                    import public "b.proto";
                    message A { string id = 1; }
                    """,
                "b.proto" to
                    """
                    syntax = "proto3";
                    package b;
                    message B { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    import "a.proto";
                    message M { b.B b = 1; a.A a = 2; }
                    """,
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(
            """
            schema t

            import b
            import a

            model M { #1 b b.B  #2 a a.A }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
    }

    @Test
    fun `import public resolves by the resolved path when files sit under a root`() {
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "api/x.proto",
                        "syntax = \"proto3\";\npackage x;\nmessage X { string id = 1; }\n",
                        relative = "x.proto",
                    ),
                    ImportInput(
                        "api/a.proto",
                        "syntax = \"proto3\";\npackage a;\nimport public \"x.proto\";\n" +
                            "message A { string id = 1; }\n",
                        relative = "a.proto",
                    ),
                    ImportInput(
                        "api/t.proto",
                        "syntax = \"proto3\";\npackage t;\nimport \"a.proto\";\n" +
                            "message M { x.X x = 1; }\n",
                        relative = "t.proto",
                    ),
                )
            )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an rpc payload from an unimported file is reported with the file that declares it`() {
        val r =
            importText(
                "shop/a.proto" to
                    """
                    syntax = "proto3";
                    package shop.a;
                    message A { string id = 1; }
                    """,
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M { string id = 1; }
                    service S { rpc Get(shop.a.A) returns (M); }
                    """,
            )
        assertEquals(
            listOf(
                "SCH2401 rpc 'S.Get': type 'shop.a.A' cannot be resolved; " +
                    "'shop/a.proto' declares 'shop.a.A' but t.proto does not import it"
            ),
            messages(r),
        )
        assertEquals("add import \"shop/a.proto\" to t.proto", r.diagnostics.single().help)
    }

    @Test
    fun `an unresolved type is an error and nothing is emitted`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M { Missing m = 1; string s = 2; }
                    """
            )
        assertEquals(listOf("SCH2401 field 'M.m': type 'Missing' cannot be resolved"), messages(r))
        assertEquals(emptyList(), r.files)
    }

    @Test
    fun `references are spelled as Schemata resolves them from the use site`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message Card { bool top = 1; }
                    message Order {
                      Card inner = 1;
                      .t.Card outer = 2;
                      message Card { bool nested = 1; }
                      message Line { Card near = 1; Order.Card again = 2; }
                    }
                    message Audit { Order.Line line = 1; Card card = 2; }
                    """
            )
        assertEquals(
            """
            schema t

            model Card { #1 top bool }

            model Order {
              #1 inner Card
              #2 outer t.Card

              model Card { #1 nested bool }

              model Line { #1 near Card  #2 again Card }
            }

            model Audit { #1 line Order.Line  #2 card Card }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `maps with other key types`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M { map<uint32, string> m = 1; map<bool, string> b = 2; }
                    """
            )
        assertEquals(
            """
            schema t

            model M { #1 m map<int64, string> }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2404 field 'M.m': uint32 map key imported as int64",
                "SCH2405 field 'M.b': map with a bool key dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `a package other than the path is carried by an annotation`() {
        val r =
            importText(
                "corpus/orders.proto" to
                    """
                    syntax = "proto3";
                    package corp.orders.v1;
                    message Order {}
                    """
            )
        assertEquals(
            """
            schema corpus.orders @proto(package: "corp.orders.v1")

            model Order {}
            """
                .trimIndent() + "\n",
            text(r, "corpus/orders.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a lone file takes its package or its stem`() {
        val money =
            ProtoImporter.import(
                listOf(ImportInput("money.proto", "syntax = \"proto3\";\npackage google.type;\n"))
            )
        assertEquals(listOf("google/type.schemata"), money.files.map { it.path })
        assertEquals("schema google.type\n", money.files.single().content)
        assertEquals(emptyList(), messages(money))
        val upper =
            ProtoImporter.import(
                listOf(ImportInput("money.proto", "syntax = \"proto3\";\npackage Google.Type;\n"))
            )
        assertEquals(
            "schema money @proto(package: \"Google.Type\")\n",
            text(upper, "money.schemata"),
        )
        assertEquals(
            listOf("SCH2402 money.proto: schema name 'money' was derived from the file name"),
            messages(upper),
        )
    }

    @Test
    fun `files sharing a package under a root merge into one unit`() {
        val r =
            importText(
                "a.proto" to
                    """
                    syntax = "proto3";
                    package corp;
                    message A {}
                    message M { int32 x = 1; }
                    """,
                "b.proto" to
                    """
                    syntax = "proto3";
                    package corp;
                    import "a.proto";
                    message B { A a = 1; }
                    """,
            )
        assertEquals(listOf("corp.schemata"), r.files.map { it.path })
        assertEquals(
            """
            schema corp

            model A {}

            model M { #1 x int32 }

            model B { #1 a A }
            """
                .trimIndent() + "\n",
            text(r, "corp.schemata"),
        )
        assertEquals(emptyList(), messages(r))
        val clash =
            importText(
                "a.proto" to "syntax = \"proto3\";\npackage corp;\nmessage M { int32 x = 1; }\n",
                "b.proto" to "syntax = \"proto3\";\npackage corp;\nmessage M { int32 y = 1; }\n",
            )
        assertEquals(
            listOf(
                "SCH2401 b.proto: message 'M' and a.proto's message 'M' both lower to model 'M'"
            ),
            messages(clash),
        )
        assertEquals(emptyList(), clash.files)
    }

    @Test
    fun `a file that does not parse is an error at its position`() {
        val r = importText("t.proto" to "syntax = \"proto3\";\nmessage {")
        assertEquals(
            listOf("SCH2401 t.proto:2:9: cannot parse: expected a message name"),
            messages(r),
        )
    }

    @Test
    fun `an import outside the inputs is read under the root then beside the importing file`() {
        val files =
            mapOf(
                "root/b/people.proto" to "syntax = \"proto3\";\npackage b.people;\nmessage P {}\n",
                "dir/y.proto" to "syntax = \"proto3\";\npackage y;\nmessage Y {}\n",
            )
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "root/a/orders.proto",
                        "syntax = \"proto3\";\nimport \"b/people.proto\";\nmessage O { .b.people.P p = 1; }\n",
                        relative = "a/orders.proto",
                    ),
                    ImportInput(
                        "dir/x.proto",
                        "syntax = \"proto3\";\npackage x;\nimport \"y.proto\";\nmessage X { y.Y y = 1; }\n",
                    ),
                ),
                null,
            ) { path ->
                files[path]?.let { ImportInput(path, it) }
            }
        assertEquals(emptyList(), messages(r))
        assertEquals(
            listOf("a/orders.schemata", "x.schemata", "b/people.schemata", "y.schemata"),
            r.files.map { it.path },
        )
        assertEquals(
            "schema a.orders\n\nimport b.people\n\nmodel O { #1 p b.people.P }\n",
            text(r, "a/orders.schemata"),
        )
        assertEquals("schema x\n\nimport y\n\nmodel X { #1 y y.Y }\n", text(r, "x.schemata"))
    }

    @Test
    fun `a shared package that is not a namespace name is lower-snaked`() {
        val r =
            importText(
                "corp/a.proto" to "syntax = \"proto3\";\npackage Corp.Orders;\nmessage A {}",
                "corp/b.proto" to "syntax = \"proto3\";\npackage Corp.Orders;\nmessage B {}",
            )
        assertEquals(
            "schema corp.orders @proto(package: \"Corp.Orders\")\n" +
                "\n" +
                "model A {}\n" +
                "\n" +
                "model B {}\n",
            text(r, "corp/orders.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2402 corp/a.proto: schema name 'corp.orders' was derived from the package 'Corp.Orders'",
                "SCH2402 corp/b.proto: schema name 'corp.orders' was derived from the package 'Corp.Orders'",
            ),
            messages(r),
        )
    }

    @Test
    fun `two packages lowering to one namespace is an error`() {
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "x.proto",
                        "syntax = \"proto3\";\npackage p;\n",
                        relative = "x.proto",
                    ),
                    ImportInput("other/y.proto", "syntax = \"proto3\";\npackage x;\n"),
                )
            )
        assertEquals(
            listOf(
                "SCH2401 other/y.proto: package 'x' and x.proto's package 'p' both lower to schema 'x'"
            ),
            messages(r),
        )
    }

    @Test
    fun `a placeholder-only enum keeps its value under a spelling the target can write`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    enum Placeholder { PLACEHOLDER_UNSPECIFIED = 0; }
                    enum Aliased {
                      option allow_alias = true;
                      ALIASED_UNSPECIFIED = 0;
                      ALIASED_NONE = 0;
                    }
                    """
            )
        assertEquals(
            """
            schema t

            enum Placeholder { @proto(name: "PLACEHOLDER_UNSPECIFIED_VALUE") unspecified }

            enum Aliased { @proto(name: "ALIASED_UNSPECIFIED_VALUE") unspecified }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 enum 'Placeholder': only value 'PLACEHOLDER_UNSPECIFIED' kept, as 'unspecified'; the regenerated enum spells it PLACEHOLDER_UNSPECIFIED_VALUE beside the synthesized zero value",
                "SCH2405 enum value 'Aliased.ALIASED_NONE': alias of 'ALIASED_UNSPECIFIED' dropped",
                "SCH2403 enum 'Aliased': only value 'ALIASED_UNSPECIFIED' kept, as 'unspecified'; the regenerated enum spells it ALIASED_UNSPECIFIED_VALUE beside the synthesized zero value",
            ),
            messages(r),
        )
    }

    private fun beside(
        inputs: List<Pair<String, String>>,
        located: Map<String, String>,
    ): ImportResult =
        ProtoImporter.import(inputs.map { ImportInput(it.first, it.second.trimIndent()) }, null) {
            path ->
            located[path]?.let { ImportInput(path, it.trimIndent()) }
        }

    @Test
    fun `an unreferenced beside file leaves no diagnostics for its own unresolved imports`() {
        val r =
            beside(
                listOf(
                    "/in/t.proto" to
                        "syntax = \"proto3\";\npackage t;\nimport \"a.proto\";\nmessage T {}"
                ),
                mapOf(
                    "/in/a.proto" to
                        "syntax = \"proto3\";\npackage a;\nimport \"gone.proto\";\nmessage A {}"
                ),
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("t.schemata"), r.files.map { it.path })
    }

    @Test
    fun `a type declared only in a dropped beside file is named in the hint`() {
        val r =
            beside(
                listOf(
                    "/in/t.proto" to
                        "syntax = \"proto3\";\npackage t;\nimport \"a.proto\";\nmessage T { b.B x = 1; }"
                ),
                mapOf(
                    "/in/a.proto" to
                        "syntax = \"proto3\";\npackage a;\nimport \"b.proto\";\nmessage A {}",
                    "/in/b.proto" to "syntax = \"proto3\";\npackage b;\nmessage B {}",
                ),
            )
        assertEquals(
            listOf(
                "SCH2401 field 'T.x': type 'b.B' cannot be resolved; " +
                    "'/in/b.proto' declares 'b.B' but /in/t.proto does not import it"
            ),
            messages(r),
        )
        assertEquals("add import \"/in/b.proto\" to /in/t.proto", r.diagnostics.single().help)
    }

    @Test
    fun `a beside file referenced only through another beside file is kept`() {
        val r =
            beside(
                listOf(
                    "/in/t.proto" to
                        "syntax = \"proto3\";\npackage t;\nimport \"a.proto\";\nmessage T { a.A x = 1; }"
                ),
                mapOf(
                    "/in/a.proto" to
                        "syntax = \"proto3\";\npackage a;\nimport \"b.proto\";\nmessage A { b.B y = 1; }",
                    "/in/b.proto" to "syntax = \"proto3\";\npackage b;\nmessage B {}",
                ),
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("t.schemata", "a.schemata", "b.schemata"), r.files.map { it.path })
    }

    @Test
    fun `a cycle between beside files terminates`() {
        val r =
            beside(
                listOf(
                    "/in/t.proto" to
                        "syntax = \"proto3\";\npackage t;\nimport \"a.proto\";\nmessage T { a.A x = 1; }"
                ),
                mapOf(
                    "/in/a.proto" to
                        "syntax = \"proto3\";\npackage a;\nimport \"b.proto\";\nmessage A { b.B y = 1; }",
                    "/in/b.proto" to
                        "syntax = \"proto3\";\npackage b;\nimport \"a.proto\";\nmessage B { a.A z = 1; }",
                ),
            )
        assertEquals(3, r.files.size)
    }

    private fun withIncludes(
        inputs: List<Pair<String, String>>,
        includes: Map<String, Map<String, String>>,
    ): ImportResult =
        ProtoImporter.import(
            inputs.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) },
            null,
            { path ->
                includes.entries.firstNotNullOfOrNull { (dir, files) ->
                    files.entries
                        .firstOrNull {
                            val base = dir.trimEnd('/')
                            (if (base.isEmpty()) it.key else "$base/${it.key}") == path
                        }
                        ?.let { ImportInput(path, it.value.trimIndent()) }
                }
            },
            includes.keys.toList(),
        )

    @Test
    fun `an import found under an include root is read and takes its namespace from its path there`() {
        val r =
            withIncludes(
                listOf(
                    "app/t.proto" to
                        """
                        syntax = "proto3";
                        package app;
                        import "google/rpc/status.proto";
                        message T { google.rpc.Status status = 1; }
                        """
                ),
                mapOf(
                    "/inc" to
                        mapOf(
                            "google/rpc/status.proto" to
                                """
                                syntax = "proto3";
                                package google.rpc;
                                message Status { int32 code = 1; }
                                """
                        )
                ),
            )
        assertEquals(emptyList(), messages(r).filter { it.startsWith("SCH2405") })
        assertEquals(
            listOf("app/t.schemata", "google/rpc/status.schemata"),
            r.files.map { it.path }.sorted(),
        )
        assertTrue(text(r, "google/rpc/status.schemata").startsWith("schema google.rpc.status"))
        assertTrue(
            text(r, "google/rpc/status.schemata").contains("@proto(package: \"google.rpc\")")
        )
    }

    @Test
    fun `an input beats an include root and the first include root beats the second`() {
        fun dep(name: String) =
            """
            syntax = "proto3";
            package x;
            message $name {}
            """
        val r =
            withIncludes(
                listOf(
                    "x/dep.proto" to dep("Input"),
                    "app/t.proto" to
                        """
                        syntax = "proto3";
                        package app;
                        import "x/dep.proto";
                        import "x/other.proto";
                        message T { x.Input a = 1; x.FromA b = 2; }
                        """,
                ),
                mapOf(
                    "/a" to mapOf("x/dep.proto" to dep("FromA"), "x/other.proto" to dep("FromA")),
                    "/b" to mapOf("x/dep.proto" to dep("FromB"), "x/other.proto" to dep("FromB")),
                ),
            )
        assertEquals(emptyList(), messages(r))
        val all = r.files.joinToString("\n") { it.content }
        assertTrue(all.contains("Input"), all)
        assertTrue(all.contains("FromA"), all)
        assertFalse(all.contains("FromB"), all)
    }

    @Test
    fun `an include file is emitted only when an input references it`() {
        val r =
            withIncludes(
                listOf(
                    "app/t.proto" to
                        """
                        syntax = "proto3";
                        package app;
                        import "validate/validate.proto";
                        import "google/rpc/status.proto";
                        message T { google.rpc.Status status = 1 [(validate.rules).message.required = true]; }
                        """
                ),
                mapOf(
                    "/inc" to
                        mapOf(
                            "validate/validate.proto" to
                                """
                                syntax = "proto2";
                                package validate;
                                import "google/protobuf/descriptor.proto";
                                extend google.protobuf.FieldOptions { optional string rules = 1071; }
                                """,
                            "google/rpc/status.proto" to
                                """
                                syntax = "proto3";
                                package google.rpc;
                                message Status { int32 code = 1; }
                                """,
                        )
                ),
            )
        assertEquals(
            listOf("app/t.schemata", "google/rpc/status.schemata"),
            r.files.map { it.path }.sorted(),
        )
        assertEquals(emptyList(), messages(r).filter { it.startsWith("SCH2402") })
    }

    private fun statusUser() =
        listOf(
            "app/t.proto" to
                """
                syntax = "proto3";
                package app;
                import "google/rpc/status.proto";
                message T { google.rpc.Status status = 1; }
                """
        )

    private fun statusFile() =
        mapOf(
            "google/rpc/status.proto" to
                """
                syntax = "proto3";
                package google.rpc;
                message Status { int32 code = 1; }
                """
        )

    @Test
    fun `an include root given as the current directory resolves imports by their bare path`() {
        // The input sits under src, so the roots step looks under src, not at the bare path.
        val files = statusFile()
        val r =
            ProtoImporter.import(
                statusUser().map {
                    ImportInput("src/${it.first}", it.second.trimIndent(), relative = it.first)
                },
                null,
                { path -> files[path]?.let { ImportInput(path, it.trimIndent()) } },
                listOf(""),
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(
            listOf("app/t.schemata", "google/rpc/status.schemata"),
            r.files.map { it.path }.sorted(),
        )
    }

    @Test
    fun `a trailing slash on an include root resolves the same as none`() {
        val plain = withIncludes(statusUser(), mapOf("deps" to statusFile()))
        val slash = withIncludes(statusUser(), mapOf("deps/" to statusFile()))
        assertEquals(emptyList(), messages(slash))
        assertEquals(
            plain.files.map { it.path to it.content },
            slash.files.map { it.path to it.content },
        )
        assertEquals(2, slash.files.size)
    }

    @Test
    fun `a file found beside an include file is written only when referenced`() {
        val r =
            withIncludes(
                listOf(
                    "app/t.proto" to
                        """
                        syntax = "proto3";
                        package app;
                        import "status.proto";
                        message T { google.rpc.Status status = 1; }
                        """
                ),
                mapOf(
                    "/inc" to
                        mapOf(
                            "status.proto" to
                                """
                                syntax = "proto3";
                                package google.rpc;
                                import "unused.proto";
                                message Status { int32 code = 1; }
                                """,
                            "unused.proto" to
                                """
                                syntax = "proto3";
                                package unused;
                                message Unused {}
                                """,
                        )
                ),
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("app/t.schemata", "status.schemata"), r.files.map { it.path }.sorted())
    }

    @Test
    fun `a root file keeps the include files it references`() {
        val files =
            mapOf(
                "/r/lib/u.proto" to
                    """
                    syntax = "proto3";
                    package lib;
                    import "google/rpc/status.proto";
                    message U { google.rpc.Status status = 1; }
                    """,
                "/inc/google/rpc/status.proto" to statusFile().getValue("google/rpc/status.proto"),
            )
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "/r/app/t.proto",
                        "syntax = \"proto3\";\npackage app;\nimport \"lib/u.proto\";\nmessage T {}\n",
                        relative = "app/t.proto",
                    )
                ),
                null,
                { path -> files[path]?.let { ImportInput(path, it.trimIndent()) } },
                listOf("/inc"),
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(
            listOf("app/t.schemata", "google/rpc/status.schemata", "lib/u.schemata"),
            r.files.map { it.path }.sorted(),
        )
    }
}
