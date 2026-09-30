package io.schemata.target.jsonschema

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@jsonschema` keys. */
object JsonSchemaAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec(
                "jsonschema",
                "id",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.NAME,
            ),
            AnnotationSpec(
                "jsonschema",
                "name",
                setOf(
                    Element.RECORD,
                    Element.ENUM,
                    Element.UNION,
                    Element.FIELD,
                    Element.ENUM_VALUE,
                ),
                ValueKind.STRING,
                Role.NAME,
            ),
            AnnotationSpec(
                "jsonschema",
                "open",
                setOf(Element.RECORD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
        )
}
