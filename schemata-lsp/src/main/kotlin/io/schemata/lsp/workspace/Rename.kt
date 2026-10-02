package io.schemata.lsp.workspace

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Names
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/** One replacement in a file, in editor coordinates. */
data class TextEdit(val range: TextRange, val newText: String)

sealed interface RenameResult {
    /** Replacements per file path. */
    data class Edits(val edits: Map<String, List<TextEdit>>) : RenameResult

    /** Why the rename cannot be done, in words for the user. */
    data class Refused(val message: String) : RenameResult
}

/** Not a lexer keyword, but it reads as one, so rename refuses it as one. */
private val reservedWords = setOf("null")

/**
 * Renames one symbol of a set: its definition and every reference. Before it answers with edits it
 * checks that nothing else changes meaning: it applies the edits in memory, analyses the result,
 * and compares every name's target before and after.
 */
internal class Rename(
    private val workspace: Workspace,
    private val analysis: SetAnalysis,
    private val symbol: Symbol,
    private val newName: String,
) {
    fun run(): RenameResult {
        if (symbol is Symbol.Namespace) {
            return RenameResult.Refused(
                "a namespace cannot be renamed; it is the name its files declare"
            )
        }
        analysis.members
            .firstOrNull { it.broken }
            ?.let {
                return RenameResult.Refused(
                    "fix the syntax errors in ${fileName(it.path)} before renaming"
                )
            }
        val refusal =
            when {
                newName in Names.keywords || newName in reservedWords -> "'$newName' is a keyword"
                !Names.isIdentifier(newName) -> "'$newName' is not a valid name"
                symbol is Symbol.Declaration && isBuiltin(newName) ->
                    "'$newName' is a builtin type name"
                else -> collision() ?: leftBehind()
            }
        if (refusal != null) return RenameResult.Refused(refusal)
        val index = analysis.index
        val edits =
            (index.definitions(symbol) + index.references(symbol))
                .mapNotNull { span ->
                    analysis.snapshot(span.file)?.let { span.file to it.lines.range(span) }
                }
                .groupBy({ it.first }, { TextEdit(it.second, newName) })
        simulate(edits)?.let {
            return RenameResult.Refused(it)
        }
        changedOnDisk(edits.keys)?.let {
            return RenameResult.Refused("${fileName(it)} changed on disk; try again")
        }
        return RenameResult.Edits(edits)
    }

    private fun isBuiltin(name: String) =
        Builtin.byName(name) != null || name == "list" || name == "map"

    /** A message when [newName] is already taken where [symbol] lives. */
    private fun collision(): String? {
        val declarations = analysis.index.declarations
        return when (symbol) {
            is Symbol.Declaration -> {
                val parent = symbol.name.path.dropLast(1)
                val sibling = QualifiedName(symbol.name.namespace, parent + newName)
                val scope =
                    if (parent.isEmpty()) symbol.name.namespace
                    else QualifiedName(symbol.name.namespace, parent).toString()
                if (sibling in declarations) "'$newName' is already declared in $scope" else null
            }
            is Symbol.Field -> {
                val record = declarations[symbol.owner]?.decl as? RecordDecl
                if (record?.fields?.any { it.name == newName } == true)
                    "'$newName' is already a field of ${symbol.owner}"
                else null
            }
            is Symbol.EnumValue -> {
                val enum = declarations[symbol.owner]?.decl as? EnumDecl
                if (enum?.values?.any { it.name == newName } == true)
                    "'$newName' is already a value of ${symbol.owner}"
                else null
            }
            is Symbol.ImportAlias -> {
                val file = analysis.files.firstOrNull { it.path == symbol.file }
                if (file?.imports?.any { it.alias == newName } == true)
                    "'$newName' is already an import alias in this file"
                else null
            }
            is Symbol.Namespace -> null
        }
    }

    /**
     * The resolver records only the names it looks up, and a type expression with an error of its
     * own (`map<Strng, Customer>`, `list<Customer, int32>`, `Customer<int32>`) can stop before it
     * reaches the old name. A segment that spells the old name and has no site would be left behind
     * by the edits.
     */
    private fun leftBehind(): String? {
        val (oldName, onlyIn) =
            when (symbol) {
                is Symbol.Declaration -> symbol.name.path.last() to null
                is Symbol.ImportAlias -> symbol.alias to symbol.file
                else -> return null
            }
        for (file in analysis.files.sortedBy { it.path }) {
            if (onlyIn != null && file.path != onlyIn) continue
            for (type in typesIn(file)) {
                val segments = type.name.split('.').zip(type.nameSegments)
                val candidates =
                    if (symbol is Symbol.ImportAlias) segments.take(1).filter { segments.size > 1 }
                    else segments
                val missed =
                    candidates.firstOrNull { (text, span) ->
                        text == oldName && !analysis.index.covers(span)
                    }
                if (missed != null) {
                    return "fix the type at ${fileName(file.path)}:${missed.second.startLine} before renaming"
                }
            }
        }
        return null
    }

    /**
     * Applies [edits] to the snapshots in memory and analyses the result. A rename replaces
     * identifiers in place, so the sites before and after, each sorted by position, correspond one
     * to one: the rename is safe when every site keeps its kind and still names the symbol defined
     * at the same place, and no file gains an error.
     */
    private fun simulate(edits: Map<String, List<TextEdit>>): String? {
        val introducesErrors = "renaming to '$newName' would introduce errors"
        val parsed =
            analysis.members.mapNotNull { member ->
                val snapshot = member.snapshot ?: return@mapNotNull null
                val text = apply(snapshot, edits[member.path] ?: emptyList())
                Parser.parse(text, member.path)
            }
        val files = parsed.map { it.file ?: return introducesErrors }
        val (diagnostics, recorded) = workspace.analyzeApart(files)
        val before = errorCounts(analysis.diagnostics.values.flatten())
        val after = errorCounts(parsed.flatMap { it.diagnostics } + diagnostics)
        if (after.any { (key, count) -> count > (before[key] ?: 0) }) return introducesErrors
        if (shape(analysis.index) != shape(IndexBuilder.build(files, recorded))) {
            return "renaming to '$newName' would change what other names refer to"
        }
        return null
    }

    private fun errorCounts(diagnostics: List<io.schemata.lang.Diagnostic>) =
        diagnostics
            .filter { it.severity == Severity.ERROR }
            .groupingBy { it.span.file to it.code.id }
            .eachCount()

    /**
     * One entry per site in position order: whether it defines, and where its symbol is defined.
     */
    private data class Shape(val definition: Boolean, val definedAt: Int?)

    private fun shape(index: ReferenceIndex): List<Shape> {
        val sites =
            index.sites
                .distinct()
                .sortedWith(
                    compareBy(
                        { it.span.file },
                        { it.span.startLine },
                        { it.span.startColumn },
                        { !it.definition },
                        { it.span.endLine },
                        { it.span.endColumn },
                    )
                )
        val definedAt = mutableMapOf<Symbol, Int>()
        sites.forEachIndexed { i, site ->
            if (site.definition) definedAt.putIfAbsent(site.symbol, i)
        }
        return sites.map { Shape(it.definition, definedAt[it.symbol]) }
    }

    private fun apply(snapshot: Snapshot, edits: List<TextEdit>): String {
        val lines = snapshot.lines
        return edits
            .sortedByDescending { lines.offset(it.range.start) }
            .fold(snapshot.text) { text, edit ->
                text.replaceRange(
                    lines.offset(edit.range.start),
                    lines.offset(edit.range.end),
                    edit.newText,
                )
            }
    }

    /** The first file the edits touch that is not open and no longer holds the analysed text. */
    private fun changedOnDisk(paths: Set<String>): String? =
        paths.sorted().firstOrNull { path ->
            val document = workspace.document(path)
            document != null && !document.open && read(path) != analysis.snapshot(path)?.text
        }

    private fun read(path: String): String? =
        try {
            Files.readString(Paths.get(path))
        } catch (e: IOException) {
            null
        }
}

internal fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

/** Every type expression written in [file], generic arguments included. */
internal fun typesIn(file: SourceFile): List<TypeExpr> {
    val types = mutableListOf<TypeExpr>()
    fun add(type: TypeExpr) {
        types += type
        type.args.forEach(::add)
    }
    fun visit(decl: Declaration) {
        when (decl) {
            is RecordDecl -> {
                decl.fields.forEach { add(it.type) }
                decl.nested.forEach(::visit)
            }
            is UnionDecl -> decl.members.forEach { add(it.type) }
            is AliasDecl -> add(decl.type)
            is EnumDecl -> Unit
        }
    }
    file.declarations.forEach(::visit)
    return types
}

/** Whether some site of the index covers the first character of [span]. */
internal fun ReferenceIndex.covers(span: Span): Boolean =
    sites.any { it.span.file == span.file && it.span.contains(span.startLine, span.startColumn, 0) }
