package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.jsonschema.JsonSchemaAnnotations
import io.schemata.target.openapi.OpenApiAnnotations
import io.schemata.target.proto.ProtoAnnotations
import io.schemata.target.sql.SqlAnnotations
import io.schemata.target.xsd.XsdAnnotations
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ConfigLoaderTest {
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

    private val tablesHelp =
        "tables are compile, import, migrate, report, sources, proto, sql, xsd, jsonschema, " +
            "openapi, and <target>.schemas.\"<schema>\""

    private val placeholdersHelp =
        "placeholders are {schema}, {Schema} and {schema_}; [proto] keys other than package " +
            "also take {package} and {Package}"

    private val globHelp =
        "globs use / as the separator and the syntax of java.nio.file.PathMatcher, " +
            "for example \"schemas/drafts/**\""

    private fun load(text: String): LoadedConfig {
        val file = root.resolve(ConfigLoader.FILE_NAME)
        Files.writeString(file, text)
        return ConfigLoader.load(file, registry, targets, ConfigLoader.FILE_NAME)
    }

    private fun loaded(text: String): ProjectConfig {
        val result = load(text)
        assertEquals(emptyList(), result.diagnostics.map { it.message })
        return assertNotNull(result.config)
    }

    private fun failure(text: String): Diagnostic {
        val result = load(text)
        assertNull(result.config)
        return result.diagnostics.single()
    }

    private fun assertDiagnostic(expected: String, help: String, actual: Diagnostic) {
        assertEquals(expected, "${actual.code.id} ${actual.message}")
        assertEquals(help, actual.help)
    }

    @Test
    fun `discover finds the nearest file upward`() {
        val project = Files.createDirectories(root.resolve("project"))
        val file = Files.writeString(project.resolve(ConfigLoader.FILE_NAME), "")
        val nested = Files.createDirectories(project.resolve("a/b"))
        val elsewhere = Files.createDirectories(root.resolve("elsewhere"))

        assertEquals(file, ConfigLoader.discover(nested))
        assertEquals(file, ConfigLoader.discover(project))
        assertNull(ConfigLoader.discover(elsewhere))
    }

    @Test
    fun `an empty file loads with every setting absent`() {
        val config = loaded("")

        assertEquals(root.resolve(ConfigLoader.FILE_NAME), config.path)
        assertEquals(root, config.directory)
        assertEquals("", config.text)
        assertEquals(CompileSettings(), config.compile)
        assertEquals(ImportSettings(), config.import)
        assertEquals(MigrateSettings(), config.migrate)
        assertEquals(ReportSettings(), config.report)
        assertNull(config.sources)
        assertEquals(emptyMap(), config.defaults)
        assertEquals(emptyList(), config.sourcePaths)
        assertFalse(config.excluded(root.resolve("a.schemata")))
    }

    @Test
    fun `run settings load with their lines`() {
        val config =
            loaded(
                """
                [compile]
                targets = ["proto", "sql"]
                out = "build/schemata"
                strict = true
                proto_layout = "type"

                [import]
                from = "xsd"
                namespace = "shop"
                include = ["common.xsd", "types.xsd"]

                [migrate]
                allow_destructive = true

                [report]
                format = "json"
                color = "never"
                """
                    .trimIndent()
            )

        assertEquals(
            CompileSettings(
                targets = Setting(listOf("proto", "sql"), 2),
                out = Setting("build/schemata", 3),
                strict = Setting(true, 4),
                protoLayout = Setting("type", 5),
            ),
            config.compile,
        )
        assertEquals(
            ImportSettings(
                from = Setting("xsd", 8),
                namespace = Setting("shop", 9),
                include = Setting(listOf("common.xsd", "types.xsd"), 10),
            ),
            config.import,
        )
        assertEquals(MigrateSettings(allowDestructive = Setting(true, 13)), config.migrate)
        assertEquals(
            ReportSettings(format = Setting("json", 16), color = Setting("never", 17)),
            config.report,
        )
    }

    @Test
    fun `sources paths resolve against the file and excludes match relative paths`() {
        Files.createDirectories(root.resolve("schemas/drafts"))
        val config =
            loaded(
                """
                [sources]
                paths = ["schemas"]
                exclude = ["schemas/drafts/**"]
                """
                    .trimIndent()
            )

        assertEquals(
            SourcesSettings(Setting(listOf("schemas"), 2), Setting(listOf("schemas/drafts/**"), 3)),
            config.sources,
        )
        assertEquals(listOf(root.resolve("schemas")), config.sourcePaths)
        assertTrue(config.excluded(root.resolve("schemas/drafts/x.schemata")))
        assertFalse(config.excluded(root.resolve("schemas/a.schemata")))
    }

    @Test
    fun `target tables load every schema-line key by kind`() {
        val config =
            loaded(
                """
                [proto]
                package = "{schema}.v1"
                java_package = "com.example.{package}"
                java_multiple_files = true

                [sql]
                schema = "{schema_}"

                [xsd]
                namespace = "urn:example:{schema}"
                element_form = "qualified"

                [jsonschema]
                id = "https://example.com/{schema}.json"

                [openapi]
                server = "https://api.example.com"
                version = "1.0.0"
                """
                    .trimIndent()
            )

        assertEquals(
            listOf("proto", "sql", "xsd", "jsonschema", "openapi"),
            config.defaults.keys.toList(),
        )
        val proto = assertNotNull(config.defaults["proto"])
        assertEquals(
            mapOf(
                "package" to Setting<Any>("{schema}.v1", 2),
                "java_package" to Setting<Any>("com.example.{package}", 3),
                "java_multiple_files" to Setting<Any>(true, 4),
            ),
            proto.keys,
        )
        assertEquals(true, proto.keys["java_multiple_files"]?.value)
        assertEquals(emptyMap(), proto.schemas)
        assertEquals(mapOf("schema" to Setting<Any>("{schema_}", 7)), config.defaults["sql"]?.keys)
        assertEquals(
            mapOf(
                "namespace" to Setting<Any>("urn:example:{schema}", 10),
                "element_form" to Setting<Any>("qualified", 11),
            ),
            config.defaults["xsd"]?.keys,
        )
        assertEquals(
            mapOf("id" to Setting<Any>("https://example.com/{schema}.json", 14)),
            config.defaults["jsonschema"]?.keys,
        )
        assertEquals(
            mapOf(
                "server" to Setting<Any>("https://api.example.com", 17),
                "version" to Setting<Any>("1.0.0", 18),
            ),
            config.defaults["openapi"]?.keys,
        )
    }

    @Test
    fun `the string security keys of openapi load and its flag tuple and scopes keys are unknown`() {
        val config =
            loaded(
                """
                [openapi]
                bearer = "JWT"
                api_key = "header X-API-Key"
                oauth2_token_url = "https://auth.example.com/token"
                oauth2_authorization_url = "https://auth.example.com/authorize"
                openid = "https://auth.example.com/.well-known/openid-configuration"
                """
                    .trimIndent()
            )
        assertEquals(
            mapOf(
                "bearer" to Setting<Any>("JWT", 2),
                "api_key" to Setting<Any>("header X-API-Key", 3),
                "oauth2_token_url" to Setting<Any>("https://auth.example.com/token", 4),
                "oauth2_authorization_url" to Setting<Any>("https://auth.example.com/authorize", 5),
                "openid" to
                    Setting<Any>("https://auth.example.com/.well-known/openid-configuration", 6),
            ),
            config.defaults["openapi"]?.keys,
        )
        val keys =
            "keys of [openapi] are api_key, bearer, oauth2_authorization_url, oauth2_token_url, " +
                "openid, server, version"
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'openapi.basic'",
            keys,
            failure("[openapi]\nbasic = true\n"),
        )
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'openapi.auth'",
            keys,
            failure("[openapi]\nauth = \"bearer\"\n"),
        )
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'openapi.scopes'",
            keys,
            failure("[openapi]\nscopes = \"orders:read\"\n"),
        )
    }

    @Test
    fun `per-schema tables load under schemas`() {
        val config =
            loaded(
                """
                [proto]
                java_package = "mil.uc2.{schema}"

                [proto.schemas."uc2_sos_all"]
                java_package = "mil.uc2.sos"

                [proto.schemas."shop.orders"]
                java_multiple_files = false
                """
                    .trimIndent()
            )

        val proto = assertNotNull(config.defaults["proto"])
        assertEquals(mapOf("java_package" to Setting<Any>("mil.uc2.{schema}", 2)), proto.keys)
        assertEquals(
            mapOf(
                "uc2_sos_all" to mapOf("java_package" to Setting<Any>("mil.uc2.sos", 5)),
                "shop.orders" to mapOf("java_multiple_files" to Setting<Any>(false, 8)),
            ),
            proto.schemas,
        )
    }

    @Test
    fun `a missing file is SCH2901`() {
        val result =
            ConfigLoader.load(root.resolve("absent.toml"), registry, targets, "absent.toml")

        assertNull(result.config)
        val d = result.diagnostics.single()
        assertDiagnostic(
            "SCH2901 absent.toml: cannot read the configuration file: no such file",
            "create the file, name another with --config, or pass --no-config",
            d,
        )
        assertEquals(Span("absent.toml", 1, 1, 1, 1), d.span)
    }

    @Test
    fun `a TOML syntax error is SCH2901 at its line`() {
        val d = failure("[compile]\nstrict = true\n[report\n")

        assertDiagnostic(
            "SCH2901 schemata.toml:3: unexpected end of line, expected ]",
            "fix the TOML syntax",
            d,
        )
        assertEquals(Span("schemata.toml", 3, 1, 3, 7), d.span)
    }

    @Test
    fun `an unknown table is SCH2902`() {
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown table 'compiler'",
            tablesHelp,
            failure("\n[compiler]\nstrict = true\n"),
        )
        assertDiagnostic(
            "SCH2902 schemata.toml:1: unknown table 'proto.schemas.\"shop\".options'",
            tablesHelp,
            failure("[proto.schemas.\"shop\".options]\njava_package = \"x\"\n"),
        )
    }

    @Test
    fun `an unknown key names the nearest known key`() {
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'proto.java_pakage'",
            "did you mean 'java_package'",
            failure("[proto]\njava_pakage = \"com.example\"\n"),
        )
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'proto.schemas.\"x\".java_pakage'",
            "did you mean 'java_package'",
            failure("[proto.schemas.\"x\"]\njava_pakage = \"com.example\"\n"),
        )
    }

    @Test
    fun `an unknown key far from every known key lists them`() {
        assertDiagnostic(
            "SCH2902 schemata.toml:2: unknown key 'report.verbosity_level'",
            "keys of [report] are color, format",
            failure("[report]\nverbosity_level = 3\n"),
        )
    }

    @Test
    fun `a wrong type is SCH2903`() {
        val d = failure("[compile]\nstrict = \"yes\"\n")

        assertDiagnostic(
            "SCH2903 schemata.toml:2: compile.strict expects true or false",
            "for example compile.strict = true",
            d,
        )
        assertEquals(Span("schemata.toml", 2, 1, 2, 14), d.span)
        assertDiagnostic(
            "SCH2903 schemata.toml:2: compile.targets expects an array of strings",
            "for example compile.targets = [\"…\"]",
            failure("[compile]\ntargets = \"proto\"\n"),
        )
        assertDiagnostic(
            "SCH2903 schemata.toml:2: proto.java_multiple_files expects true or false",
            "for example proto.java_multiple_files = true",
            failure("[proto]\njava_multiple_files = \"yes\"\n"),
        )
        assertDiagnostic(
            "SCH2903 schemata.toml:2: sql.schema expects a string",
            "for example sql.schema = \"…\"",
            failure("[sql]\nschema = 1\n"),
        )
    }

    @Test
    fun `a value outside its choices is SCH2903`() {
        assertDiagnostic(
            "SCH2903 schemata.toml:2: compile.proto_layout expects one of \"schema\", \"type\"",
            "for example compile.proto_layout = \"schema\"",
            failure("[compile]\nproto_layout = \"flat\"\n"),
        )
        assertDiagnostic(
            "SCH2903 schemata.toml:2: compile.targets expects one of \"proto\", \"sql\", " +
                "\"xsd\", \"jsonschema\", \"openapi\"",
            "for example compile.targets = [\"proto\"]",
            failure("[compile]\ntargets = [\"proto\", \"avro\"]\n"),
        )
        assertDiagnostic(
            "SCH2903 schemata.toml:2: xsd.element_form expects one of \"qualified\", \"unqualified\"",
            "for example xsd.element_form = \"qualified\"",
            failure("[xsd]\nelement_form = \"loose\"\n"),
        )
    }

    @Test
    fun `import from accepts openapi`() {
        assertEquals(Setting("openapi", 2), loaded("[import]\nfrom = \"openapi\"\n").import.from)
        assertDiagnostic(
            "SCH2903 schemata.toml:2: import.from expects one of \"xsd\", \"proto\", \"sql\", " +
                "\"openapi\"",
            "for example import.from = \"xsd\"",
            failure("[import]\nfrom = \"avro\"\n"),
        )
    }

    @Test
    fun `an unclosed placeholder is SCH2904`() {
        assertDiagnostic(
            "SCH2904 schemata.toml:2: proto.java_package holds the unknown placeholder '{schema'",
            placeholdersHelp,
            failure("[proto]\njava_package = \"com.{schema\"\n"),
        )
    }

    @Test
    fun `an unknown placeholder is SCH2904`() {
        assertDiagnostic(
            "SCH2904 schemata.toml:2: proto.java_package holds the unknown placeholder '{scheme}'",
            placeholdersHelp,
            failure("[proto]\njava_package = \"com.example.{scheme}\"\n"),
        )
    }

    @Test
    fun `a package placeholder outside proto is SCH2904`() {
        assertDiagnostic(
            "SCH2904 schemata.toml:2: sql.schema holds '{package}', which only [proto] keys " +
                "other than package accept",
            placeholdersHelp,
            failure("[sql]\nschema = \"{package}\"\n"),
        )
    }

    @Test
    fun `a package placeholder in proto package is SCH2904`() {
        assertDiagnostic(
            "SCH2904 schemata.toml:2: proto.package holds '{Package}', which only [proto] keys " +
                "other than package accept",
            placeholdersHelp,
            failure("[proto]\npackage = \"x.{Package}\"\n"),
        )
    }

    @Test
    fun `a missing sources path is SCH2905`() {
        Files.createDirectories(root.resolve("schemas"))

        assertDiagnostic(
            "SCH2905 schemata.toml:2: sources path 'missing' names nothing",
            "create it or remove it from [sources].paths",
            failure("[sources]\npaths = [\"schemas\", \"missing\"]\n"),
        )
    }

    @Test
    fun `an invalid exclude glob is SCH2903`() {
        Files.createDirectories(root.resolve("schemas"))

        assertDiagnostic(
            "SCH2903 schemata.toml:3: sources.exclude holds an invalid glob 'schemas/[drafts'",
            globHelp,
            failure(
                "[sources]\npaths = [\"schemas\"]\nexclude = [\"schemas/*.bak\", \"schemas/[drafts\"]\n"
            ),
        )
    }

    @Test
    fun `a backslash in an exclude glob is SCH2903`() {
        Files.createDirectories(root.resolve("schemas"))

        assertDiagnostic(
            "SCH2903 schemata.toml:3: sources.exclude holds an invalid glob 'schemas\\drafts\\**'",
            globHelp,
            failure("[sources]\npaths = [\"schemas\"]\nexclude = ['schemas\\drafts\\**']\n"),
        )
    }

    @Test
    fun `an invalid glob in a config built by hand excludes nothing`() {
        val config =
            ProjectConfig(
                root.resolve(ConfigLoader.FILE_NAME),
                "",
                CompileSettings(),
                ImportSettings(),
                MigrateSettings(),
                ReportSettings(),
                SourcesSettings(Setting(emptyList(), 1), Setting(listOf("[", "*.bak"), 1)),
                emptyMap(),
            )

        assertFalse(config.excluded(root.resolve("a.schemata")))
        assertTrue(config.excluded(root.resolve("a.bak")))
    }

    @Test
    fun `a file the directory cannot relativize is not excluded`() {
        val config =
            ProjectConfig(
                root.resolve(ConfigLoader.FILE_NAME),
                "",
                CompileSettings(),
                ImportSettings(),
                MigrateSettings(),
                ReportSettings(),
                SourcesSettings(Setting(emptyList(), 1), Setting(listOf("**"), 1)),
                emptyMap(),
            )
        // A path on another file system stands in for one on another Windows drive: neither has a
        // path relative to the directory.
        val zip = root.resolve("other.zip")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { other ->
            assertFalse(config.excluded(other.getPath("/a.schemata")))
        }
        assertTrue(config.excluded(root.resolve("a.schemata")))
    }

    @Test
    fun `the first parse writes nothing to stderr`() {
        // Other tests in this JVM have already loaded tomlj, so the first parse only happens in a
        // fresh one.
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    FirstParseMain::class.java.name,
                    root.toString(),
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        val stderr = process.errorStream.bufferedReader().readText()

        assertEquals(0, process.waitFor())
        assertEquals("", stderr)
    }

    @Test
    fun `a file with errors loads no config`() {
        val result =
            load(
                """
                [compile]
                strict = "yes"
                proto_layout = "type"

                [sql]
                schema = "{package}"

                [proto]
                java_pakage = "x"

                [colour]
                mode = "never"
                """
                    .trimIndent()
            )

        assertNull(result.config)
        assertEquals(
            listOf(
                "SCH2903 schemata.toml:2: compile.strict expects true or false",
                "SCH2904 schemata.toml:6: sql.schema holds '{package}', which only [proto] keys " +
                    "other than package accept",
                "SCH2902 schemata.toml:9: unknown key 'proto.java_pakage'",
                "SCH2902 schemata.toml:11: unknown table 'colour'",
            ),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }
}
