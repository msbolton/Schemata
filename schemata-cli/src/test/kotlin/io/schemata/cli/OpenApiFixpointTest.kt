package io.schemata.cli

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.openapi.OpenApiImporter
import io.schemata.lang.Severity
import io.schemata.target.openapi.OpenApiTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * A hand-written OpenAPI document reaches a fixpoint: importing it, compiling the import to
 * openapi, and importing that document again gives the same schemas. The compiled document holds
 * only the declarations an operation reaches, as components in the order the operations first use
 * them, so the second import may list its declarations in another order than the first: each file
 * compares its schema line and imports, and its services, exactly and in order, and its top-level
 * declarations, each with its doc, annotations, and nested declarations, as a sorted list. Runs
 * over the import corpus cases in [cases], each of which declares operations; a case without one
 * compiles to no document at all.
 */
class OpenApiFixpointTest {
    private val root = File("src/test/resources/import")
    private val documentExtensions = setOf("yaml", "yml", "json")

    /** The import corpus cases whose documents reach every component from an operation. */
    private val cases =
        listOf(
            "openapi-operations",
            "openapi-security",
            "openapi-security-none",
            "openapi-ecommerce",
            "openapi-handwritten",
        )

    @TestFactory
    fun `a hand written document reaches a fixpoint`(): List<DynamicTest> =
        cases.map { name -> DynamicTest.dynamicTest(name) { fixpoint(File(root, name)) } }

    @Test
    fun `every fixpoint case is an import corpus case with a document`() {
        cases.forEach { name ->
            assertTrue(documents(File(root, name)).isNotEmpty(), "$name holds an OpenAPI document")
        }
        assertTrue(File(root, "openapi-ecommerce/ecommerce.yaml").isFile)
    }

    @Test
    fun `the comparison sets declaration order aside but not service order`() {
        val a =
            "schema s\n\nimport t\n\nmodel B {}\n\n/// A\nmodel A {\n  x int32\n\n  model N {}\n}\n\n" +
                "service S {\n  #1 f(A): B\n}\n\nservice T {\n  #1 g(A): B\n}\n"
        val b =
            "schema s\n\nimport t\n\n/// A\nmodel A {\n  x int32\n\n  model N {}\n}\n\nmodel B {}\n\n" +
                "service S {\n  #1 f(A): B\n}\n\nservice T {\n  #1 g(A): B\n}\n"
        assertEquals(layout(a), layout(b))
        assertEquals("schema s\n\nimport t", layout(a).head)
        assertEquals("model B {}", layout(a).declarations.substringAfterLast("\n\n"))
        assertNotEquals(layout(a), layout(a.replace("x int32", "x int64")))
        val swapped =
            "schema s\n\nimport t\n\nmodel B {}\n\n/// A\nmodel A {\n  x int32\n\n  model N {}\n}\n\n" +
                "service T {\n  #1 g(A): B\n}\n\nservice S {\n  #1 f(A): B\n}\n"
        assertNotEquals(layout(a).services, layout(swapped).services)
    }

    private fun layout(text: String): SchemataLayout = SchemataLayout.of(text)

    /**
     * The OpenAPI documents of [case], `expected/` aside, named by their path in the case. A file
     * under `include/` is one a document refers to, read through the lookup, never an input.
     */
    private fun documents(case: File): List<ImportInput> =
        case
            .walkTopDown()
            .filter {
                it.isFile &&
                    it.extension in documentExtensions &&
                    !it.relativeTo(case).path.startsWith("expected") &&
                    !it.relativeTo(case).path.startsWith("include/")
            }
            .sortedBy { it.relativeTo(case).path }
            .map {
                val path = it.relativeTo(case).path.replace(File.separatorChar, '/')
                ImportInput(path, it.readText(), path)
            }
            .toList()

    private fun fixpoint(case: File) {
        val locate = { path: String ->
            File(case, path).takeIf { it.isFile }?.let { ImportInput(path, it.readText()) }
        }
        val first = OpenApiImporter.import(documents(case), null, locate)
        assertNoErrors(first, "${case.name}: importing the document")
        val compiled =
            Pipeline.compile(
                first.files.map { SourceInput(it.path, it.content) },
                listOf(OpenApiTarget),
            )
        assertFalse(
            compiled.hasErrors,
            "${case.name}: compiling the import to openapi: " +
                compiled.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val written =
            compiled.files.map { ImportInput(it.file.path, it.file.content, it.file.path) }
        val second = OpenApiImporter.import(written)
        assertNoErrors(second, "${case.name}: importing the compiled documents")
        val before = first.files.associate { it.path to layout(it.content) }
        val after = second.files.associate { it.path to layout(it.content) }
        assertEquals(before.keys, after.keys, "${case.name}: the schemas each import writes")
        before.forEach { (path, layout) ->
            val again = after.getValue(path)
            assertEquals(layout.head, again.head, "$path in ${case.name}: schema line and imports")
            assertEquals(
                layout.declarations,
                again.declarations,
                "$path in ${case.name}: declarations, sorted; a declaration no operation reaches " +
                    "is not written to the document, so the second import cannot have it",
            )
            assertEquals(layout.services, again.services, "$path in ${case.name}: services")
        }
    }

    private fun assertNoErrors(result: ImportResult, what: String) =
        assertFalse(
            result.diagnostics.any { it.severity == Severity.ERROR },
            "$what: " +
                result.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
}
