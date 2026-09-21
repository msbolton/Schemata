package io.schemata.core.annotations

/**
 * Every annotation key the compilation accepts, from core and from every target the caller knows. A
 * key may carry a different value kind per element, so [find] returns all specs for a target and
 * key and the checker picks by element; two specs for one key may not share an element.
 */
class AnnotationRegistry(specs: List<AnnotationSpec>) {
    private val byTarget: Map<String, List<AnnotationSpec>> = specs.groupBy { it.target }

    init {
        specs
            .groupBy { it.target to it.key }
            .forEach { (id, group) ->
                val shared =
                    group.flatMap { it.elements }.groupBy { it }.filter { it.value.size > 1 }.keys
                require(shared.isEmpty()) {
                    "annotation '@${id.first}(${id.second})' is declared more than once for $shared"
                }
            }
    }

    fun hasTarget(target: String): Boolean = target.isNotEmpty() && target in byTarget

    /** Every name that may follow `@`: core keys and target names, sorted. */
    fun names(): List<String> = (keys("") + byTarget.keys.filter { it.isNotEmpty() }).sorted()

    fun keys(target: String): List<String> =
        (byTarget[target] ?: emptyList()).map { it.key }.distinct().sorted()

    fun find(target: String, key: String): List<AnnotationSpec> =
        (byTarget[target] ?: emptyList()).filter { it.key == key }

    companion object {
        /** Core's own keys only; the CLI adds every target's. */
        val CORE = AnnotationRegistry(CoreAnnotations.specs)
    }
}

object CoreAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec(
                target = "",
                key = "deprecated",
                elements =
                    setOf(
                        Element.RECORD,
                        Element.ENUM,
                        Element.UNION,
                        Element.ALIAS,
                        Element.FIELD,
                        Element.ENUM_VALUE,
                    ),
                valueKind = ValueKind.STRING,
                role = Role.REPRESENTATION,
                optional = true,
            )
        )
}
