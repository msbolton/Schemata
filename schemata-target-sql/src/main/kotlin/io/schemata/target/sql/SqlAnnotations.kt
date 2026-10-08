package io.schemata.target.sql

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/**
 * The `@sql` keys. A key, uniqueness, an index, and embedding are the language's own options (`id`,
 * `unique`, `index`, `embed`), so none of them is an `@sql` key.
 */
object SqlAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec("sql", "schema", setOf(Element.NAMESPACE), ValueKind.STRING, Role.NAME),
            AnnotationSpec("sql", "table", setOf(Element.RECORD), ValueKind.STRING, Role.NAME),
            AnnotationSpec("sql", "column", setOf(Element.FIELD), ValueKind.STRING, Role.NAME),
            AnnotationSpec(
                "sql",
                "type",
                setOf(Element.FIELD),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "sql",
                "strategy",
                setOf(Element.FIELD),
                ValueKind.NAME,
                Role.STRATEGY,
                choices = setOf("table", "json"),
            ),
        )
}
