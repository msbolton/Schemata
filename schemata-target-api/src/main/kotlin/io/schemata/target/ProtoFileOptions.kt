package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Namespace

/**
 * The language file options of `.proto` that a schema can set with `@proto(<key>: …)`: the options
 * that decide where `protoc` puts generated code in one language. [keys] is their fixed order, and
 * the order they are written in, so the same schema always yields the same file.
 */
object ProtoFileOptions {
    /**
     * What a key's value must look like. [DOTTED] is a dotted package, [BACKSLASHED] backslash-
     * separated identifiers (a PHP namespace), [DOUBLE_COLON] `::`-separated identifiers (a Ruby
     * module path), [IDENTIFIER] one identifier, [NON_EMPTY] any text without control characters (a
     * Go import path), and [BOOL] a true or false literal. The `_OR_EMPTY` shapes also take the
     * empty string, which protoc reads as the global namespace or no prefix.
     */
    enum class Shape {
        DOTTED,
        BACKSLASHED,
        DOUBLE_COLON,
        IDENTIFIER,
        DOTTED_OR_EMPTY,
        BACKSLASHED_OR_EMPTY,
        IDENTIFIER_OR_EMPTY,
        NON_EMPTY,
        BOOL,
    }

    /** A key, the language whose generated code it moves, and the shape of its value. */
    data class Key(val name: String, val language: String, val shape: Shape) {
        val bool: Boolean
            get() = shape == Shape.BOOL
    }

    val keys: List<Key> =
        listOf(
            Key("java_package", "Java", Shape.DOTTED),
            Key("java_outer_classname", "Java", Shape.IDENTIFIER),
            Key("java_multiple_files", "Java", Shape.BOOL),
            Key("csharp_namespace", "C#", Shape.DOTTED_OR_EMPTY),
            Key("go_package", "Go", Shape.NON_EMPTY),
            Key("objc_class_prefix", "Objective-C", Shape.IDENTIFIER_OR_EMPTY),
            Key("php_namespace", "PHP", Shape.BACKSLASHED_OR_EMPTY),
            Key("php_class_prefix", "PHP", Shape.IDENTIFIER_OR_EMPTY),
            Key("php_metadata_namespace", "PHP", Shape.BACKSLASHED_OR_EMPTY),
            Key("ruby_package", "Ruby", Shape.DOUBLE_COLON),
            Key("swift_prefix", "Swift", Shape.IDENTIFIER_OR_EMPTY),
        )

    private val byName: Map<String, Key> = keys.associateBy { it.name }

    fun key(name: String): Key? = byName[name]

    /** The options [namespace] declares, in key order. */
    fun declared(namespace: Namespace): Map<String, AnnotationValue> {
        val proto = namespace.annotations["proto"]
        return keys.mapNotNull { key -> proto[key.name]?.let { key.name to it } }.toMap()
    }

    /**
     * Two members of one unit that declare different values for [key]: [second] is the first member
     * whose value differs from [first], the first member to declare it.
     */
    data class Conflict(
        val key: String,
        val first: Namespace,
        val firstValue: AnnotationValue,
        val second: Namespace,
        val secondValue: AnnotationValue,
    )

    /** A unit's option [values] in key order, and the [conflicts] among its members. */
    data class Resolved(val values: Map<String, AnnotationValue>, val conflicts: List<Conflict>)

    fun of(unit: ProtoUnit): Resolved = of(unit.members)

    /**
     * Members of one unit share one file, so each key has one value: the first declaring member's.
     * The first later member declaring a different value is that key's conflict; members declaring
     * nothing never conflict.
     */
    fun of(members: List<Namespace>): Resolved {
        val declared = members.map { it to declared(it) }
        val values = LinkedHashMap<String, AnnotationValue>()
        val conflicts = mutableListOf<Conflict>()
        for (key in keys) {
            val declaring =
                declared.mapNotNull { (ns, options) -> options[key.name]?.let { ns to it } }
            val (first, firstValue) = declaring.firstOrNull() ?: continue
            values[key.name] = firstValue
            declaring
                .firstOrNull { (_, value) -> value != firstValue }
                ?.let { (second, secondValue) ->
                    conflicts += Conflict(key.name, first, firstValue, second, secondValue)
                }
        }
        return Resolved(values, conflicts)
    }
}
