package io.schemata.target.openapi

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@openapi` keys. */
object OpenApiAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec(
                "openapi",
                "version",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "server",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "name",
                setOf(Element.SERVICE, Element.OPERATION),
                ValueKind.STRING,
                Role.NAME,
            ),
            // the field travels as the named HTTP header where its model is a request, a response,
            // or an error body
            AnnotationSpec(
                "openapi",
                "header",
                setOf(Element.FIELD),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
        )
}
