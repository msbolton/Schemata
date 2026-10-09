package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.Imported
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitReserved
import io.schemata.importer.UnitService
import io.schemata.importer.UnitType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.target.Names

/**
 * Lowers read `.proto` files to Schemata units, inverting what the Protobuf target writes: enum
 * value prefixes and the synthesised zero value, a union's wrapper message with its single oneof,
 * `google.protobuf.Timestamp` and `Duration`, rpc names and `google.protobuf.Empty` payloads, and
 * the `// schemata:` notes that carry what proto cannot say. Files that lower to one namespace
 * merge into one unit, in file order.
 */
internal object ProtoLowering {
    /**
     * [namespaces] names each file's namespace, [annotations] what its unit carries above the
     * `namespace` line; both are keyed by [ProtoFile.path], since a file's tree is too large to
     * hash and compare on every lookup. A unit imports exactly the namespaces its declarations
     * reference, in the order they are first used: a Schemata file imports what it uses, so a proto
     * import whose types it never uses, an option-only import and an `import public` produce no
     * line, which is what makes the Protobuf round trip exact.
     */
    fun lower(
        files: List<ProtoFile>,
        namespaces: Map<String, String>,
        symbols: ProtoSymbols,
        annotations: Map<String, List<UnitAnnotation>> = emptyMap(),
        sourceNames: Map<String, String> = emptyMap(),
        unresolvedImports: Map<String, List<String>> = emptyMap(),
    ): Imported {
        val context = Context(namespaces, symbols, sourceNames, unresolvedImports)
        val diagnostics = mutableListOf<Diagnostic>()
        val units =
            files
                .groupBy { namespaces.getValue(it.path) }
                .map { (namespace, group) ->
                    val topLevel = mutableMapOf<String, Claim>()
                    val unitImports = LinkedHashSet<String>()
                    val declarations = mutableListOf<UnitDecl>()
                    val services = mutableListOf<UnitService>()
                    group.forEach { file ->
                        val lowering = FileLowering(file, namespace, context, diagnostics, topLevel)
                        declarations += lowering.declarations()
                        services += lowering.serviceLowering.services()
                        unitImports += lowering.referenced
                    }
                    unitImports -= namespace
                    SchemataUnit(
                        namespace = namespace,
                        annotations = annotations[group.first().path].orEmpty(),
                        doc = null,
                        imports = unitImports.toList(),
                        declarations = declarations,
                        services = services,
                        sourcePath = group.first().path,
                    )
                }
        return Imported(units, diagnostics)
    }
}

/** A Schemata type name for a proto message or enum name: upper camel case. */
internal fun typeName(protoName: String): String = ImportNames.upperCamel(protoName)

internal fun List<ProtoOption>.flag(name: String): Boolean = any {
    it.name == name && it.value == "true"
}

internal fun nameAnnotation(name: String, original: String): List<UnitAnnotation> =
    if (name == original) emptyList()
    else listOf(UnitAnnotation("proto", "name", SchemataText.string(original)))

/** The leading comment, then the trailing one after a blank line. */
internal fun doc(doc: String?, trailing: String?): String? {
    val after = trailing?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        doc == null -> after
        after == null -> doc
        else -> "$doc\n\n$after"
    }
}

/** Who took a declaration name in a scope first, for the collision message. */
internal data class Claim(val path: String, val kind: String, val protoName: String)

/** What every file's lowering reads about the whole input set. */
internal class Context(
    val namespaces: Map<String, String>,
    val symbols: ProtoSymbols,
    /** How an import statement names each file, by [ProtoFile.path], where it has a name. */
    val sourceNames: Map<String, String> = emptyMap(),
    /** The import paths each file wrote that resolved nowhere, by [ProtoFile.path]. */
    val unresolvedImports: Map<String, List<String>> = emptyMap(),
) {
    /** Each symbol's path from its namespace's root in Schemata: each proto name upper-camelled. */
    val paths: Map<String, List<String>> =
        symbols.all.associate { it.fullName to it.path.map(::typeName) }

    /**
     * Every declaration path each namespace holds, for spelling references as Schemata reads them.
     */
    val declared: Map<String, Set<List<String>>> =
        symbols.all
            .filter { it.file.path in namespaces }
            .groupBy({ namespaces.getValue(it.file.path) }, { paths.getValue(it.fullName) })
            .mapValues { it.value.toSet() }

    private val enums = HashMap<String, EnumLowering>()

    fun enumOf(symbol: Symbol): EnumLowering =
        enums.getOrPut(symbol.fullName) { EnumLowering(symbol.enum!!, symbol.file) }
}

