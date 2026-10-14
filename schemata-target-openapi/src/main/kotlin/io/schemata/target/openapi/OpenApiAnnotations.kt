package io.schemata.target.openapi

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind

/** The `@openapi` keys. */
object OpenApiAnnotations {
    /** Where a requirement may be written; the nearest of them wins. */
    private val levels = setOf(Element.NAMESPACE, Element.SERVICE, Element.OPERATION)

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
            // bare `bearer` declares the scheme; `bearer: "JWT"` also names its token format
            AnnotationSpec(
                "openapi",
                "bearer",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
                optional = true,
            ),
            AnnotationSpec(
                "openapi",
                "basic",
                setOf(Element.NAMESPACE),
                ValueKind.FLAG,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "api_key",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "oauth2_token_url",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "oauth2_authorization_url",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            AnnotationSpec(
                "openapi",
                "openid",
                setOf(Element.NAMESPACE),
                ValueKind.STRING,
                Role.REPRESENTATION,
            ),
            // one scheme name, or a tuple of them any one of which admits a call
            AnnotationSpec("openapi", "auth", levels, ValueKind.NAME_TUPLE, Role.REPRESENTATION),
            AnnotationSpec("openapi", "scopes", levels, ValueKind.STRING, Role.REPRESENTATION),
        )
}
