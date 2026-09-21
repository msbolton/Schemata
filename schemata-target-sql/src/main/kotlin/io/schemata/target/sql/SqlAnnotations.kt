package io.schemata.target.sql

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@sql` keys. Declared here; consumed when the emitter lowers them (SCH-28 to SCH-32). */
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
            AnnotationSpec("sql", "key", setOf(Element.FIELD), ValueKind.FLAG, Role.STRATEGY),
            AnnotationSpec(
                "sql",
                "key",
                setOf(Element.RECORD),
                ValueKind.NAME_TUPLE,
                Role.STRATEGY,
            ),
            AnnotationSpec(
                "sql",
                "strategy",
                setOf(Element.FIELD),
                ValueKind.NAME,
                Role.STRATEGY,
                choices = setOf("embed", "table", "json"),
            ),
            AnnotationSpec(
                "sql",
                "unique",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "sql",
                "index",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
        )
}
