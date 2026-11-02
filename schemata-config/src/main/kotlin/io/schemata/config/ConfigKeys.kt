package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
import io.schemata.target.ProtoLayout

/**
 * The value a key takes: the wording of SCH2903 and the example its help shows, or none when no
 * value would be accepted.
 */
internal sealed class Expect(val wording: String, val example: String?) {
    object Text : Expect("a string", "\"…\"")

    object Flag : Expect("true or false", "true")

    object Integer : Expect("an integer", "1")

    object Texts : Expect("an array of strings", "[\"…\"]")

    /**
     * A string out of [choices]; with [many], an array of such strings. With no choices only an
     * empty array, or nothing at all, is accepted, so there is no value to show.
     */
    class OneOf(val choices: List<String>, val many: Boolean = false) :
        Expect(
            when {
                choices.isNotEmpty() -> "one of " + choices.joinToString(", ") { "\"$it\"" }
                many -> "an empty array"
                else -> "no value"
            },
            choices.firstOrNull()?.let { if (many) "[\"$it\"]" else "\"$it\"" },
        )
}

/**
 * A key of a run table: the value it takes, its built-in default (null when it has none) and a
 * short line on what it sets, which `schemata init` writes beside it.
 */
class ConfigKey
internal constructor(
    val name: String,
    internal val expect: Expect,
    val default: Any?,
    val note: String,
)

/**
 * Every table and key `schemata.toml` accepts (SCH-105). The loader validates against it, `config`
 * shows its defaults and `init` writes them, so the three cannot disagree.
 */
object ConfigKeys {
    /** The run tables in the order `config` shows them and `init` writes them, keys in order. */
    fun runTables(targetNames: List<String>): Map<String, List<ConfigKey>> =
        linkedMapOf(
            "compile" to
                listOf(
                    ConfigKey(
                        "targets",
                        Expect.OneOf(targetNames, many = true),
                        targetNames,
                        "the targets compile and check write",
                    ),
                    ConfigKey(
                        "out",
                        Expect.Text,
                        "out",
                        "where compile writes, relative to this file",
                    ),
                    ConfigKey("strict", Expect.Flag, false, "report every warning as an error"),
                    ConfigKey(
                        "proto_layout",
                        Expect.OneOf(ProtoLayout.flags),
                        ProtoLayout.SCHEMA.flag,
                        "\"schema\" writes a Protobuf file per schema, \"type\" one per type",
                    ),
                ),
            "import" to
                listOf(
                    ConfigKey(
                        "from",
                        Expect.OneOf(listOf("xsd", "proto", "sql", "openapi")),
                        null,
                        "the format import reads: \"xsd\", \"proto\", \"sql\" or \"openapi\"",
                    ),
                    ConfigKey(
                        "namespace",
                        Expect.Text,
                        null,
                        "the schema name of a one-file import",
                    ),
                    ConfigKey(
                        "include",
                        Expect.Texts,
                        emptyList<String>(),
                        "directories searched for imported .proto files",
                    ),
                ),
            "migrate" to
                listOf(
                    ConfigKey(
                        "allow_destructive",
                        Expect.Flag,
                        false,
                        "let migrate drop tables and columns",
                    )
                ),
            "report" to
                listOf(
                    ConfigKey(
                        "format",
                        Expect.OneOf(listOf("human", "json")),
                        "human",
                        "\"human\" or \"json\"",
                    ),
                    ConfigKey(
                        "color",
                        Expect.OneOf(listOf("auto", "always", "never")),
                        "auto",
                        "\"auto\", \"always\" or \"never\"",
                    ),
                ),
            "sources" to
                listOf(
                    ConfigKey(
                        "paths",
                        Expect.Texts,
                        null,
                        "schema files and directories read when a command is given none",
                    ),
                    ConfigKey("exclude", Expect.Texts, null, "globs of files to leave out"),
                ),
        )

    /**
     * The keys [target] declares for a schema line, by the TOML value each takes. A key whose
     * annotation is a bare flag or a name tuple has no single literal to default to, so the file
     * cannot set it. `@openapi(scopes)` cannot be set either: its scopes belong to the oauth2 or
     * openid scheme the same line's `auth` requires, and `auth` is a name tuple the file cannot
     * default, so a default `scopes` would be refused on every schema line that does not itself
     * require such a scheme.
     */
    internal fun targetKeys(registry: AnnotationRegistry, target: String): Map<String, Expect> =
        registry
            .specs(target)
            .filter { Element.NAMESPACE in it.elements }
            .filterNot { target == "openapi" && it.key == "scopes" }
            .mapNotNull { spec ->
                val expect =
                    when (spec.valueKind) {
                        ValueKind.STRING,
                        ValueKind.NAME ->
                            spec.choices?.let { Expect.OneOf(it.toList()) } ?: Expect.Text
                        ValueKind.BOOL -> Expect.Flag
                        ValueKind.INT -> Expect.Integer
                        ValueKind.FLAG,
                        ValueKind.NAME_TUPLE -> null
                    }
                expect?.let { spec.key to it }
            }
            .toMap()
}
