package io.schemata.importer.proto

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ValidateRulesTest {
    /** One field declaration, the field line it emits, and every diagnostic it raises. */
    private class Row(
        val name: String,
        val decl: String,
        val field: String,
        val diagnostics: List<String> = emptyList(),
    )

    private fun import(body: String): ImportResult =
        ProtoImporter.import(
            listOf(
                ImportInput(
                    "validate/validate.proto",
                    "syntax = \"proto3\";\npackage validate;\n",
                    relative = "validate/validate.proto",
                ),
                ImportInput(
                    "m.proto",
                    """
                    syntax = "proto3";
                    package m;
                    import "validate/validate.proto";
                    import "google/protobuf/wrappers.proto";
                    import "google/protobuf/any.proto";
                    import "google/protobuf/duration.proto";
                    import "google/protobuf/timestamp.proto";
                    enum E { E_UNSPECIFIED = 0; E_A = 1; }
                    message Line { int32 n = 1; }
                    message M { $body }
                    """
                        .trimIndent(),
                    relative = "m.proto",
                ),
            )
        )

    /** The emitted line of field [start]; a model of one field is written on a single line. */
    private fun fieldLine(result: ImportResult, start: String = "#1"): String {
        val lines =
            result.files
                .single { it.path == "m.schemata" }
                .content
                .lines()
                .map { it.trim().replace(Regex("\\s+"), " ") }
        val from = lines.indexOfFirst { it.startsWith("model M") }
        if (lines[from].contains(" $start ")) {
            return lines[from].substringAfter("{ ").removeSuffix(" }")
        }
        return lines.drop(from + 1).first { it.startsWith("$start ") }
    }

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun w(code: String, text: String) = "$code field 'M.f': $text"

    private fun dropped(key: String, n: Int = 1) =
        "SCH2405 m.proto: validate rule '$key' dropped on $n field${if (n == 1) "" else "s"}"

    private val rows =
        listOf(
            Row(
                "int gte lte",
                "int32 f = 1 [(validate.rules).int32 = {gte: 1, lte: 65535}];",
                "#1 f int32 { min 1, max 65535 }",
            ),
            Row(
                "int gt lt",
                "int64 f = 1 [(validate.rules).int64 = {gt: 0, lt: 10}];",
                "#1 f int64 { min 1, max 9 }",
            ),
            Row(
                "int const",
                "int32 f = 1 [(validate.rules).int32.const = 5];",
                "#1 f int32 { min 5, max 5 }",
            ),
            Row(
                "int in and not_in",
                "int32 f = 1 [(validate.rules).int32 = {in: [1, 2], not_in: [3]}];",
                "#1 f int32",
                listOf(dropped("int32.in"), dropped("int32.not_in")),
            ),
            Row(
                "uint32 keeps the widened bounds",
                "uint32 f = 1 [(validate.rules).uint32 = {gte: 1, lte: 5000000000}];",
                "#1 f int64 { min 1, max 4294967295 }",
                listOf(w("SCH2404", "uint32 imported as int64(min = 0, max = 4294967295)")),
            ),
            Row(
                "uint32 tightens the widened bounds",
                "uint32 f = 1 [(validate.rules).uint32 = {gt: 0, lt: 100}];",
                "#1 f int64 { min 1, max 99 }",
                listOf(w("SCH2404", "uint32 imported as int64(min = 0, max = 4294967295)")),
            ),
            Row(
                "float gt",
                "double f = 1 [(validate.rules).double.gt = 0.5];",
                "#1 f float64 { min 0.5 }",
                listOf(w("SCH2403", "gt 0.5 imported as min 0.5; the bound is inclusive")),
            ),
            Row(
                "float lt and lte",
                "float f = 1 [(validate.rules).float = {gte: 0, lt: 9.5}];",
                "#1 f float32 { min 0, max 9.5 }",
                listOf(w("SCH2403", "lt 9.5 imported as max 9.5; the bound is inclusive")),
            ),
            Row(
                "float const",
                "double f = 1 [(validate.rules).double.const = 1.5];",
                "#1 f float64 { min 1.5, max 1.5 }",
            ),
            Row(
                "wrapper takes the scalar rules",
                "google.protobuf.Int32Value f = 1 [(validate.rules).int32.gte = 1];",
                "#1 f int32? { min 1 }",
                listOf(
                    w(
                        "SCH2403",
                        "google.protobuf.Int32Value imported as int32?; the regenerated field " +
                            "is optional, not a wrapper",
                    )
                ),
            ),
            Row(
                "string lengths",
                "string f = 1 [(validate.rules).string = {min_len: 1, max_len: 64}];",
                "#1 f string { min 1, max 64 }",
            ),
            Row(
                "string len",
                "string f = 1 [(validate.rules).string.len = 8];",
                "#1 f string { min 8, max 8 }",
            ),
            Row(
                "string min_bytes 1",
                "string f = 1 [(validate.rules).string.min_bytes = 1];",
                "#1 f string { min 1 }",
            ),
            Row(
                "string min_bytes 5",
                "string f = 1 [(validate.rules).string.min_bytes = 5];",
                "#1 f string { min 2 }",
                listOf(w("SCH2404", "min_bytes 5 imported as min 2; Schemata counts characters")),
            ),
            Row(
                "string max_bytes",
                "string f = 1 [(validate.rules).string.max_bytes = 10];",
                "#1 f string { max 10 }",
                listOf(w("SCH2404", "max_bytes 10 imported as max 10; Schemata counts characters")),
            ),
            Row(
                "string min_len and min_bytes take the tighter",
                "string f = 1 [(validate.rules).string = {min_len: 3, min_bytes: 4}];",
                "#1 f string { min 3 }",
                listOf(w("SCH2404", "min_bytes 4 imported as min 1; Schemata counts characters")),
            ),
            Row(
                "string pattern",
                "string f = 1 [(validate.rules).string.pattern = \"^[a-z]+\"];",
                "#1 f string { match \"^[a-z]+\" }",
            ),
            Row(
                "string pattern that does not compile",
                "string f = 1 [(validate.rules).string.pattern = \"([\"];",
                "#1 f string",
                listOf(
                    w(
                        "SCH2405",
                        "validate pattern '([' dropped; it does not compile: " +
                            "Unclosed character class",
                    )
                ),
            ),
            Row(
                "string prefix",
                "string f = 1 [(validate.rules).string.prefix = \"ab.c\"];",
                """#1 f string { match "^ab\\.c" }""",
            ),
            Row(
                "string suffix",
                "string f = 1 [(validate.rules).string.suffix = \"a+b\"];",
                """#1 f string { match "a\\+b$" }""",
            ),
            Row(
                "string contains",
                "string f = 1 [(validate.rules).string.contains = \"a(b)\"];",
                """#1 f string { match "a\\(b\\)" }""",
            ),
            Row(
                "string in",
                "string f = 1 [(validate.rules).string = {in: [\"a\", \"b.c\"]}];",
                """#1 f string { match "^(a|b\\.c)$" }""",
            ),
            Row("string uuid", "string f = 1 [(validate.rules).string.uuid = true];", "#1 f uuid"),
            Row(
                "string uuid with lengths",
                "string f = 1 [(validate.rules).string = {uuid: true, min_len: 36}];",
                "#1 f uuid",
            ),
            Row(
                "string formats are dropped",
                "string f = 1 [(validate.rules).string = {email: true, hostname: true}];",
                "#1 f string",
                listOf(dropped("string.email"), dropped("string.hostname")),
            ),
            Row(
                "string ip uri and address",
                "string f = 1 [(validate.rules).string = {ip: true, ipv4: true, ipv6: true, " +
                    "uri: true, uri_ref: true, address: true}];",
                "#1 f string",
                listOf("ip", "ipv4", "ipv6", "uri", "uri_ref", "address").map {
                    dropped("string.$it")
                },
            ),
            Row(
                "string not_in and not_contains",
                "string f = 1 [(validate.rules).string = {not_in: [\"a\"], not_contains: \"b\"}];",
                "#1 f string",
                listOf(dropped("string.not_in"), dropped("string.not_contains")),
            ),
            Row(
                "string well_known_regex hides strict",
                "string f = 1 [(validate.rules).string = {well_known_regex: HTTP_HEADER_NAME, " +
                    "strict: false}];",
                "#1 f string",
                listOf(dropped("string.well_known_regex")),
            ),
            Row(
                "string strict alone",
                "string f = 1 [(validate.rules).string.strict = false];",
                "#1 f string",
                listOf(dropped("string.strict")),
            ),
            Row(
                "string pattern wins over the other pattern rules",
                "string f = 1 [(validate.rules).string = {pattern: \"^a\", prefix: \"b\", " +
                    "contains: \"c\"}];",
                """#1 f string { match "^a" }""",
                listOf(dropped("string.prefix"), dropped("string.contains")),
            ),
            Row(
                "bytes lengths",
                "bytes f = 1 [(validate.rules).bytes = {min_len: 1, max_len: 5}];",
                "#1 f bytes { min 1, max 5 }",
            ),
            Row(
                "bytes len",
                "bytes f = 1 [(validate.rules).bytes.len = 4];",
                "#1 f bytes { min 4, max 4 }",
            ),
            Row(
                "bytes other rules are dropped",
                "bytes f = 1 [(validate.rules).bytes = {ip: true, pattern: \"a\", prefix: \"b\"}];",
                "#1 f bytes",
                listOf(dropped("bytes.ip"), dropped("bytes.pattern"), dropped("bytes.prefix")),
            ),
            Row(
                "repeated items count",
                "repeated string f = 1 [(validate.rules).repeated.min_items = 1];",
                "#1 f string[] { minItems 1 }",
            ),
            Row(
                "repeated min and max items",
                "repeated string f = 1 [(validate.rules).repeated = {min_items: 1, max_items: 3}];",
                "#1 f string[] { minItems 1, maxItems 3 }",
            ),
            Row(
                "repeated items rules reach the element",
                "repeated string f = 1 [(validate.rules).repeated = {min_items: 1, " +
                    "items: {string: {max_len: 5}}}];",
                "#1 f string[] { minItems 1, max 5 }",
            ),
            Row(
                "repeated items in the dotted form",
                "repeated int32 f = 1 [(validate.rules).repeated.items.int32.gte = 0];",
                "#1 f int32[] { min 0 }",
            ),
            Row(
                "repeated unique is dropped",
                "repeated string f = 1 [(validate.rules).repeated.unique = true];",
                "#1 f string[]",
                listOf(dropped("repeated.unique")),
            ),
            Row(
                "map pairs and entry rules",
                "map<string, int32> f = 1 [(validate.rules).map = {min_pairs: 1, max_pairs: 4, " +
                    "keys: {string: {min_len: 1}}, values: {int32: {gte: 0}}}];",
                "#1 f map<string { min 1 }, int32 { min 0 }> { minItems 1, maxItems 4 }",
            ),
            Row(
                "map no_sparse is dropped",
                "map<string, int32> f = 1 [(validate.rules).map.no_sparse = true];",
                "#1 f map<string, int32>",
                listOf(dropped("map.no_sparse")),
            ),
            Row(
                "enum defined_only is silent",
                "E f = 1 [(validate.rules).enum.defined_only = true];",
                "#1 f E",
            ),
            Row(
                "enum const in and not_in are dropped",
                "E f = 1 [(validate.rules).enum = {const: 1, in: [1], not_in: [0]}];",
                "#1 f E",
                listOf(dropped("enum.const"), dropped("enum.in"), dropped("enum.not_in")),
            ),
            Row(
                "bool const is dropped",
                "bool f = 1 [(validate.rules).bool.const = true];",
                "#1 f bool",
                listOf(dropped("bool.const")),
            ),
            Row(
                "message required on a wrapper drops the question mark",
                "google.protobuf.StringValue f = 1 [(validate.rules).message.required = true];",
                "#1 f string",
                listOf(
                    w(
                        "SCH2403",
                        "google.protobuf.StringValue imported as string?; the regenerated " +
                            "field is optional, not a wrapper",
                    )
                ),
            ),
            Row(
                "message required on a message is silent",
                "Line f = 1 [(validate.rules).message.required = true];",
                "#1 f Line",
            ),
            Row(
                "message skip is silent",
                "Line f = 1 [(validate.rules).message.skip = true];",
                "#1 f Line",
            ),
            Row(
                "any required is silent",
                "google.protobuf.Any f = 1 [(validate.rules).any.required = true];",
                "#1 f bytes",
                listOf(w("SCH2404", "google.protobuf.Any imported as bytes")),
            ),
            Row(
                "any in is dropped",
                "google.protobuf.Any f = 1 [(validate.rules).any = {in: [\"a\"]}];",
                "#1 f bytes",
                listOf(w("SCH2404", "google.protobuf.Any imported as bytes"), dropped("any.in")),
            ),
            Row(
                "duration rules are dropped",
                "google.protobuf.Duration f = 1 [(validate.rules).duration = {gt: {seconds: 1}}];",
                "#1 f duration",
                listOf(dropped("duration.gt")),
            ),
            Row(
                "timestamp rules are dropped",
                "google.protobuf.Timestamp f = 1 [(validate.rules).timestamp.lt_now = true];",
                "#1 f instant",
                listOf(dropped("timestamp.lt_now")),
            ),
            Row(
                "ignore_empty drops every rule",
                "string f = 1 [(validate.rules).string = {min_len: 1, email: true, " +
                    "ignore_empty: true}];",
                "#1 f string",
                listOf(dropped("string.ignore_empty")),
            ),
            Row(
                "ignore_empty on a repeated field",
                "repeated string f = 1 [(validate.rules).repeated = {min_items: 1, " +
                    "ignore_empty: true}];",
                "#1 f string[]",
                listOf(dropped("repeated.ignore_empty")),
            ),
            Row(
                "the rules named in one aggregate",
                "string f = 1 [(validate.rules) = {string: {min_len: 1}}];",
                "#1 f string { min 1 }",
            ),
        )

    @TestFactory
    fun `validate rules`(): List<DynamicTest> =
        rows.map { row ->
            DynamicTest.dynamicTest(row.name) {
                val result = import(row.decl)
                assertEquals(row.field, fieldLine(result))
                assertEquals(row.diagnostics, messages(result))
            }
        }

    @Test
    fun `string in and prefix rules escape metacharacters`() {
        val meta = "\\^\$.|?*+()[]{}"
        val regex = "^" + meta.map { "\\$it" }.joinToString("")
        val prefix =
            import(
                "string f = 1 [(validate.rules).string.prefix = \"${meta.replace("\\", "\\\\")}\"];"
            )
        assertEquals("#1 f string { match \"${regex.replace("\\", "\\\\")}\" }", fieldLine(prefix))
        val alternatives =
            import("string f = 1 [(validate.rules).string = {in: [\"a|b\", \"c.d\"]}];")
        assertEquals("""#1 f string { match "^(a\\|b|c\\.d)$" }""", fieldLine(alternatives))
    }

    @Test
    fun `a schemata note still wins over a validate rule`() {
        val result =
            import(
                "string f = 1 [(validate.rules).string.max_len = 5];  // schemata: string { max 9 }\n"
            )
        assertEquals("#1 f string { max 9 }", fieldLine(result))
        assertEquals(emptyList(), messages(result))
    }

    @Test
    fun `a rule dropped on several fields is reported once with the count`() {
        val result =
            import(
                "string f = 1 [(validate.rules).string.email = true];\n" +
                    "string g = 2 [(validate.rules).string.email = true];\n" +
                    "string h = 3 [(validate.rules).string.hostname = true];\n"
            )
        assertEquals(
            listOf(dropped("string.email", 2), dropped("string.hostname")),
            messages(result),
        )
    }

    @Test
    fun `a oneof level required option is silent`() {
        val result =
            import("oneof k { option (validate.required) = true; string a = 1; string b = 2; }\n")
        assertEquals(emptyList(), messages(result).filter { "validate" in it })
    }
}
