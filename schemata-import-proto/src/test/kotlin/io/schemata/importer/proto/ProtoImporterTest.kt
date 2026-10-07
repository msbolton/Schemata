package io.schemata.importer.proto

import io.schemata.importer.ImportInput
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
