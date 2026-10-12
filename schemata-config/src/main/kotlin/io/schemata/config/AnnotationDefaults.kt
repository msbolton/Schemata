package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Schema
import io.schemata.target.ProtoPackages
import io.schemata.target.referencesByKey

/** One annotation the configuration adds to a schema, and the file line that set it. */
data class Synthesized(
    val namespace: String,
    val target: String,
    val key: String,
    val value: AnnotationValue,
    val line: Int,
)

/**
 * Turns a configuration's target tables into schema-line annotations (SCH-102). A schema line's own
 * annotation beats a per-schema table, which beats the target table: the file only fills in keys a
 * schema leaves unset.
 */
object AnnotationDefaults {
    private const val PROTO = "proto"
    private const val PACKAGE = "package"

    /**
     * What [config] adds to [schema]: every configured key a schema does not declare, in schema
     * order then target order then key order.
     */
    fun plan(
        schema: Schema,
        config: ProjectConfig,
        registry: AnnotationRegistry,
    ): List<Synthesized> {
        if (config.defaults.isEmpty()) return emptyList()
        // `{package}` names the package of the file a schema is written to, which a configured
        // `[proto] package` may itself decide. So every package is settled first, from each
        // schema's own name, and the files are worked out on the schema holding those packages.
        val packages =
            schema.namespaces
                .mapNotNull { ns ->
                    val setting = effective(config, PROTO, ns.name)[PACKAGE]
                    if (setting == null || declares(ns, PROTO, PACKAGE)) null
                    else synthesize(ns.name, PROTO, PACKAGE, setting, registry, ns.name, null)
                }
                .associateBy { it.namespace }
        val units by lazy {
            ProtoPackages.of(withEntries(schema, packages.values.toList()).referencesByKey())
        }
        return schema.namespaces.flatMap { ns ->
            config.defaults.keys.flatMap { target ->
                effective(config, target, ns.name).mapNotNull { (key, setting) ->
                    when {
                        declares(ns, target, key) -> null
                        target == PROTO && key == PACKAGE -> packages[ns.name]
                        target == PROTO -> {
                            // Every schema of a reference cycle shares one file, so each must get
                            // the same value: the one the file's first schema expands to.
                            val unit = units.unitOf(ns.name)
                            val first = unit.members.first().name
                            synthesize(
                                ns.name,
                                target,
                                key,
                                setting,
                                registry,
                                first,
                                unit.packageName,
                            )
                        }
                        else -> synthesize(ns.name, target, key, setting, registry, ns.name, null)
                    }
                }
            }
        }
    }

    /** [schema] with [plan] applied: each namespace's annotations gain the synthesized entries. */
    fun apply(schema: Schema, config: ProjectConfig, registry: AnnotationRegistry): Schema {
        val plan = plan(schema, config, registry)
        return if (plan.isEmpty()) schema else withEntries(schema, plan)
    }

    /** [target]'s keys for [namespace]: the target table, with the schema's own table on top. */
    private fun effective(
        config: ProjectConfig,
        target: String,
        namespace: String,
    ): Map<String, Setting<Any>> {
        val defaults = config.defaults[target] ?: return emptyMap()
        val overrides = defaults.schemas[namespace] ?: return defaults.keys
        // A key the schema's table repeats keeps its place in the target table's order.
        return LinkedHashMap(defaults.keys).apply { putAll(overrides) }
    }

    private fun declares(namespace: Namespace, target: String, key: String): Boolean =
        namespace.annotations[target][key] != null

    /**
     * The annotation [setting] makes, of the kind the key's schema-line annotation takes, with
     * placeholders filled from [schemaName] and [protoPackage].
     */
    private fun synthesize(
        namespace: String,
        target: String,
        key: String,
        setting: Setting<Any>,
        registry: AnnotationRegistry,
        schemaName: String,
        protoPackage: String?,
    ): Synthesized {
        val spec = registry.find(target, key).first { Element.NAMESPACE in it.elements }
        val raw = setting.value
        val value =
            when (spec.valueKind) {
                ValueKind.STRING -> AnnotationValue.Str(text(spec, raw, schemaName, protoPackage))
                ValueKind.NAME -> AnnotationValue.Name(text(spec, raw, schemaName, protoPackage))
                ValueKind.BOOL -> AnnotationValue.Bool(raw as Boolean)
                ValueKind.INT -> AnnotationValue.Num(raw as Long)
                ValueKind.FLAG,
                ValueKind.NAME_TUPLE ->
                    error("$target.$key has no single value to default to; the loader rejects it")
            }
        return Synthesized(namespace, target, key, value, setting.line)
    }

    /**
     * A key restricted to fixed choices holds one of them as written; the loader checked it against
     * the choices, not for placeholders. Free text has its placeholders expanded.
     */
    private fun text(
        spec: AnnotationSpec,
        raw: Any,
        schemaName: String,
        protoPackage: String?,
    ): String =
        if (spec.choices != null) raw as String
        else Placeholders.expand(raw as String, schemaName, protoPackage)

    private fun withEntries(schema: Schema, entries: List<Synthesized>): Schema {
        if (entries.isEmpty()) return schema
        val byNamespace = entries.groupBy { it.namespace }
        return schema.copy(
            namespaces =
                schema.namespaces.map { ns ->
                    val added = byNamespace[ns.name] ?: return@map ns
                    val merged = LinkedHashMap(ns.annotations.entries)
                    for (entry in added) {
                        merged[entry.target] =
                            merged[entry.target].orEmpty() + (entry.key to entry.value)
                    }
                    ns.copy(annotations = Annotations(merged))
                }
        )
    }
}
