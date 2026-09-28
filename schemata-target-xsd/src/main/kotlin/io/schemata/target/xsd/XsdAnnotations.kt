package io.schemata.target.xsd

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@xsd` keys. */
object XsdAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec(
                "xsd",
                "namespace",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.NAME,
            ),
            AnnotationSpec(
                "xsd",
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
                "xsd",
                "attribute",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "xsd",
                "root",
                setOf(Element.RECORD),
                ValueKind.BOOL,
                Role.REPRESENTATION,
            ),
        )
}
