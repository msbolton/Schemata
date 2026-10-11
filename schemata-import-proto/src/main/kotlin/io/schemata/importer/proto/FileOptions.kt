package io.schemata.importer.proto

import io.schemata.importer.UnitAnnotation
import io.schemata.lang.SchemataText
import io.schemata.target.OverrideNames
import io.schemata.target.ProtoFileOptions

/**
 * Reads the language file options of the files that lower to one namespace back as the
 * `@proto(<key>: …)` attributes of its schema, in the fixed key order the Protobuf target writes
 * them in, so a compiled file imports to the schema it came from. Other file options, such as
 * `optimize_for` or a custom option, have no `@proto` key and are passed over without a word.
 */
internal object FileOptions {
    /**
     * The attributes [files] declare, one per key. The files become one schema and that schema one
     * `.proto` file, which can hold only one value per option: when the files disagree, keeping any
     * one value would silently move the generated code of the others, so the key is dropped and
     * [report] names each file whose value differs from the first declaring file's, with the
     * message and its help. A file that declares a key twice is read by its first declaration.
     */
    fun annotations(
        files: List<ProtoFile>,
        report: (ProtoFile, String, String) -> Unit,
    ): List<UnitAnnotation> =
        ProtoFileOptions.keys.mapNotNull { key ->
            val declared =
                files.mapNotNull { file ->
                    val option = file.options.firstOrNull { it.name == key.name }
                    when {
                        option == null -> null
                        key.bool && option.value != "true" && option.value != "false" -> {
                            report(
                                file,
                                "${file.path}: option ${key.name} '${OverrideNames.escaped(option.value)}' is not a " +
                                    "boolean; dropped",
                                "write the option as `true` or `false`",
                            )
                            null
                        }
                        else -> file to option.value
                    }
                }
            val (first, value) = declared.firstOrNull() ?: return@mapNotNull null
            val differing = declared.filter { (_, other) -> other != value }
            differing.forEach { (file, other) ->
                report(
                    file,
                    "${file.path}: option ${key.name} '${OverrideNames.escaped(other)}' differs from " +
                        "'${OverrideNames.escaped(value)}' in " +
                        "${first.path}; dropped",
                    "set `@proto(${key.name}: …)` on the imported schema to choose the value",
                )
            }
            when {
                differing.isNotEmpty() -> null
                key.bool -> UnitAnnotation("proto", key.name, value)
                else -> UnitAnnotation("proto", key.name, SchemataText.string(value))
            }
        }
}
