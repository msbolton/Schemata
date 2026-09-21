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
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.ImportDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr

/** Where a type expression was written: its file, namespace, and the records enclosing it. */
data class Scope(val file: SourceFile, val namespace: String, val enclosing: List<String>)

/** A resolved type expression. [nullable] folds in a transparent alias's own `?`. */
data class Resolved(val type: Type, val nullable: Boolean, val aliasName: String?)

/**
 * Turns a [TypeExpr] into an IR [Type]. A bare name is looked up in the enclosing records' nested
 * declarations (innermost first); failing that, in the current namespace and every unaliased import
 * together, where more than one hit is ambiguous; failing that, among the builtins. A dotted name
 * is `Outer.Inner`, `alias.Name` through an aliased import, or a fully qualified name. Aliases are
 * substituted at every use.
 */
class Resolver(
    private val index: DeclarationIndex,
    files: List<SourceFile>,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val mapKeyTypes = setOf(Builtin.STRING, Builtin.INT32, Builtin.INT64)
    private val imports: Map<String, List<ImportDecl>> = files.associate { it.path to it.imports }
    private val usedImports = mutableSetOf<ImportDecl>()
    private val resolvingAliases = mutableSetOf<QualifiedName>()

    init {
        files
            .sortedBy { it.path }
            .forEach { file ->
                file.imports.forEach { imp ->
                    if (!index.namespaceExists(imp.namespace)) {
                        error(
                            CoreCodes.UNKNOWN_IMPORT,
                            "import '${imp.namespace}' does not name a namespace in this compilation",
                            imp.span,
                        )
                        usedImports += imp // never reported as unused as well
                    }
                }
            }
    }

    /** Call once after every type expression in the compilation has been resolved. */
    fun finish() {
        imports.toSortedMap().forEach { (_, list) ->
            list
                .filter { it !in usedImports }
                .forEach {
                    diagnostics +=
                        Diagnostic(
                            CoreCodes.UNUSED_IMPORT,
                            "import '${it.namespace}' is unused",
                            it.span,
                        )
                }
        }
    }

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
        return when (val found = lookup(expr.name, expr.nameSpan, scope) ?: return null) {
            is Found.Builtin -> Resolved(Scalar(found.builtin), expr.nullable, null)
            is Found.Decl -> declared(found.entry, expr)
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

    private fun declared(entry: IndexedDecl, expr: TypeExpr): Resolved? {
        val alias =
            entry.decl as? AliasDecl
                ?: return Resolved(Ref(entry.qualifiedName), expr.nullable, null)
        if (!resolvingAliases.add(entry.qualifiedName)) {
            error(CoreCodes.ALIAS_CYCLE, "alias '${alias.name}' refers to itself", expr.nameSpan)
            return null
        }
        try {
            val aliasScope =
                Scope(
                    entry.file,
                    entry.qualifiedName.namespace,
                    entry.qualifiedName.path.dropLast(1),
                )
            val target = resolve(alias.type, aliasScope) ?: return null
            if (target.nullable && expr.nullable) {
                error(
                    CoreCodes.DOUBLE_NULLABLE,
                    "'${alias.name}' is already nullable; remove the '?'",
                    expr.span,
                )
                return null
            }
            return Resolved(target.type, target.nullable || expr.nullable, alias.name)
        } finally {
            resolvingAliases -= entry.qualifiedName
        }
    }

    private sealed interface Found {
        data class Builtin(val builtin: io.schemata.core.ir.Builtin) : Found

        data class Decl(val entry: IndexedDecl) : Found
    }

    private fun lookup(name: String, at: Span, scope: Scope): Found? {
        val parts = name.split('.')
        val head = parts.first()
        enclosing(head, scope)?.let {
            return descend(it, parts.drop(1), at)
        }
        val candidates =
            listOfNotNull(index.find(QualifiedName(scope.namespace, listOf(head)))) +
                imported(head, scope)
        if (candidates.size > 1) {
            val where =
                candidates
                    .sortedWith(compareBy({ it.file.path }, { it.decl.nameSpan.startLine }))
                    .joinToString(", ") { "${it.file.path}:${it.decl.nameSpan.startLine}" }
            error(CoreCodes.AMBIGUOUS_TYPE, "ambiguous type '$head': $where", at)
            return null
        }
        candidates.singleOrNull()?.let {
            return descend(it, parts.drop(1), at)
        }
        // a form that reported its own error must not fall through to "unknown type"
        val before = diagnostics.size
        aliased(parts, scope, at)?.let {
            return it
        }
        if (diagnostics.size != before) return null
        val beforeQualified = diagnostics.size
        qualified(parts, at)?.let {
            return it
        }
        if (diagnostics.size != beforeQualified) return null
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

    private fun imported(head: String, scope: Scope): List<IndexedDecl> =
        (imports[scope.file.path] ?: emptyList())
            .filter { it.alias == null }
            .mapNotNull { imp ->
                index.find(QualifiedName(imp.namespace, listOf(head)))?.also { usedImports += imp }
            }

    private fun aliased(parts: List<String>, scope: Scope, at: Span): Found? {
        if (parts.size < 2) return null
        val imp =
            (imports[scope.file.path] ?: emptyList()).firstOrNull { it.alias == parts[0] }
                ?: return null
        usedImports += imp
        val base = index.find(QualifiedName(imp.namespace, listOf(parts[1]))) ?: return null
        return descend(base, parts.drop(2), at)
    }

    /** `shop.customers.Customer[.Nested…]`: the longest namespace prefix that exists wins. */
    private fun qualified(parts: List<String>, at: Span): Found? {
        for (split in parts.size - 1 downTo 1) {
            val namespace = parts.take(split).joinToString(".")
            if (!index.namespaceExists(namespace)) continue
            val base = index.find(QualifiedName(namespace, listOf(parts[split]))) ?: continue
            return descend(base, parts.drop(split + 1), at)
        }
        return null
    }

    private fun descend(base: IndexedDecl, rest: List<String>, at: Span): Found? {
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
                    at,
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