internal fun diagnostic(
    file: ProtoFile,
    code: DiagnosticCode,
    message: String,
    pos: Pos,
    help: String = ImportCodes.helpFor(code),
) = Diagnostic(code, message, Span(file.path, pos.line, pos.col, pos.line, pos.col), help)

/**
 * One proto enum as Schemata reads it. The target writes each value as `<UPPER_SNAKE(E)>_<VALUE>`
 * after a synthesised `<UPPER_SNAKE(E)>_UNSPECIFIED = 0`, so the prefix comes off and the zero
 * value goes when the target would write them back; a value the target would spell differently
 * keeps its proto name in `@proto(name)`. Numbers are ordinals unless one is not positive, when the
 * values are renumbered in order. A Schemata enum needs a value, so when the zero value is the only
 * one left it stays, spelled for the target beside the zero value it synthesises.
 */
internal class EnumLowering(e: ProtoEnum, file: ProtoFile) {
    private val prefix = Names.snakeCase(e.name).uppercase() + "_"
    val values = mutableListOf<UnitEnumValue>()

    /** Proto value name to Schemata value name, for the values kept. */
    val names = mutableMapOf<String, String>()
    val reserved = mutableListOf<UnitReserved>()

    /** Reported where the enum is declared, once. */
    val diagnostics = mutableListOf<Diagnostic>()

    init {
        val where = "enum '${e.name}'"
        fun report(
            code: DiagnosticCode,
            message: String,
            pos: Pos,
            help: String = ImportCodes.helpFor(code),
        ) {
            diagnostics += diagnostic(file, code, message, pos, help)
        }
        val taken = mutableMapOf<Int, String>()
        val kept = mutableListOf<ProtoEnumValue>()
        // The zero value set aside, and where its note goes if it stays set aside.
        var zero: ProtoEnumValue? = null
        var zeroNote: Pair<Int, Diagnostic>? = null
        e.values.forEach { v ->
            val first = taken.putIfAbsent(v.number, v.name)
            if (first != null) {
                report(
                    ImportCodes.DROPPED,
                    "enum value '${e.name}.${v.name}': alias of '$first' dropped",
                    v.pos,
                )
                return@forEach
            }
            if (v.number == 0 && v.name == "${prefix}UNSPECIFIED") {
                zero = v
                return@forEach
            }
            if (v.number == 0 && zeroLike.matches(v.name)) {
                zero = v
                zeroNote =
                    diagnostics.size to
                        diagnostic(
                            file,
                            ImportCodes.APPROXIMATED,
                            "$where: zero value '${v.name}' dropped; the regenerated enum names " +
                                "it '${prefix}UNSPECIFIED'",
                            v.pos,
                        )
                return@forEach
            }
            kept += v
        }
        val placeholder = zero.takeIf { kept.isEmpty() }
        if (placeholder == null) {
            zeroNote?.let { (at, note) -> diagnostics.add(at, note) }
        } else {
            val name = valueName(placeholder.name)
            val spelled =
                regenerated(name).let { if (it == "${prefix}UNSPECIFIED") "${it}_VALUE" else it }
            report(
                ImportCodes.APPROXIMATED,
                "$where: only value '${placeholder.name}' kept, as '$name'; the regenerated enum " +
                    "spells it $spelled beside the synthesized zero value",
                placeholder.pos,
            )
            names[placeholder.name] = name
            values +=
                UnitEnumValue(
                    name = name,
                    doc = doc(placeholder.doc, placeholder.trailing),
                    annotations =
                        if (spelled != regenerated(name)) nameAnnotation(name, spelled)
                        else nameAnnotation(spelled, placeholder.name),
                    deprecated = placeholder.options.flag("deprecated"),
                )
        }
        val notOrdinal = kept.firstOrNull { it.number <= 0 }
        if (notOrdinal != null) {
            report(
                ImportCodes.APPROXIMATED,
                "$where: values renumbered; ${notOrdinal.number} is not a Schemata ordinal",
                e.pos,
            )
        }
        val claimed = mutableMapOf<String, String>()
        kept.forEach { v ->
            val name = valueName(v.name)
            val other = claimed.putIfAbsent(name, v.name)
            if (other != null) {
                report(
                    ImportCodes.UNRESOLVED,
                    "enum value '${e.name}.${v.name}' and '${e.name}.$other' both lower to '$name'",
                    v.pos,
                    ImportCodes.RENAME_HELP,
                )
                return@forEach
            }
            names[v.name] = name
            values +=
                UnitEnumValue(
                    name = name,
                    doc = doc(v.doc, v.trailing),
                    annotations = nameAnnotation(regenerated(name), v.name),
                    ordinal = if (notOrdinal == null) v.number else null,
                    deprecated = v.options.flag("deprecated"),
                )
        }
        var below = false
        e.reserved.forEach { r ->
            r.ranges.forEach { (from, to) ->
                if (from < 1) below = true
                if (to >= 1) reserved += UnitReserved.Ordinals(maxOf(from, 1), to)
            }
        }
        if (below) {
            report(
                ImportCodes.APPROXIMATED,
                "$where: reserved numbers below 1 dropped; Schemata ordinals start at 1",
                e.reserved.first().pos,
            )
        }
        e.reserved.forEach { r ->
            r.names.forEach { original ->
                val name = valueName(original)
                if (regenerated(name) != original) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "$where: reserved name '$original' imported as '$name'; the regenerated " +
                            "enum reserves '${regenerated(name)}'",
                        r.pos,
                    )
                }
                reserved += UnitReserved.Name(name)
            }
        }
    }

    /**
     * `STATUS_PENDING` → `pending`: the prefix off, the rest lower-cased, lower-snaked if need be.
     */
    private fun valueName(proto: String): String {
        val rest =
            if (proto.startsWith(prefix) && proto.length > prefix.length)
                proto.substring(prefix.length)
            else proto
        val lower = rest.lowercase()
        return if (ImportNames.isLowerSnake(lower)) lower else ImportNames.lowerSnake(rest)
    }

    /** The proto name the target writes for the Schemata value [name]. */
    private fun regenerated(name: String): String = prefix + name.uppercase()

    companion object {
        /** A zero value that means "not set" under another spelling than the target's. */
        private val zeroLike = Regex("(.*_)?(UNSPECIFIED|UNKNOWN|UNSET)")
    }
}

