package io.schemata.lang.ast

import io.schemata.lang.Span

/**
 * Root of a parsed `.schemata` file. Built by hand from the parse tree; no ANTLR types leak past
 * here.
 */
data class SourceFile(
    val path: String,
    val namespace: NamespaceDecl,
    val declarations: List<Declaration>,
    val span: Span,
)

/** `namespace shop.orders` — [name] keeps the dots. */
data class NamespaceDecl(val name: String, val span: Span)

sealed interface Declaration {
    val name: String
    val span: Span
}

data class RecordDecl(
    override val name: String,
    val fields: List<FieldDecl>,
    override val span: Span,
) : Declaration

data class FieldDecl(val name: String, val type: TypeRef, val span: Span)

/** A reference to a type by name. Whether the name resolves is the analyzer's business. */
data class TypeRef(val name: String, val nullable: Boolean, val span: Span)
