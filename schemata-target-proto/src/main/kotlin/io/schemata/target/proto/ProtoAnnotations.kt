package io.schemata.target.proto

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@proto` keys. Declared here; consumed when the emitter lowers them (SCH-23). */
object ProtoAnnotations {
    val specs: List<AnnotationSpec> =
        listOf(
            AnnotationSpec(
                "proto",
                "package",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.NAME,
            ),
            AnnotationSpec(
                "proto",
                "name",
                setOf(Element.RECORD, Element.ENUM, Element.FIELD, Element.ENUM_VALUE),
                ValueKind.STRING,
                Role.NAME,
            ),
        )
}
