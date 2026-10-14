package io.schemata.importer.openapi

import io.schemata.importer.ImportNames

/** How component names map to schemas and declarations, and the target's id rule. */
object OpenApiNames {
    /** A component name's schema, or null for the document's own, and its declaration path. */
    data class Split(val namespace: String?, val path: List<String>)

    /** The target's rule for an operationId or a tag name. */
    val ID = Regex("[A-Za-z0-9_.-]+")

    private val upperCamel = Regex("[A-Z][A-Za-z0-9]*")

    fun isUpperCamel(s: String): Boolean = upperCamel.matches(s)

    /**
     * Splits [componentName] the way the target joins a declaration's schema and path:
     * `shop.orders.Order.Line` is schema `shop.orders`, model `Order`, nested `Line`.
     *
     * A name that starts with one of the [known] schemas and a dot splits there, the longest such
     * schema first, whatever follows: the target writes a declaration renamed with
     * `@jsonschema(name)` under its wire text, as in `shop.pet_info`. Otherwise leading lower_snake
     * segments name the schema when one or more UpperCamel segments follow them and nothing else
     * does. Any other name is one declaration of the document's own schema. A schema equal to
     * [own], the document's own, is given as null.
     */
    fun split(componentName: String, known: Set<String> = emptySet(), own: String? = null): Split {
        val prefix = known.filter { componentName.startsWith("$it.") }.maxByOrNull { it.length }
        val split =
            prefix?.let { ns ->
                val path = componentName.removePrefix("$ns.").split('.')
                Split(ns, path).takeIf { path.none(String::isEmpty) }
            } ?: strict(componentName) ?: Split(null, listOf(componentName))
        return if (split.namespace == own) split.copy(namespace = null) else split
    }

    /**
     * The schema and path of [componentName] when its leading segments are lower_snake and every
     * segment after them is UpperCamel, or null when it is not written so.
     */
    fun strict(componentName: String): Split? {
        val segments = componentName.split('.')
        val schema = segments.takeWhile(ImportNames::isNamespaceSegment)
        val path = segments.drop(schema.size)
        if (schema.isEmpty() || path.isEmpty() || !path.all(::isUpperCamel)) return null
        return Split(schema.joinToString("."), path)
    }
}

/** The help for a name that took a numeric suffix because another construct has it. */
internal const val SUFFIX_HELP = "rename one of them in the document, or keep the suffixed name"

/**
 * The names taken in one scope: a schema's top level, a model's nested declarations, a model's
 * fields, or an enum's values. A name already taken gets the first free numeric suffix, `2`, `3`,
 * and so on; the caller reports the change.
 */
class Scope {
    private val taken = HashSet<String>()

    fun claim(wanted: String): String {
        if (taken.add(wanted)) return wanted
        var n = 2
        while (!taken.add("$wanted$n")) n++
        return "$wanted$n"
    }

    fun taken(name: String): Boolean = name in taken
}
