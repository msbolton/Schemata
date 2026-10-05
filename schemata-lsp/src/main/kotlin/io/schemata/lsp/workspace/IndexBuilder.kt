package io.schemata.lsp.workspace

import io.schemata.core.IndexedDecl
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

/**
 * Builds a set's [ReferenceIndex] from what the resolver recorded (type names, import aliases,
 * namespace prefixes) and from a walk of the trees for everything the resolver never looks up:
 * where each symbol is defined, import lines, enum values used as defaults, and field names listed
 * in a record's annotation.
 */
class IndexBuilder private constructor(recorded: Recorded) {
    private val sites = mutableListOf<Site>()
    private val builtins = mutableListOf<BuiltinSite>()
    private val declarations = linkedMapOf<QualifiedName, DeclaredAt>()
    private val services = linkedMapOf<QualifiedName, ServiceAt>()
    private val typeAt: Map<Span, IndexedDecl> = recorded.types.toMap()

    init {
        recorded.types.forEach { (site, target) ->
            sites += Site(site, Symbol.Declaration(target.qualifiedName), definition = false)
        }
        recorded.aliases.forEach { (site, import) ->
            import.alias?.let {
                sites += Site(site, Symbol.ImportAlias(site.file, it), definition = false)
            }
        }
        recorded.namespaces.forEach { (site, namespace) ->
            sites += Site(site, Symbol.Namespace(namespace), definition = false)
        }
    }

    private fun file(file: SourceFile) {
        val namespace = file.namespace.name
        sites += Site(file.namespace.nameSpan, Symbol.Namespace(namespace), definition = true)
        file.imports.forEach { import ->
            sites += Site(import.namespaceSpan, Symbol.Namespace(import.namespace), false)
            val alias = import.alias
            val aliasSpan = import.aliasSpan
            if (alias != null && aliasSpan != null) {
                sites += Site(aliasSpan, Symbol.ImportAlias(file.path, alias), definition = true)
            }
        }
        file.declarations.forEach { declaration(file, namespace, emptyList(), it) }
        file.services.forEach { service(file, namespace, it) }
    }

    /** A service and its operations define names; its payload types are recorded as references. */
    private fun service(file: SourceFile, namespace: String, decl: ServiceDecl) {
        val name = QualifiedName(namespace, listOf(decl.name))
        services.putIfAbsent(name, ServiceAt(decl, file))
        sites += Site(decl.nameSpan, Symbol.Service(name), definition = true)
        decl.operations.forEach { op ->
            sites += Site(op.nameSpan, Symbol.Operation(name, op.name), definition = true)
            op.request?.let { builtinsIn(it.type) }
            op.response?.let { builtinsIn(it.type) }
        }
    }

    private fun declaration(
        file: SourceFile,
        namespace: String,
        parent: List<String>,
        decl: Declaration,
    ) {
        val name = QualifiedName(namespace, parent + decl.name)
        declarations.putIfAbsent(name, DeclaredAt(decl, file))
        sites += Site(decl.nameSpan, Symbol.Declaration(name), definition = true)
        when (decl) {
            is RecordDecl -> {
                decl.fields.forEach { field ->
                    sites += Site(field.nameSpan, Symbol.Field(name, field.name), true)
                    builtinsIn(field.type)
                    enumDefault(field)
                }
                tupleNames(decl, name)
                decl.nested.forEach { declaration(file, namespace, parent + decl.name, it) }
            }
            is EnumDecl ->
                decl.values.forEach {
                    sites += Site(it.nameSpan, Symbol.EnumValue(name, it.name), definition = true)
                }
            is UnionDecl -> decl.members.forEach { builtinsIn(it.type) }
            is AliasDecl -> builtinsIn(decl.type)
        }
    }

    /** A single-segment name the resolver did not resolve to a declaration, if it is a builtin. */
    private fun builtinsIn(type: TypeExpr) {
        val only = type.nameSegments.singleOrNull()
        if (only != null && only !in typeAt && type.name in BuiltinDocs.text) {
            builtins += BuiltinSite(only, type.name)
        }
        type.args.forEach(::builtinsIn)
    }

    private fun enumDefault(field: FieldDecl) {
        val literal = field.default as? Literal.NameLit ?: return
        val (owner, enum) = enumOf(field.type, depth = 0) ?: return
        if (enum.values.any { it.name == literal.name }) {
            sites += Site(literal.span, Symbol.EnumValue(owner, literal.name), definition = false)
        }
    }

    /** The enum a type names, following aliases; the depth bound guards a cyclic alias. */
    private fun enumOf(type: TypeExpr, depth: Int): Pair<QualifiedName, EnumDecl>? {
        val target = typeAt[type.nameSegments.lastOrNull() ?: return null] ?: return null
        return when (val decl = target.decl) {
            is EnumDecl -> target.qualifiedName to decl
            is AliasDecl -> if (depth < 16) enumOf(decl.type, depth + 1) else null
            else -> null
        }
    }

    /** `@sql(unique = (a, b))` on a record: each name that is one of its fields refers to it. */
    private fun tupleNames(record: RecordDecl, owner: QualifiedName) {
        val fields = record.fields.map { it.name }.toSet()
        record.annotations
            .flatMap { it.args }
            .map {
                when (it) {
                    is AnnotationArg.Named -> it.value
                    is AnnotationArg.Positional -> it.value
                }
            }
            .filterIsInstance<AnnotationValue.Tuple>()
            .forEach { tuple ->
                tuple.names.zip(tuple.nameSpans).forEach { (name, span) ->
                    if (name in fields) sites += Site(span, Symbol.Field(owner, name), false)
                }
            }
    }

    companion object {
        fun build(files: List<SourceFile>, recorded: Recorded): ReferenceIndex {
            val builder = IndexBuilder(recorded)
            files.sortedBy { it.path }.forEach(builder::file)
            return ReferenceIndex(
                builder.sites,
                builder.builtins,
                builder.declarations,
                builder.services,
            )
        }
    }
}