internal class FileLowering(
    val file: ProtoFile,
    val namespace: String,
    val context: Context,
    val diagnostics: MutableList<Diagnostic>,
    val topLevel: MutableMap<String, Claim>,
) {
    val messageLowering = MessageLowering(this)
    val serviceLowering = ServiceLowering(this)

    /** Namespaces other than this one that a reference named. */
    val referenced = LinkedHashSet<String>()

    /** Imports of this file that could not be resolved. */
    val unresolvedImports: List<String> = context.unresolvedImports[file.path].orEmpty()

    /**
     * The help for a type that resolves nowhere: when this file has imports that were not found the
     * type may live in one of them, so the help names them.
     */
    fun unresolvedHelp(): String =
        when (unresolvedImports.size) {
            0 -> ImportCodes.helpFor(ImportCodes.UNRESOLVED)
            1 ->
                "import '${unresolvedImports.single()}' was not found; " +
                    "add its directory with --include"
            else ->
                "imports ${unresolvedImports.joinToString { "'$it'" }} were not found; " +
                    "add their directory with --include"
        }

    /**
     * When [name] resolves to nothing this file can see but does resolve among all the files read:
     * the message to append to the unresolved text, and its help. Protoc would reject such a file
     * for want of the import, so the text names the file that declares the type.
     */
    fun unimported(name: String, scope: List<String>): Pair<String, String>? {
        val symbol = context.symbols.resolve(name, scope, null) ?: return null
        val declared = context.sourceNames[symbol.file.path] ?: symbol.file.path
        return "'$declared' declares '${symbol.fullName}' but ${file.path} does not import it" to
            "add import \"$declared\" to ${file.path}"
    }

    internal fun report(
        code: DiagnosticCode,
        message: String,
        pos: Pos,
        help: String = ImportCodes.helpFor(code),
    ) {
        diagnostics += diagnostic(file, code, message, pos, help)
    }

    /**
     * The file's declarations in file order, after a note for an `edition` file and before the
     * constructs dropped at file level.
     */
    fun declarations(): List<UnitDecl> {
        file.edition?.let {
            report(
                ImportCodes.APPROXIMATED,
                "${file.path}: edition $it read as proto3; explicit presence assumed for optional " +
                    "fields only",
                Pos(1, 1),
            )
        }
        val out =
            nested(file.messages, file.enums, context.symbols.scopeOf(file), emptyList(), topLevel)
        file.dropped.forEach { (what, pos) ->
            report(ImportCodes.DROPPED, "${file.path}: $what dropped", pos)
        }
        return out
    }

    /**
     * Messages and enums declared side by side, in the order they were written. [scope] is the
     * proto scope they sit in; [enclosing] the Schemata path of the record holding them.
     */
    internal fun nested(
        messages: List<ProtoMessage>,
        enums: List<ProtoEnum>,
        scope: List<String>,
        enclosing: List<String>,
        claims: MutableMap<String, Claim>,
    ): List<UnitDecl> =
        (messages.map { it.pos to it } + enums.map { it.pos to it })
            .sortedWith(compareBy({ it.first.line }, { it.first.col }))
            .mapNotNull { (_, d) ->
                when (d) {
                    is ProtoMessage -> messageLowering.message(d, scope, enclosing, claims)
                    is ProtoEnum -> enum(d, scope, claims)
                    else -> null
                }
            }

    /** False, with the collision reported, when an earlier declaration took [name] in [claims]. */
    internal fun claim(
        claims: MutableMap<String, Claim>,
        name: String,
        lowered: String,
        kind: String,
        protoName: String,
        pos: Pos,
    ): Boolean {
        val previous = claims.putIfAbsent(name, Claim(file.path, kind, protoName)) ?: return true
        report(
            ImportCodes.UNRESOLVED,
            "${file.path}: $kind '$protoName' and ${previous.path}'s ${previous.kind} " +
                "'${previous.protoName}' both lower to $lowered '$name'",
            pos,
            ImportCodes.RENAME_HELP,
        )
        return false
    }

    private fun enum(
        e: ProtoEnum,
        scope: List<String>,
        claims: MutableMap<String, Claim>,
    ): UnitEnum? {
        val name = typeName(e.name)
        if (!claim(claims, name, "enum", "enum", e.name, e.pos)) return null
        val symbol = context.symbols[(scope + e.name).joinToString(".")]
        val lowering = if (symbol?.enum === e) context.enumOf(symbol) else EnumLowering(e, file)
        diagnostics += lowering.diagnostics
        return UnitEnum(
            name = name,
            values = lowering.values,
            doc = e.doc,
            annotations = nameAnnotation(name, e.name),
            reserved = lowering.reserved,
            deprecated = e.options.flag("deprecated"),
        )
    }

    /**
     * [symbol] spelled as Schemata resolves it from inside the record at [enclosing]: a type of
     * another namespace in full (and imported); one of this namespace by the shortest tail of its
     * path that the lookup, innermost record first, finds, else in full.
     */
    internal fun reference(symbol: Symbol, enclosing: List<String>): UnitType.Ref {
        val target = context.namespaces.getValue(symbol.file.path)
        val path = context.paths.getValue(symbol.fullName)
        if (target != namespace) {
            referenced += target
            return UnitType.Ref("$target.${path.joinToString(".")}")
        }
        for (k in path.size - 1 downTo 0) {
            val candidate = path.drop(k)
            if (lookup(candidate, enclosing) == path)
                return UnitType.Ref(candidate.joinToString("."))
        }
        return UnitType.Ref("$namespace.${path.joinToString(".")}")
    }

    /**
     * What Schemata finds for [name] from inside [enclosing]: its head innermost first, then top.
     */
    private fun lookup(name: List<String>, enclosing: List<String>): List<String>? {
        val declared = context.declared[namespace].orEmpty()
        val head = name.first()
        for (depth in enclosing.size downTo 1) {
            val base = enclosing.take(depth) + head
            if (base in declared) return base + name.drop(1)
        }
        return if (listOf(head) in declared) name else null
    }
}
