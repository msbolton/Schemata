package io.schemata.config

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Schema
import io.schemata.lang.Parser
import io.schemata.target.jsonschema.JsonSchemaAnnotations
import io.schemata.target.openapi.OpenApiAnnotations
import io.schemata.target.proto.ProtoAnnotations
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlAnnotations
import io.schemata.target.xsd.XsdAnnotations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.io.TempDir

private const val ORDERS = "schema shop.orders\n\nmodel Order { #1 id uuid { id } }\n"

private const val ALPHA = "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 b B? }\n"

private const val BETA = "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 a A? }\n"

class AnnotationDefaultsTest {
    @TempDir lateinit var root: Path

    private val registry =
        AnnotationRegistry(
            CoreAnnotations.specs +
                ProtoAnnotations.specs +
                SqlAnnotations.specs +
                XsdAnnotations.specs +
                JsonSchemaAnnotations.specs +
                OpenApiAnnotations.specs
        )

    private val targets = listOf("proto", "sql", "xsd", "jsonschema", "openapi")

    /** Analyses [sources], each in a file named for its namespace, expecting no diagnostics. */
    private fun analysed(vararg sources: String): Schema {
        val files =
            sources.map { text ->
                val ns = Regex("""schema\s+([\w.]+)""").find(text)!!.groupValues[1]
                Parser.parse(text, "$ns.schemata").file!!
            }
        val analysis = Analyzer.analyze(files, AnalysisOptions(annotations = registry))
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!
    }

    private fun config(text: String): ProjectConfig {
        val file = root.resolve(ConfigLoader.FILE_NAME)
        Files.writeString(file, text)
        val loaded = ConfigLoader.load(file, registry, targets, ConfigLoader.FILE_NAME)
        assertEquals(emptyList(), loaded.diagnostics.map { it.message })
        return assertNotNull(loaded.config)
    }

    private fun applied(text: String, vararg sources: String): Schema =
        AnnotationDefaults.apply(analysed(*sources), config(text), registry)

    private fun Schema.value(namespace: String, target: String, key: String): AnnotationValue? =
        namespaces.single { it.name == namespace }.annotations[target][key]

    @Test
    fun `a table default becomes a schema annotation`() {
        val schema = applied("[proto]\njava_package = \"com.example.{schema}\"\n", ORDERS)
        assertEquals(
            AnnotationValue.Str("com.example.shop.orders"),
            schema.value("shop.orders", "proto", "java_package"),
        )
    }

    @Test
    fun `a declared annotation wins over the file`() {
        val schema =
            applied(
                "[proto]\njava_package = \"com.example.{schema}\"\n",
                ORDERS.replace(
                    "schema shop.orders",
                    "schema shop.orders @proto(java_package: \"org.declared\")",
                ),
            )
        assertEquals(
            AnnotationValue.Str("org.declared"),
            schema.value("shop.orders", "proto", "java_package"),
        )
    }

    @Test
    fun `a per-schema table wins over the target table`() {
        val schema =
            applied(
                """
                [proto]
                java_package = "com.example.{schema}"

                [proto.schemas."shop.orders"]
                java_package = "com.orders"
                """
                    .trimIndent(),
                ORDERS,
                ALPHA,
                BETA,
            )
        assertEquals(
            AnnotationValue.Str("com.orders"),
            schema.value("shop.orders", "proto", "java_package"),
        )
        assertEquals(
            AnnotationValue.Str("com.example.cyc.alpha"),
            schema.value("cyc.beta", "proto", "java_package"),
        )
    }

    @Test
    fun `a boolean key is synthesized as a boolean`() {
        val schema = applied("[proto]\njava_multiple_files = true\n", ORDERS)
        assertEquals(
            AnnotationValue.Bool(true),
            schema.value("shop.orders", "proto", "java_multiple_files"),
        )
    }

    @Test
    fun `other targets expand the schema name`() {
        val schema =
            applied(
                """
                [sql]
                schema = "{schema_}"

                [jsonschema]
                id = "https://example.com/{schema}.json"
                """
                    .trimIndent(),
                ORDERS,
            )
        assertEquals(
            AnnotationValue.Str("shop_orders"),
            schema.value("shop.orders", "sql", "schema"),
        )
        assertEquals(
            AnnotationValue.Str("https://example.com/shop.orders.json"),
            schema.value("shop.orders", "jsonschema", "id"),
        )
    }

    @Test
    fun `a proto default is evaluated once per cycle unit`() {
        val schema =
            applied(
                """
                [proto]
                java_package = "com.example.{schema}"
                csharp_namespace = "X.{Package}"
                """
                    .trimIndent(),
                ALPHA,
                BETA,
            )
        for (member in listOf("cyc.alpha", "cyc.beta")) {
            assertEquals(
                AnnotationValue.Str("com.example.cyc.alpha"),
                schema.value(member, "proto", "java_package"),
            )
            assertEquals(
                AnnotationValue.Str("X.Cyc"),
                schema.value(member, "proto", "csharp_namespace"),
            )
        }
    }

