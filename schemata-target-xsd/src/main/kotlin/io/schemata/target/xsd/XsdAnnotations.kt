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
            AnnotationSpec("xsd", "any", setOf(Element.FIELD), ValueKind.FLAG, Role.REPRESENTATION),
            AnnotationSpec(
                "xsd",
                "any_type",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "xsd",
                "any_attribute",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "xsd",
                "process",
                setOf(Element.FIELD),
                ValueKind.STRING,
                Role.REPRESENTATION,
                choices = setOf("lax", "strict", "skip"),
            ),
            AnnotationSpec(
                "xsd",
                "wildcard",
                setOf(Element.FIELD),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "xsd",
                "mixed",
                setOf(Element.FIELD),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "xsd",
                "element_form",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
                choices = setOf("qualified", "unqualified"),
            ),
            AnnotationSpec(
                "xsd",
                "attribute_form",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
                choices = setOf("qualified", "unqualified"),
            ),
        )
}
