package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr

/** Where a type expression was written: its file, namespace, and the records enclosing it. */
data class Scope(val file: SourceFile, val namespace: String, val enclosing: List<String>)

/** A resolved type expression. [nullable] folds in a transparent alias's own `?`. */
data class Resolved(val type: Type, val nullable: Boolean, val aliasName: String?)

/**
 * Turns a [TypeExpr] into an IR [Type]. A bare name is looked up in the enclosing records' nested
 * declarations (innermost first), then the current namespace, then unaliased imports, then the
 * builtins. A dotted name is either `Outer.Inner`, `alias.Name`, or a fully qualified name.
 */
class Resolver(
    private val index: DeclarationIndex,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val mapKeyTypes = setOf(Builtin.STRING, Builtin.INT32, Builtin.INT64)

    fun resolve(expr: TypeExpr, scope: Scope): Resolved? {
        when (expr.name) {
            "list" ->
                return generic(expr, scope, arity = 1) { args ->
                    ListOf(args[0].type, args[0].nullable)
                }
            "map" ->
                return generic(expr, scope, arity = 2) { args ->
                    val key = args[0]
                    if (key.nullable) {
                        error(
                            CoreCodes.NULLABLE_MAP_KEY,
                            "map keys may not be nullable",
                            expr.args[0].span,
                        )
                        return@generic null
                    }
                    if ((key.type as? Scalar)?.builtin !in mapKeyTypes) {
                        error(
                            CoreCodes.MAP_KEY_TYPE,
                            "map keys must be string, int32, or int64",
                            expr.args[0].span,
                        )
                        return@generic null
                    }
                    MapOf(key.type, args[1].type, args[1].nullable)
                }
        }
        if (expr.args.isNotEmpty()) {
            error(CoreCodes.NOT_GENERIC, "'${expr.name}' is not generic", expr.nameSpan)
            return null
        }
        val found = lookup(expr.name, expr.nameSpan, scope) ?: return null
        return when (found) {
            is Found.Builtin -> Resolved(Scalar(found.builtin), expr.nullable, null)
            is Found.Decl -> declared(found.entry, expr, scope)
        }
    }

    private fun generic(
        expr: TypeExpr,
        scope: Scope,
        arity: Int,
        build: (List<Resolved>) -> Type?,
    ): Resolved? {
        if (expr.args.size != arity) {
            val plural = if (arity == 1) "argument" else "arguments"
            error(
                CoreCodes.GENERIC_ARITY,
                "${expr.name} takes $arity type $plural, got ${expr.args.size}",
                expr.nameSpan,
            )
            return null
        }
        val args = expr.args.map { resolve(it, scope) ?: return null }
        val type = build(args) ?: return null
        return Resolved(type, expr.nullable, null)
    }

    /**
     * Task 3 replaces this body with alias substitution; for now every declaration is a reference.
     */
    private fun declared(entry: IndexedDecl, expr: TypeExpr, scope: Scope): Resolved? =
        Resolved(Ref(entry.qualifiedName), expr.nullable, null)

    private sealed interface Found {
        data class Builtin(val builtin: io.schemata.core.ir.Builtin) : Found

        data class Decl(val entry: IndexedDecl) : Found
    }

    private fun lookup(name: String, at: Span, scope: Scope): Found? {
        val parts = name.split('.')
        val head = parts.first()
        val base: IndexedDecl? =
            enclosing(head, scope)
                ?: index.find(QualifiedName(scope.namespace, listOf(head)))
                ?: imported(head, scope)
        if (base != null) return descend(base, parts.drop(1), at)
        qualified(parts)?.let {
            return it
        }
        if (parts.size == 1)
            Builtin.byName(head)?.let {
                return Found.Builtin(it)
            }
        error(CoreCodes.UNKNOWN_TYPE, "unknown type '$name'", at)
        return null
    }

    private fun enclosing(head: String, scope: Scope): IndexedDecl? {
        for (depth in scope.enclosing.size downTo 1) {
            index.find(QualifiedName(scope.namespace, scope.enclosing.take(depth) + head))?.let {
                return it
            }
        }
        return null
    }

    /** Task 3 fills this in. */
    private fun imported(head: String, scope: Scope): IndexedDecl? = null

    /** `shop.customers.Customer[.Nested…]`: the longest namespace prefix that exists wins. */
    private fun qualified(parts: List<String>): Found? {
        for (split in parts.size - 1 downTo 1) {
            val namespace = parts.take(split).joinToString(".")
            if (!index.namespaceExists(namespace)) continue
            val base = index.find(QualifiedName(namespace, listOf(parts[split]))) ?: continue
            return descend(base, parts.drop(split + 1), null)
        }
        return null
    }

    private fun descend(base: IndexedDecl, rest: List<String>, at: Span?): Found? {
        var current = base
        for (segment in rest) {
            val next =
                index.find(
                    QualifiedName(
                        current.qualifiedName.namespace,
                        current.qualifiedName.path + segment,
                    )
                )
            if (next == null || current.decl !is RecordDecl) {
                error(
                    CoreCodes.NESTED_TYPE_NOT_FOUND,
                    "type '${current.decl.name}' has no nested type '$segment'",
                    at ?: current.decl.nameSpan,
                )
                return null
            }
            current = next
        }
        return Found.Decl(current)
    }

    private fun error(code: DiagnosticCode, message: String, span: Span) {
        diagnostics += Diagnostic(code, message, span)
    }
}
