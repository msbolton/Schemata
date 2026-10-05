package io.schemata.lang.ast

import io.schemata.lang.Span

/**
 * Root of a parsed `.schemata` file. Built by hand from the parse tree; no ANTLR types leak past
 * here. Every node carries the [Span] of its source text, and every span names the file.
 */
data class SourceFile(
    val path: String,
    val doc: String?,
    val annotations: List<Annotation>,
    val namespace: NamespaceDecl,
    val imports: List<ImportDecl>,
    val declarations: List<Declaration>,
    val span: Span,
    val services: List<ServiceDecl> = emptyList(),
)

/** `namespace shop.orders` — [name] keeps the dots; [nameSpan] covers the name alone. */
data class NamespaceDecl(val name: String, val nameSpan: Span, val span: Span)

/**
 * `import shop.customers` or `import shop.customers as cust`. [namespaceSpan] covers the namespace
 * name, [aliasSpan] the alias when there is one.
 */
data class ImportDecl(
    val namespace: String,
    val alias: String?,
    val namespaceSpan: Span,
    val aliasSpan: Span?,
    val span: Span,
)

sealed interface Declaration {
    val name: String
    val nameSpan: Span
    val doc: String?
    val annotations: List<Annotation>
    val span: Span
}

data class RecordDecl(
    override val name: String,
    override val nameSpan: Span,
    val fields: List<FieldDecl>,
    val nested: List<Declaration>,
    val reserved: List<ReservedItem>,
    override val doc: String?,
    override val annotations: List<Annotation>,
    override val span: Span,
) : Declaration

data class EnumDecl(
    override val name: String,
    override val nameSpan: Span,
    val values: List<EnumValueDecl>,
    val reserved: List<ReservedItem>,
    override val doc: String?,
    override val annotations: List<Annotation>,
    override val span: Span,
) : Declaration

data class UnionDecl(
    override val name: String,
    override val nameSpan: Span,
    val members: List<UnionMemberDecl>,
    override val doc: String?,
    override val annotations: List<Annotation>,
    override val span: Span,
) : Declaration

data class AliasDecl(
    override val name: String,
    override val nameSpan: Span,
    val type: TypeExpr,
    override val doc: String?,
    override val annotations: List<Annotation>,
    override val span: Span,
) : Declaration

/**
 * `service Orders { … }`: not a [Declaration], so it never nests and no `when` over declarations
 * sees it.
 */
data class ServiceDecl(
    val name: String,
    val nameSpan: Span,
    val operations: List<OperationDecl>,
    val reserved: List<ReservedItem>,
    val doc: String?,
    val annotations: List<Annotation>,
    val span: Span,
)

/**
 * `#1 get(OrderId): Order get "/orders/{id}"`. [request] is null for `()`, [response] when there is
 * no `: …`, and [binding] when no HTTP route is given.
 */
data class OperationDecl(
    val ordinal: Int?,
    val ordinalSpan: Span?,
    val name: String,
    val nameSpan: Span,
    val request: PayloadDecl?,
    val response: PayloadDecl?,
    val binding: BindingDecl?,
    val doc: String?,
    val annotations: List<Annotation>,
    val span: Span,
)

/** `stream Order` or `Order`. */
data class PayloadDecl(val type: TypeExpr, val stream: Boolean, val span: Span)

/**
 * `get "/orders/{id}"`; [parameters] are the `{name}` segments in order; [pathSpan] is the
 * literal's.
 */
data class BindingDecl(
    val verb: String,
    val verbSpan: Span,
    val path: String,
    val pathSpan: Span,
    val parameters: List<String>,
    val span: Span,
)

/** [ordinal] is null when the field has no `#n`; the checker decides all-or-nothing. */
data class FieldDecl(
    val ordinal: Int?,
    val ordinalSpan: Span?,
    val name: String,
    val nameSpan: Span,
    val type: TypeExpr,
    val default: Literal?,
    val doc: String?,
    val annotations: List<Annotation>,
    val span: Span,
)

data class EnumValueDecl(
    val ordinal: Int?,
    val ordinalSpan: Span?,
    val name: String,
    val nameSpan: Span,
    val doc: String?,
    val annotations: List<Annotation>,
    val span: Span,
)

data class UnionMemberDecl(
    val ordinal: Int?,
    val ordinalSpan: Span?,
    val type: TypeExpr,
    val doc: String?,
    val span: Span,
)

sealed interface ReservedItem {
    val span: Span

    /** `reserved #3` is `Ordinals(3, 3)`; `reserved #5..#7` is `Ordinals(5, 7)`. */
    data class Ordinals(val from: Int, val to: Int, override val span: Span) : ReservedItem

    data class Name(val name: String, override val span: Span) : ReservedItem
}

/**
 * A type as written: `list<string(max = 5)>?`. [name] is the qualified name verbatim (`Outer.Inner`
 * or `cust.Customer`), and [nameSegments] holds one span per identifier of it, in order, since
 * whitespace or a comment may sit between segments. Whether it resolves, and whether the
 * refinements are legal for it, is the analyzer's business.
 */
data class TypeExpr(
    val name: String,
    val nameSpan: Span,
    val nameSegments: List<Span>,
    val args: List<TypeExpr>,
    val refinements: List<Refinement>,
    val nullable: Boolean,
    val span: Span,
)

sealed interface Refinement {
    val value: Literal
    val span: Span

    data class Named(val name: String, override val value: Literal, override val span: Span) :
        Refinement

    data class Positional(override val value: Literal, override val span: Span) : Refinement
}

data class Annotation(val name: String, val args: List<AnnotationArg>, val span: Span)

sealed interface AnnotationArg {
    val span: Span

    data class Named(val name: String, val value: AnnotationValue, override val span: Span) :
        AnnotationArg

    /** A bare flag such as `@sql(key)` is `Positional(Lit(NameLit("key")))`. */
    data class Positional(val value: AnnotationValue, override val span: Span) : AnnotationArg
}

sealed interface AnnotationValue {
    val span: Span

    data class Lit(val literal: Literal, override val span: Span) : AnnotationValue

    /**
     * `(a, b)` — used by `@sql(key = (tenant_id, id))`. [nameSpans] holds one span per name, in
     * order.
     */
    data class Tuple(val names: List<String>, val nameSpans: List<Span>, override val span: Span) :
        AnnotationValue
}

sealed interface Literal {
    val span: Span

    data class IntLit(val value: Long, override val span: Span) : Literal

    /** Kept as text; decimal semantics are decided where the literal is used. */
    data class FloatLit(val text: String, override val span: Span) : Literal

    /** [value] is unescaped: `\"` and `\\` are resolved. */
    data class StringLit(val value: String, override val span: Span) : Literal

    data class BoolLit(val value: Boolean, override val span: Span) : Literal

    /** A bare identifier used as a value: an enum value default, or a flag/keyword like `embed`. */
    data class NameLit(val name: String, override val span: Span) : Literal
}
