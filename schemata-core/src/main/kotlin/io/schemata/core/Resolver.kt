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
    private val references: ReferenceRecorder? = null,
) {
    private val mapKeyTypes = setOf(Builtin.STRING, Builtin.INT32, Builtin.INT64)
    private val imports: Map<String, List<ImportDecl>> = files.associate { it.path to it.imports }
    private val usedImports = mutableSetOf<ImportDecl>()
    private val resolvingAliases = mutableSetOf<QualifiedName>()
    private val aliasTargets = mutableMapOf<QualifiedName, Resolved?>()

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
                            help =
                                "add the file that declares `namespace ${imp.namespace}` to the compilation, or fix the import",
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
                            help = "remove the import",
                        )
                }
        }
    }

    fun resolve(expr: TypeExpr, scope: Scope): Resolved? {
        when (expr.name) {
            "list" ->
                return generic(expr, scope, arity = 1) { args ->
                    val refinements =
                        RefinementChecker.collection("list", expr, diagnostics)
                            ?: return@generic null
                    ListOf(args[0].type, args[0].nullable, refinements)
                }
            "map" ->
                return generic(expr, scope, arity = 2) { args ->
                    val key = args[0]
                    if (key.nullable) {
                        error(
                            CoreCodes.NULLABLE_MAP_KEY,
                            "map key ${expr.args[0].text()} is nullable",
                            expr.args[0].span,
                            help = "write the key type without `?`",
                        )
                        return@generic null
                    }
                    if ((key.type as? Scalar)?.builtin !in mapKeyTypes) {
                        error(
                            CoreCodes.MAP_KEY_TYPE,
                            "map key ${expr.args[0].text()} must be string, int32, or int64",
                            expr.args[0].span,
                            help =
                                "use one of the three key types, or store the entries as a list of records",
                        )
                        return@generic null
                    }
                    val refinements =
                        RefinementChecker.collection("map", expr, diagnostics)
                            ?: return@generic null
                    MapOf(key.type, args[1].type, args[1].nullable, refinements)
                }
        }
        if (expr.args.isNotEmpty()) {
            error(
                CoreCodes.NOT_GENERIC,
                "'${expr.name}' is not generic",
                expr.nameSpan,
                help = "remove the type arguments; only `list` and `map` take them",
            )
            return null
        }
        return when (
            val found = lookup(expr.name, expr.nameSpan, expr.nameSegments, scope) ?: return null
        ) {
            is Found.Builtin -> {
                val refinements =
                    RefinementChecker.scalar(found.builtin, expr, diagnostics) ?: return null
                Resolved(Scalar(found.builtin, refinements), expr.nullable, null)
            }
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
                "'${expr.name}' takes $arity type $plural; got ${expr.args.size}",
                expr.nameSpan,
                help = if (expr.name == "list") "write `list<T>`" else "write `map<K, V>`",
            )
            return null
        }
        val args = expr.args.map { resolve(it, scope) ?: return null }
        val type = build(args) ?: return null
        return Resolved(type, expr.nullable, null)
    }

    /**
     * Resolves an alias at its declaration so an unused alias is still checked. Every use then
     * reads the memoized result, so the alias's own diagnostics are reported once.
     */
    fun checkAlias(decl: AliasDecl, scope: Scope) {
        val entry =
            index.find(QualifiedName(scope.namespace, scope.enclosing + decl.name)) ?: return
        aliasTarget(entry, decl, decl.nameSpan)
    }

    private fun declared(entry: IndexedDecl, expr: TypeExpr): Resolved? {
        val alias = entry.decl as? AliasDecl
        if (expr.refinements.isNotEmpty()) {
            val message: String
            val help: String
            if (alias != null) {
                message = "'${alias.name}' is an alias and takes no refinements here"
                help = "refine the alias where it is declared, or write the builtin type here"
            } else {
                val kind = DeclarationIndex.kindOf(entry.decl)
                val article = if (kind == "enum") "an" else "a"
                message =
                    "'${entry.decl.name}' is $article $kind; only builtin types and collections take refinements"
                help = "remove the parentheses"
            }
            error(CoreCodes.REFINEMENT_NOT_ALLOWED, message, expr.refinements.first().span, help)
            return null
        }
        if (alias == null) return Resolved(Ref(entry.qualifiedName), expr.nullable, null)
        val target = aliasTarget(entry, alias, expr.nameSpan) ?: return null
        if (target.nullable && expr.nullable) {
            error(
                CoreCodes.DOUBLE_NULLABLE,
                "'${alias.name}' is already nullable",
                expr.span,
                help = "remove the `?`",
            )
            return null
        }
        return Resolved(target.type, target.nullable || expr.nullable, alias.name)
    }

    private fun aliasTarget(entry: IndexedDecl, alias: AliasDecl, at: Span): Resolved? {
        if (entry.qualifiedName in aliasTargets) return aliasTargets[entry.qualifiedName]
        if (!resolvingAliases.add(entry.qualifiedName)) {
            error(
                CoreCodes.ALIAS_CYCLE,
                "alias '${alias.name}' refers to itself",
                at,
                help = "point the alias at a builtin or a declared type",
            )
            return null
        }
        try {
            val aliasScope =
                Scope(
                    entry.file,
                    entry.qualifiedName.namespace,
                    entry.qualifiedName.path.dropLast(1),
                )
            val target = resolve(alias.type, aliasScope)
            aliasTargets[entry.qualifiedName] = target
            return target
        } finally {
            resolvingAliases -= entry.qualifiedName
        }
    }

    private sealed interface Found {
        data class Builtin(val builtin: io.schemata.core.ir.Builtin) : Found

        data class Decl(val entry: IndexedDecl) : Found
    }

    private fun lookup(name: String, at: Span, sites: List<Span>, scope: Scope): Found? {
        val parts = name.split('.')
        val head = parts.first()
        enclosing(head, scope)?.let {
            return descend(it, parts.drop(1), at, sites)
        }
        val candidates =
            listOfNotNull(index.find(QualifiedName(scope.namespace, listOf(head)))) +
                imported(head, scope)
        if (candidates.size > 1) {
            val sorted =
                candidates.sortedWith(compareBy({ it.file.path }, { it.decl.nameSpan.startLine }))
            val where = sorted.joinToString(", ") { it.qualifiedName.toString() }
            error(
                CoreCodes.AMBIGUOUS_TYPE,
                "type '$head' is ambiguous; candidates: $where",
                at,
                help =
                    "write the qualified name, for example `${sorted.first().qualifiedName}`, or alias one import",
            )
            return null
        }
        candidates.singleOrNull()?.let {
            return descend(it, parts.drop(1), at, sites)
        }
        // a form that reported its own error must not fall through to "unknown type"
        val before = diagnostics.size
        aliased(parts, scope, at, sites)?.let {
            return it
        }
        if (diagnostics.size != before) return null
        val beforeQualified = diagnostics.size
        qualified(parts, at, sites)?.let {
            return it
        }
        if (diagnostics.size != beforeQualified) return null
        if (parts.size == 1)
            Builtin.byName(head)?.let {
                return Found.Builtin(it)
            }
        error(
            CoreCodes.UNKNOWN_TYPE,
            "unknown type '$name'",
            at,
            help =
                "declare `$name`, import the namespace that declares it, or check the spelling against the builtin types",
        )
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

    private fun aliased(parts: List<String>, scope: Scope, at: Span, sites: List<Span>): Found? {
        if (parts.size < 2) return null
        val imp =
            (imports[scope.file.path] ?: emptyList()).firstOrNull { it.alias == parts[0] }
                ?: return null
        usedImports += imp
        sites.firstOrNull()?.let { references?.alias(it, imp) }
        val base = index.find(QualifiedName(imp.namespace, listOf(parts[1]))) ?: return null
        return descend(base, parts.drop(2), at, sites.drop(1))
    }

    /** `shop.customers.Customer[.Nested…]`: the longest namespace prefix that exists wins. */
    private fun qualified(parts: List<String>, at: Span, sites: List<Span>): Found? {
        for (split in parts.size - 1 downTo 1) {
            val namespace = parts.take(split).joinToString(".")
            if (!index.namespaceExists(namespace)) continue
            val base = index.find(QualifiedName(namespace, listOf(parts[split]))) ?: continue
            if (sites.size == parts.size) {
                val first = sites.first()
                val last = sites[split - 1]
                references?.namespace(
                    Span(
                        first.file,
                        first.startLine,
                        first.startColumn,
                        last.endLine,
                        last.endColumn,
                    ),
                    namespace,
                )
            }
            return descend(base, parts.drop(split + 1), at, sites.drop(split))
        }
        return null
    }

    /** [sites] holds the span of [base]'s segment first, then one per segment of [rest]. */
    private fun descend(
        base: IndexedDecl,
        rest: List<String>,
        at: Span,
        sites: List<Span>,
    ): Found? {
        sites.firstOrNull()?.let { references?.type(it, base) }
        var current = base
        rest.forEachIndexed { i, segment ->
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
                    help =
                        "declare `$segment` inside `${current.decl.name}`, or refer to it by its own qualified name",
                )
                return null
            }
            sites.getOrNull(i + 1)?.let { references?.type(it, next) }
            current = next
        }
        return Found.Decl(current)
    }

    private fun error(code: DiagnosticCode, message: String, span: Span, help: String? = null) {
        diagnostics += Diagnostic(code, message, span, help)
    }
}

/** The type as the user wrote it: its name, generic args recursively, and a trailing `?`. */
internal fun TypeExpr.text(): String {
    val base = if (args.isEmpty()) name else "$name<${args.joinToString(", ") { it.text() }}>"
    return if (nullable) "$base?" else base
}
