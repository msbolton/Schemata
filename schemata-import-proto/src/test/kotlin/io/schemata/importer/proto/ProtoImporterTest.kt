package io.schemata.importer.proto

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoImporterTest {
    @Test
    fun `a root and an import resolve whichever separator the path uses`() {
        val input = ImportInput("C:\\protos\\a\\orders.proto", "", relative = "a/orders.proto")
        assertEquals("C:/protos", ProtoImporter.root(input))
        assertEquals("C:/protos", ProtoImporter.root(input.copy(relative = "a\\orders.proto")))
        assertEquals(
            "C:/protos/a/people.proto",
            ProtoImporter.resolvePath("C:\\protos\\a\\orders.proto", "people.proto"),
        )
        assertEquals(
            "C:/protos/b/people.proto",
            ProtoImporter.resolvePath("C:\\protos\\a\\orders.proto", "../b/people.proto"),
        )
    }

    @Test
    fun `backslash paths under one root merge a shared package and find imports under the root`() {
        val located = mutableListOf<String>()
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "C:\\protos\\a\\orders.proto",
                        """
                        syntax = "proto3";
                        package a;
                        import "b/people.proto";
                        message Order { .b.People who = 1; }
                        """
                            .trimIndent(),
                        relative = "a/orders.proto",
                    ),
                    ImportInput(
                        "C:\\protos\\a\\lines.proto",
                        "syntax = \"proto3\";\npackage a;\nmessage Line {}\n",
                        relative = "a/lines.proto",
                    ),
                ),
                null,
            ) { path ->
                located += path
                if (path == "C:/protos/b/people.proto")
                    ImportInput(path, "syntax = \"proto3\";\npackage b;\nmessage People {}\n")
                else null
            }
        assertEquals(emptyList(), r.diagnostics.map { "${it.code.id} ${it.message}" })
        assertEquals(listOf("a.schemata", "b/people.schemata"), r.files.map { it.path })
        assertEquals(
            "schema a\n" +
                "\n" +
                "import b.people\n" +
                "\n" +
                "model Order { #1 who b.people.People }\n" +
                "\n" +
                "model Line {}\n",
            r.files.first().content,
        )
        assertEquals(listOf("C:/protos/a/b/people.proto", "C:/protos/b/people.proto"), located)
    }

    private fun import(vararg files: Pair<String, String>) =
        ProtoImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) },
            null,
        )

    private fun ImportResult.text(path: String) =
        files.singleOrNull { it.path == path }?.content
            ?: error("no $path among ${files.map { it.path }}: ${messages()}")

    private fun ImportResult.messages() = diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `a service declared in two files of one namespace is claimed once`() {
        val r =
            import(
                "x.proto" to
                    """
                    syntax = "proto3";
                    package a;
                    message M {}
                    service S { rpc First(M) returns (M); }
                    """,
                "y.proto" to
                    """
                    syntax = "proto3";
                    package a;
                    service S { rpc Second(M) returns (M); }
                    """,
            )
        assertEquals(
            listOf(
                "SCH2401 y.proto: service 'S' and x.proto's service 'S' both lower to service 'S'"
            ),
            r.messages(),
        )
    }

    @Test
    fun `a payload from another namespace names the import it comes from`() {
        val r =
            import(
                "a.proto" to
                    """
                    syntax = "proto3";
                    package a;
                    import "b.proto";
                    service S { rpc Get(b.People) returns (b.People); }
                    """,
                "b.proto" to
                    """
                    syntax = "proto3";
                    package b;
                    message People {}
                    """,
            )
        assertEquals(
            "schema a\n\nimport b\n\nservice S {\n  #1 get(b.People): b.People\n}\n",
            r.text("a.schemata"),
        )
        assertEquals(emptyList(), r.messages())
    }

    @Test
    fun `an own-line schemata comment before the closing brace of a service is reported`() {
        val r =
            import(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M {}
                    service S {
                      rpc A(M) returns (M);
                      // schemata: get "/a"
                    }
                    """
            )
        assertEquals(
            listOf("SCH2403 service 'S': note 'get \"/a\"' stands after the last rpc; ignored"),
            r.messages(),
        )
        assertEquals(6, r.diagnostics.single().span.startLine)
    }

    @Test
    fun `the proto package reaches the output whether it names the namespace or not`() {
        val named =
            ProtoImporter.import(
                listOf(ImportInput("orders.proto", "syntax = \"proto3\";\npackage corp.orders;\n")),
                null,
            )
        assertEquals("schema corp.orders\n", named.files.single().content)
        val under = import("shop/orders.proto" to "syntax = \"proto3\";\npackage corp.orders;\n")
        assertEquals(
            "schema shop.orders @proto(package: \"corp.orders\")\n",
            under.files.single().content,
        )
    }

    @Test
    fun `a default-only note that does not fit the field type is reported and dropped`() {
        val r =
            import(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message M {
                      int32 n = 1;  // schemata: default = "x"
                      int32 k = 2;  // schemata: default = 5
                      string s = 3;  // schemata: default = 5
                      bool b = 4;  // schemata: default = true
                      double f = 5;  // schemata: default = 2
                      M m = 6;  // schemata: default = 1
                    }
                    """
            )
        val out = r.text("t.schemata")
        assertEquals(
            "schema t\n\nmodel M { #1 n int32  #2 k int32 = 5  #3 s string  #4 b bool = true  " +
                "#5 f float64 = 2  #6 m M }\n",
            out,
        )
        assertEquals(
            listOf(
                "SCH2403 field 'M.n': note 'default = \"x\"' does not fit int32; ignored",
                "SCH2403 field 'M.s': note 'default = 5' does not fit string; ignored",
                "SCH2403 field 'M.m': note 'default = 1' does not fit M; ignored",
            ),
            r.messages(),
        )
    }

    @Test
    fun `a nullable note on a union member keeps the message a model`() {
        val r =
            import(
                "t.proto" to
                    """
                    syntax = "proto3";
                    package t;
                    message U {
                      oneof kind {
                        string text = 1;  // schemata: string?
                        int64 count = 2;
                      }
                    }
                    """
            )
        val out = r.text("t.schemata")
        assertTrue(out.startsWith("schema t\n\nmodel U {"), out)
        assertFalse("union" in out, out)
    }

    @Test
    fun `a file found beside a file under a root takes its namespace from its place under the root`() {
        val r =
            ProtoImporter.import(
                listOf(
                    ImportInput(
                        "/r/a/orders.proto",
                        "syntax = \"proto3\";\npackage x;\nimport \"people.proto\";\nmessage O { y.P p = 1; }\n",
                        relative = "a/orders.proto",
                    )
                ),
                null,
            ) { path ->
                if (path == "/r/a/people.proto")
                    ImportInput(path, "syntax = \"proto3\";\npackage y;\nmessage P {}\n")
                else null
            }
        assertEquals(
            listOf("a/orders.schemata", "a/people.schemata"),
            r.files.map { it.path }.sorted(),
        )
    }
}
