package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ImportCommandTest {
    @TempDir lateinit var dir: File

    private fun write(name: String, text: String) = File(dir, name).apply { writeText(text) }

    private val clean =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s">
          <xs:complexType name="ThingType">
            <xs:sequence>
              <xs:element name="name" type="xs:string"/>
            </xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    private val withDropped =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s">
          <xs:notation name="png" public="image/png"/>
          <xs:complexType name="ThingType">
            <xs:sequence>
              <xs:element name="name" type="xs:string"/>
            </xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    private val unresolvedImport =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s">
          <xs:import namespace="urn:schemata:missing" schemaLocation="missing.xsd"/>
        </xs:schema>
        """
            .trimIndent()

    private val tracksNamespace =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
            targetNamespace="http://example.com/tracks">
          <xs:complexType name="ThingType">
            <xs:sequence>
              <xs:element name="name" type="xs:string"/>
            </xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `writes a schemata file per namespace and exits 0`() {
        write("s.xsd", clean)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --out ${out.path} ${dir.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(out.resolve("import/s.schemata").isFile)
        assertTrue(r.stderr.contains("wrote 1 file to ${out.path}/import"), r.stderr)
    }

    @Test
    fun `a lossy warning exits 2 and still writes`() {
        write("s.xsd", withDropped)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --out ${out.path} ${dir.path}")
        assertEquals(2, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("warning[SCH2405]"), r.stderr)
        assertTrue(out.resolve("import/s.schemata").isFile)
    }

    @Test
    fun `--strict promotes the warning to an error and writes nothing`() {
        write("s.xsd", withDropped)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --strict --out ${out.path} ${dir.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("[promoted]"), r.stderr)
        assertFalse(out.exists(), r.stderr)
    }

    @Test
    fun `an unresolved import exits 1`() {
        write("s.xsd", unresolvedImport)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --out ${out.path} ${dir.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2401]"), r.stderr)
    }

    @Test
    fun `--namespace renames a single input file and drops the derived-name note`() {
        write("s.xsd", tracksNamespace)
        val out = File(dir, "out")
        val withoutFlag = ImportCommand().test("--from xsd --out ${out.path} ${dir.path}")
        assertEquals(2, withoutFlag.statusCode, withoutFlag.stderr)
        assertTrue(withoutFlag.stderr.contains("warning[SCH2402]"), withoutFlag.stderr)

        val r = ImportCommand().test("--from xsd --namespace tracks --out ${out.path} ${dir.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertFalse(r.stderr.contains("SCH2402"), r.stderr)
        val written = out.resolve("import/tracks.schemata")
        assertTrue(written.isFile, r.stderr)
        assertTrue(written.readText().contains("schema tracks"), written.readText())
    }

    @Test
    fun `--namespace with two files is a usage error`() {
        write("a.xsd", clean)
        write("b.xsd", clean.replace("ThingType", "OtherType"))
        val r = ImportCommand().test("--from xsd --namespace tracks ${dir.path}")
        assertEquals(1, r.statusCode)
        assertTrue(r.stderr.contains("--namespace applies to a single input file"), r.stderr)
    }

    @Test
    fun `--format json prints the written path`() {
        write("s.xsd", clean)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --format json --out ${out.path} ${dir.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("\"path\":\"${out.path}/import/s.schemata\""), r.stdout)
    }

    @Test
    fun `--namespace must be dotted lower snake segments`() {
        write("s.xsd", tracksNamespace)
        listOf("Tracks", "tracks-1", "a..b", "1tracks", "shop.import").forEach { bad ->
            val out = File(dir, "out")
            val r =
                ImportCommand().test("--from xsd --namespace $bad --out ${out.path} ${dir.path}")
            assertEquals(1, r.statusCode, bad)
            assertTrue(
                r.stderr.contains("--namespace must be dotted lower-snake segments"),
                "$bad: ${r.stderr}",
            )
            assertFalse(out.exists(), bad)
        }
        val okOut = File(dir, "ok")
        val ok =
            ImportCommand()
                .test("--from xsd --namespace shop.tracks_1 --out ${okOut.path} ${dir.path}")
        assertEquals(0, ok.statusCode, ok.stderr)
        assertTrue(okOut.resolve("import/shop/tracks_1.schemata").isFile, ok.stderr)
    }

    @Test
    fun `a proto import names each file's namespace by its path under the directory`() {
        File(dir, "src/shop").mkdirs()
        write(
            "src/shop/customers.proto",
            "syntax = \"proto3\";\npackage shop.customers;\nmessage Customer { string id = 1; }\n",
        )
        write(
            "src/shop/orders.proto",
            """
            syntax = "proto3";
            package shop.orders.v1;
            import "shop/customers.proto";
            message Order { .shop.customers.Customer customer = 1; }
            """
                .trimIndent(),
        )
        val out = File(dir, "out")
        val r = ImportCommand().test("--from proto --out ${out.path} ${dir.path}/src")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(out.resolve("import/shop/customers.schemata").isFile, r.stderr)
        val orders = out.resolve("import/shop/orders.schemata").readText()
        assertTrue(orders.contains("@proto(package: \"shop.orders.v1\")"), orders)
        assertTrue(orders.contains("customer shop.customers.Customer"), orders)
    }

    @Test
    fun `a proto file named on its own takes its package`() {
        val money =
            write("money.proto", "syntax = \"proto3\";\npackage google.type;\nmessage Money {}\n")
        val out = File(dir, "out")
        val r = ImportCommand().test("--from proto --out ${out.path} ${money.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(out.resolve("import/google/type.schemata").isFile, r.stderr)
    }

    @Test
    fun `a sql import names each schema's namespace by its file's path under the directory`() {
        File(dir, "src/shop").mkdirs()
        write(
            "src/shop/customers.sql",
            "CREATE SCHEMA customers;\nCREATE TABLE customers.customer (id uuid PRIMARY KEY);\n",
        )
        write(
            "src/shop/orders.sql",
            """
            CREATE SCHEMA shop;
            CREATE TABLE shop."order" (
              id uuid PRIMARY KEY,
              customer_id uuid NOT NULL REFERENCES customers.customer (id)
            );
            """
                .trimIndent(),
        )
        val out = File(dir, "out")
        val r = ImportCommand().test("--from sql --out ${out.path} ${dir.path}/src")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(out.resolve("import/shop/customers.schemata").isFile, r.stderr)
        val orders = out.resolve("import/shop/orders.schemata").readText()
        assertTrue(orders.contains("@sql(schema: \"shop\")"), orders)
        assertTrue(orders.contains("customer shop.customers.Customer"), orders)
    }

    @Test
    fun `--from sql on a directory with no sql files is a usage error`() {
        write("s.xsd", clean)
        val r = ImportCommand().test("--from sql --out ${File(dir, "out").path} ${dir.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no .sql files found under"), r.stderr)
    }

    @Test
    fun `--from proto on a directory with no proto files is a usage error`() {
        write("s.xsd", clean)
        val r = ImportCommand().test("--from proto --out ${File(dir, "out").path} ${dir.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no .proto files found under"), r.stderr)
    }

    private fun includeTree(referenced: Boolean) {
        File(dir, "src/shop").mkdirs()
        File(dir, "inc/google/rpc").mkdirs()
        File(dir, "inc/validate").mkdirs()
        val use = if (referenced) "google.rpc.Status status = 1;" else "string id = 1;"
        File(dir, "src/shop/orders.proto")
            .writeText(
                "syntax = \"proto3\";\npackage shop;\nimport \"google/rpc/status.proto\";\n" +
                    "message Order { $use }\n"
            )
        File(dir, "inc/google/rpc/status.proto")
            .writeText(
                "syntax = \"proto3\";\npackage google.rpc;\nmessage Status { int32 code = 1; }\n"
            )
    }

    @Test
    fun `--include adds a root that is searched after the inputs`() {
        includeTree(referenced = true)
        val out = File(dir, "out")
        val r =
            ImportCommand()
                .test("--from proto --include ${dir.path}/inc --out ${out.path} ${dir.path}/src")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(out.resolve("import/shop/orders.schemata").isFile, r.stderr)
        assertTrue(out.resolve("import/google/rpc/status.schemata").isFile, r.stderr)

        includeTree(referenced = false)
        val again = File(dir, "out2")
        val r2 =
            ImportCommand()
                .test("--from proto --include ${dir.path}/inc --out ${again.path} ${dir.path}/src")
        assertEquals(0, r2.statusCode, r2.stderr)
        assertTrue(again.resolve("import/shop/orders.schemata").isFile, r2.stderr)
        assertFalse(again.resolve("import/google/rpc/status.schemata").exists(), r2.stderr)
    }

    @Test
    fun `--include must be a directory`() {
        includeTree(referenced = true)
        val r =
            ImportCommand()
                .test(
                    "--from proto --include ${dir.path}/inc/google/rpc/status.proto " +
                        "--out ${File(dir, "out").path} ${dir.path}/src"
                )
        assertEquals(1, r.statusCode, r.stderr)
        assertFalse(File(dir, "out").exists())
    }

    @Test
    fun `--include is refused for xsd and sql`() {
        write("s.xsd", clean)
        File(dir, "inc").mkdirs()
        for (from in listOf("xsd", "sql")) {
            val r =
                ImportCommand()
                    .test(
                        "--from $from --include ${dir.path}/inc --out ${File(dir, "out").path} ${dir.path}"
                    )
            assertEquals(1, r.statusCode, r.stderr)
            assertTrue(r.stderr.contains("--include applies to --from proto"), r.stderr)
        }
    }
}