    @Test
    fun `a declared annotation on one cycle member still conflicts`() {
        val schema =
            applied(
                "[proto]\njava_package = \"com.example.{schema}\"\n",
                ALPHA,
                BETA.replace("schema cyc.beta", "schema cyc.beta @proto(java_package: \"other\")"),
            )
        assertEquals(
            AnnotationValue.Str("com.example.cyc.alpha"),
            schema.value("cyc.alpha", "proto", "java_package"),
        )
        assertEquals(
            AnnotationValue.Str("other"),
            schema.value("cyc.beta", "proto", "java_package"),
        )
        assertEquals(
            listOf(
                "SCH2007 schemas cyc.alpha and cyc.beta reference each other but declare " +
                    "java_package 'com.example.cyc.alpha' and 'other'"
            ),
            ProtoTarget.lower(schema)
                .diagnostics
                .filter { it.code.id == "SCH2007" }
                .map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `package is synthesized before the other proto keys`() {
        val schema =
            applied(
                """
                [proto]
                go_package = "example.com/{package}"
                java_package = "com.example.{schema}"
                package = "{schema_}.v1"
                """
                    .trimIndent(),
                ORDERS,
                ALPHA,
                BETA,
            )
        assertEquals(
            AnnotationValue.Str("shop_orders.v1"),
            schema.value("shop.orders", "proto", "package"),
        )
        assertEquals(
            AnnotationValue.Str("example.com/shop_orders.v1"),
            schema.value("shop.orders", "proto", "go_package"),
        )
        for (member in listOf("cyc.alpha", "cyc.beta")) {
            assertEquals(
                AnnotationValue.Str("example.com/cyc_alpha.v1"),
                schema.value(member, "proto", "go_package"),
            )
            assertEquals(
                AnnotationValue.Str("com.example.cyc.alpha"),
                schema.value(member, "proto", "java_package"),
            )
        }
    }

    @Test
    fun `a package template over a cycle gives every member one package`() {
        val schema = applied("[proto]\npackage = \"{schema_}.v1\"\n", ALPHA, BETA)
        for (member in listOf("cyc.alpha", "cyc.beta")) {
            assertEquals(
                AnnotationValue.Str("cyc_alpha.v1"),
                schema.value(member, "proto", "package"),
            )
        }
        assertEquals(
            emptyList(),
            ProtoTarget.lower(schema)
                .diagnostics
                .filter { it.code.id == "SCH2007" }
                .map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `plan lists the file line of each entry`() {
        // The analysed schema lists its namespaces by name, so shop.orders comes last.
        val plan =
            AnnotationDefaults.plan(
                analysed(ORDERS, ALPHA, BETA),
                config(
                    """
                    [proto]
                    java_package = "com.example.{schema}"
                    java_multiple_files = true

                    [sql]
                    schema = "{schema_}"

                    [proto.schemas."shop.orders"]
                    java_package = "com.orders"
                    """
                        .trimIndent()
                ),
                registry,
            )
        assertEquals(
            listOf(
                Synthesized("cyc.alpha", "proto", "java_package", str("com.example.cyc.alpha"), 2),
                Synthesized("cyc.alpha", "proto", "java_multiple_files", bool(true), 3),
                Synthesized("cyc.alpha", "sql", "schema", str("cyc_alpha"), 6),
                Synthesized("cyc.beta", "proto", "java_package", str("com.example.cyc.alpha"), 2),
                Synthesized("cyc.beta", "proto", "java_multiple_files", bool(true), 3),
                Synthesized("cyc.beta", "sql", "schema", str("cyc_beta"), 6),
                Synthesized("shop.orders", "proto", "java_package", str("com.orders"), 9),
                Synthesized("shop.orders", "proto", "java_multiple_files", bool(true), 3),
                Synthesized("shop.orders", "sql", "schema", str("shop_orders"), 6),
            ),
            plan,
        )
    }

    @Test
    fun `apply changes nothing without tables`() {
        val schema = analysed(ORDERS)
        val config = config("[compile]\nstrict = true\n")
        assertEquals(emptyList(), AnnotationDefaults.plan(schema, config, registry))
        assertSame(schema, AnnotationDefaults.apply(schema, config, registry))
        assertNull(schema.value("shop.orders", "proto", "java_package"))
    }

    private fun str(value: String) = AnnotationValue.Str(value)

    private fun bool(value: Boolean) = AnnotationValue.Bool(value)
}
