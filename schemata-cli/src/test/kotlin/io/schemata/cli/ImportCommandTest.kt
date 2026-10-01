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

    private val withAny =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s">
          <xs:complexType name="ThingType">
            <xs:sequence>
              <xs:element name="name" type="xs:string"/>
              <xs:any processContents="lax" minOccurs="0"/>
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
        write("s.xsd", withAny)
        val out = File(dir, "out")
        val r = ImportCommand().test("--from xsd --out ${out.path} ${dir.path}")
        assertEquals(2, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("warning[SCH2405]"), r.stderr)
        assertTrue(out.resolve("import/s.schemata").isFile)
    }

    @Test
    fun `--strict promotes the warning to an error and writes nothing`() {
        write("s.xsd", withAny)
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
        assertTrue(written.readText().contains("namespace tracks"), written.readText())
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
}
