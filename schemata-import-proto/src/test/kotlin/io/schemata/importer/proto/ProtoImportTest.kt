package io.schemata.importer.proto

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals

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
            namespace corp.orders

            record Order {
              #1  id:     string
              #2  n:      int32?
              #3  tags:   list<string>
              #4  counts: map<string, int64>
              #5  at:     instant
              #6  nick:   string?
              #7  s:      int32
              #8  u:      int64(min = 0, max = 4294967295)
              #9  big:    int64(min = 0)
              #10 f:      int64(min = 0)
              #11 line:   Line
              #12 maybe:  Line?

              record Line { #1 qty: int64 }
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
                      int32 o = 3;  // schemata: int32(min = 0); default = 3
                      repeated string l = 4;  // schemata: list<string(max = 3)>(max = 2)
                      map<string, string> m = 5;  // schemata: map<string, decimal(19, 4)>
                      Status s = 6;  // schemata: default = STATUS_PENDING
                    }
                    """
            )
        assertEquals(
            """
            namespace t

            enum Status { #1 pending }

            record M {
              #1 f: decimal(10, 2)
              #2 d: date?
              #3 o: int32(min = 0) = 3
              #4 l: list<string(max = 3)>(max = 2)
              #5 m: map<string, decimal(19, 4)>
              #6 s: Status = pending
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
            namespace t

            record M { #1 a: int32 #2 b: string }
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
                      string f = 1;  // schemata: string(pattern = "^a; b$"); default = "x; y"
                    }
                    """
            )
        assertEquals(
            """
            namespace t

            record M { #1 f: string(pattern = "^a; b$") = "x; y" }
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
            namespace t

            enum Status {
              #1 pending
              @deprecated #2 paid
              reserved #5..#6, "old"
            }

            enum Color { @proto(name = "RED") red, @proto(name = "GREEN") green }

            enum K { #1 a }

            enum E { #1 a }

            enum V { #1 foo_bar, @proto(name = "CUSTOM") #2 custom }
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
            namespace t

            union Payment = #1 Card | #2 Cash | #3 uuid

            record Card {}

            record Cash {}

            union P = #1 Card | #2 Cash

            record Q { #1 x: int32 #2 a: string? #3 b: string? }

            record S { #1 a: string? #2 b: string? }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 union 'P': member element 'c' has no Schemata equivalent; the regenerated oneof names it 'card'",
                "SCH2403 record 'Q': oneof 'which' imported as nullable fields; at most one of them is set, which Schemata cannot say",
                "SCH2403 record 'S': oneof 'k' imported as nullable fields; at most one of them is set, which Schemata cannot say",
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
            namespace t

            /// A message.
            /// Second line.
            @deprecated
            record M {
              /// The field.
              ///
              /// trailing words
              #1 f: string
              @deprecated #2 g: int32
              #3 h: list<int32>
              /// Block doc.
              #4 i: string
              reserved #7, #9..#11, "old", "older"
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
                    message foo_bar { string FooBaz = 1; string record = 2; }
                    """
            )
        assertEquals(
            """
            namespace t

            @proto(name = "foo_bar")
            record FooBar {
              @proto(name = "FooBaz") #1 foo_baz:      string
              @proto(name = "record") #2 record_value: string
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
            namespace t

            record M { #1 a: int32 #2 b: string = "x" }
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
            namespace t

            enum Status { #1 pending }

            record M {
              #1 a: int32 = -3
              #2 b: float64 = 1.5
              #3 c: bool = true
              #4 d: Status = pending
              #5 e: string = "a\"b"
              #6 f: float64?
              #7 g: int32?
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
    fun `services and extend are dropped`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto2";
                    message M { optional int32 a = 1; extensions 100 to 200; }
                    extend M { optional int32 x = 100; }
                    service S { rpc A (M) returns (M); rpc B (stream M) returns (M); }
                    """
            )
        assertEquals(
            listOf(
                "SCH2405 message 'M': extensions dropped",
                "SCH2405 t.proto: extend dropped",
                "SCH2405 service 'S': rpc 'A' dropped; services arrive in a later version",
                "SCH2405 service 'S': rpc 'B' dropped; services arrive in a later version",
            ),
            messages(r),
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
                    import "missing.proto";
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
            namespace t

            import shop.customers
            import x

            record M { #1 c: shop.customers.Customer #2 e: string #3 a: bytes }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 t.proto: import public 'x.proto' re-exports nothing in Schemata",
                "SCH2401 t.proto: import 'missing.proto' cannot be resolved",
                "SCH2404 field 'M.e': google.protobuf.Empty imported as string",
                "SCH2404 field 'M.a': google.protobuf.Any imported as bytes",
            ),
            messages(r),
        )
    }

    @Test
    fun `an unresolved type is an error and the field is left out`() {
        val r =
            importText(
                "t.proto" to
                    """
                    syntax = "proto3";
                    message M { Missing m = 1; string s = 2; }
                    """
            )
        assertEquals(listOf("SCH2401 field 'M.m': type 'Missing' cannot be resolved"), messages(r))
        assertEquals(
            """
            namespace t

            record M { #2 s: string }
            """
                .trimIndent() + "\n",
            text(r, "t.schemata"),
        )
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
            namespace t

            record Card { #1 top: bool }

            record Order {
              #1 inner: Card
              #2 outer: t.Card

              record Card { #1 nested: bool }

              record Line { #1 near: Card #2 again: Card }
            }

            record Audit { #1 line: Order.Line #2 card: Card }
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
            namespace t

            record M { #1 m: map<int64, string> }
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
            @proto(package = "corp.orders.v1")
            namespace corpus.orders

            record Order {}
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
        assertEquals("namespace google.type\n", money.files.single().content)
        assertEquals(emptyList(), messages(money))
        val upper =
            ProtoImporter.import(
                listOf(ImportInput("money.proto", "syntax = \"proto3\";\npackage Google.Type;\n"))
            )
        assertEquals(
            "@proto(package = \"Google.Type\")\nnamespace money\n",
            text(upper, "money.schemata"),
        )
        assertEquals(
            listOf("SCH2402 money.proto: namespace 'money' was derived from the file name"),
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
                    message B { A a = 1; }
                    message M { int32 y = 1; }
                    """,
            )
        assertEquals(listOf("corp.schemata"), r.files.map { it.path })
        assertEquals(
            """
            namespace corp

            record A {}

            record M { #1 x: int32 }

            record B { #1 a: A }
            """
                .trimIndent() + "\n",
            text(r, "corp.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2401 b.proto: message 'M' and a.proto's message 'M' both lower to record 'M'"
            ),
            messages(r),
        )
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
            "namespace a.orders\n\nimport b.people\n\nrecord O { #1 p: b.people.P }\n",
            text(r, "a/orders.schemata"),
        )
        assertEquals("namespace x\n\nimport y\n\nrecord X { #1 y: y.Y }\n", text(r, "x.schemata"))
    }

    @Test
    fun `a shared package that is not a namespace name is lower-snaked`() {
        val r =
            importText(
                "corp/a.proto" to "syntax = \"proto3\";\npackage Corp.Orders;\nmessage A {}",
                "corp/b.proto" to "syntax = \"proto3\";\npackage Corp.Orders;\nmessage B {}",
            )
        assertEquals(
            "@proto(package = \"Corp.Orders\")\nnamespace corp.orders\n\nrecord A {}\n\nrecord B {}\n",
            text(r, "corp/orders.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2402 corp/a.proto: namespace 'corp.orders' was derived from the package 'Corp.Orders'",
                "SCH2402 corp/b.proto: namespace 'corp.orders' was derived from the package 'Corp.Orders'",
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
                "SCH2401 other/y.proto: package 'x' and x.proto's package 'p' both lower to namespace 'x'"
            ),
            messages(r),
        )
    }
}
