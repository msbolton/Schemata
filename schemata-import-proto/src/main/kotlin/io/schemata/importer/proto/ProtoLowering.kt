package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.Imported
import io.schemata.importer.NoteText
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnionMember
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitField
import io.schemata.importer.UnitOperation
import io.schemata.importer.UnitPayload
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitReserved
import io.schemata.importer.UnitService
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.target.Names
import java.math.BigDecimal

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
     * `namespace` line, and [imports] the files each one's `import` statements resolved to; all
     * three are keyed by [ProtoFile.path], since a file's tree is too large to hash and compare on
     * every lookup.
     */
    fun lower(
        files: List<ProtoFile>,
        namespaces: Map<String, String>,
        symbols: ProtoSymbols,
        annotations: Map<String, List<UnitAnnotation>> = emptyMap(),
        imports: Map<String, List<ProtoFile>> = emptyMap(),
    ): Imported {
        val context = Context(namespaces, symbols)
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
                        services += lowering.services()
                        imports[file.path].orEmpty().forEach {
                            unitImports += namespaces.getValue(it.path)
                        }
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
private fun typeName(protoName: String): String = ImportNames.upperCamel(protoName)

private fun List<ProtoOption>.flag(name: String): Boolean = any {
    it.name == name && it.value == "true"
}

private fun nameAnnotation(name: String, original: String): List<UnitAnnotation> =
    if (name == original) emptyList()
    else listOf(UnitAnnotation("proto", "name", SchemataText.string(original)))

/** The leading comment, then the trailing one after a blank line. */
private fun doc(doc: String?, trailing: String?): String? {
    val after = trailing?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        doc == null -> after
        after == null -> doc
        else -> "$doc\n\n$after"
    }
}

/** Who took a declaration name in a scope first, for the collision message. */
private data class Claim(val path: String, val kind: String, val protoName: String)

/** What every file's lowering reads about the whole input set. */
private class Context(val namespaces: Map<String, String>, val symbols: ProtoSymbols) {
    /** Each symbol's path from its namespace's root in Schemata: each proto name upper-camelled. */
    val paths: Map<String, List<String>> =
        symbols.all.associate { it.fullName to it.path.map(::typeName) }

    /**
     * Every declaration path each namespace holds, for spelling references as Schemata reads them.
     */
    val declared: Map<String, Set<List<String>>> =
        symbols.all
            .groupBy({ namespaces.getValue(it.file.path) }, { paths.getValue(it.fullName) })
            .mapValues { it.value.toSet() }

    private val enums = HashMap<String, EnumLowering>()

    fun enumOf(symbol: Symbol): EnumLowering =
        enums.getOrPut(symbol.fullName) { EnumLowering(symbol.enum!!, symbol.file) }
}

private fun diagnostic(
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
private class EnumLowering(e: ProtoEnum, file: ProtoFile) {
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

/**
 * A field's type as lowered: [type] is null when the field is left out (the reason is among
 * [notes]); [symbol] is the message or enum the type (or its element or value) names; [nullable] is
 * the field's own `?`.
 */
private class FieldType(
    val type: UnitType?,
    val nullable: Boolean,
    val default: String?,
    val symbol: Symbol?,
    val notes: List<Diagnostic>,
    val noteNullable: Boolean = false,
)

/** One value type: a scalar, a well-known type, or a reference. */
private class Single(val type: UnitType, val nullable: Boolean, val symbol: Symbol?)

/**
 * The ordinals a service's operations have taken so far, beside its [reserved] ranges, which none
 * may take: Schemata refuses two operations of one ordinal and an operation on a reserved one.
 */
private class RpcOrdinals(private val reserved: List<IntRange>) {
    private val used = mutableSetOf<Int>()

    private fun free(n: Int): Boolean = n !in used && reserved.none { n in it }

    /** [wanted] when it is free, else the next free ordinal above it; either is now taken. */
    fun claim(wanted: Int): Int {
        var n = maxOf(wanted, 1)
        while (!free(n)) n++
        used += n
        return n
    }
}

private class FileLowering(
    val file: ProtoFile,
    val namespace: String,
    val context: Context,
    val diagnostics: MutableList<Diagnostic>,
    val topLevel: MutableMap<String, Claim>,
) {
    /** Namespaces other than this one that a reference named. */
    val referenced = LinkedHashSet<String>()

    private fun report(
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
     * The file's services in file order, each named in the namespace's top-level scope beside the
     * messages and enums. A service keeps its name as a message does; its `// schemata: reserved`
     * lines become its `reserved` statement.
     */
    fun services(): List<UnitService> =
        file.services.mapNotNull { s ->
            val name = typeName(s.name)
            if (!claim(topLevel, name, "service", "service", s.name, s.pos)) return@mapNotNull null
            val where = "service '${s.name}'"
            // Read before the rpcs, whose ordinals must avoid the reserved ones, but reported
            // after them, where the notes stand.
            val notes = s.reservedNotes.map { (text, _) -> NoteText.parseReservedNote(text) }
            val reserved = notes.flatMap { it.orEmpty() }
            val ordinals =
                RpcOrdinals(
                    reserved.filterIsInstance<UnitReserved.Ordinals>().map { it.from..it.to }
                )
            val claimed = mutableMapOf<String, String>()
            val operations = mutableListOf<UnitOperation>()
            s.rpcs.forEach { rpc ->
                operation(rpc, where, claimed, operations.size + 1, ordinals)?.let {
                    operations += it
                }
            }
            s.reservedNotes.zip(notes).forEach { (note, parsed) ->
                if (parsed == null) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "$where: note '${note.first}' cannot be read; ignored",
                        note.second,
                    )
                }
            }
            s.strayNotes.forEach { (text, pos) ->
                report(
                    ImportCodes.APPROXIMATED,
                    "$where: note '$text' stands after the last rpc; ignored",
                    pos,
                )
            }
            UnitService(
                name = name,
                operations = operations,
                doc = s.doc,
                annotations = nameAnnotation(name, s.name),
                reserved = reserved,
                deprecated = s.options.flag("deprecated"),
            )
        }

    /**
     * An rpc as an operation, or null when it is dropped: a payload must be a message, or
     * `google.protobuf.Empty` for none. The target writes the UpperCamel form of the operation's
     * name, so a name that form does not give back keeps its proto spelling in `@proto(name)`. A
     * note's ordinal wins; otherwise the operation takes [position], its place among the rpcs kept.
     * An ordinal [ordinals] already holds is replaced by the next free one.
     */
    private fun operation(
        rpc: ProtoRpc,
        service: String,
        claimed: MutableMap<String, String>,
        position: Int,
        ordinals: RpcOrdinals,
    ): UnitOperation? {
        val where = "$service: rpc '${rpc.name}'"
        val scope = context.symbols.scopeOf(file)
        val resolved =
            listOf("request" to rpc.request, "response" to rpc.response).map { (role, t) ->
                fun drop(reason: String): Nothing? {
                    report(ImportCodes.DROPPED, "$where: $role $reason; rpc dropped", rpc.pos)
                    return null
                }
                val full = t.name.removePrefix(".")
                if (full == EMPTY) {
                    if (t.stream) return drop("stream of $EMPTY has no Schemata form")
                    return@map null
                }
                // A well-known type is a message, but Schemata reads it as a scalar or not at all,
                // never as a record an operation could carry.
                if (full.startsWith("google.protobuf.")) {
                    return drop("type '$full' has no Schemata model")
                }
                val symbol = context.symbols.resolve(t.name, scope)
                if (symbol?.message == null) return drop("type '${t.name}' is not a message")
                t to symbol
            }
        val name = ImportNames.lowerSnake(rpc.name)
        val other = claimed.putIfAbsent(name, rpc.name)
        if (other != null) {
            report(
                ImportCodes.UNRESOLVED,
                "$where and rpc '$other' both lower to '$name'",
                rpc.pos,
                ImportCodes.RENAME_HELP,
            )
            return null
        }
        val annotations =
            if (Names.upperCamel(name) == rpc.name) emptyList()
            else {
                report(ImportCodes.RENAMED, "$where: renamed to '$name'", rpc.pos, RPC_RENAME_HELP)
                listOf(UnitAnnotation("proto", "name", SchemataText.string(rpc.name)))
            }
        val note =
            rpc.note?.let { text ->
                NoteText.parseOperationNote(text)
                    ?: null.also {
                        report(
                            ImportCodes.APPROXIMATED,
                            "$where: note '$text' cannot be read; ignored",
                            rpc.pos,
                        )
                    }
            }
        val (request, response) =
            resolved.map { r ->
                r?.let { (t, symbol) -> UnitPayload(reference(symbol, emptyList()), t.stream) }
            }
        val wanted = note?.ordinal ?: position
        val ordinal = ordinals.claim(wanted)
        if (ordinal != wanted) {
            report(
                ImportCodes.APPROXIMATED,
                "$where: ordinal #$wanted is already used; the next free ordinal is taken",
                rpc.pos,
            )
        }
        return UnitOperation(
            name = name,
            request = request,
            response = response,
            binding = note?.binding,
            doc = rpc.doc,
            annotations = annotations,
            ordinal = ordinal,
            deprecated = rpc.options.flag("deprecated"),
        )
    }

    /**
     * Messages and enums declared side by side, in the order they were written. [scope] is the
     * proto scope they sit in; [enclosing] the Schemata path of the record holding them.
     */
    private fun nested(
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
                    is ProtoMessage -> message(d, scope, enclosing, claims)
                    is ProtoEnum -> enum(d, scope, claims)
                    else -> null
                }
            }

    /** False, with the collision reported, when an earlier declaration took [name] in [claims]. */
    private fun claim(
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
     * A record, or a union when the message is exactly one oneof whose members lower to distinct
     * types, which is how the target writes a union.
     */
    private fun message(
        m: ProtoMessage,
        scope: List<String>,
        enclosing: List<String>,
        claims: MutableMap<String, Claim>,
    ): UnitDecl? {
        val name = typeName(m.name)
        val here = scope + m.name
        val path = enclosing + name
        val mapped =
            m.fields.map { it to fieldType(it, here, path, "field '${m.name}.${it.name}'") }
        val types = mapped.map { it.second.type }
        val isUnion =
            m.oneofs.size == 1 &&
                m.fields.isNotEmpty() &&
                m.fields.all { it.oneof != null } &&
                m.messages.isEmpty() &&
                m.enums.isEmpty() &&
                m.reserved.isEmpty() &&
                types.all { it != null } &&
                types.distinct().size == types.size
        val kind = if (isUnion) "union" else "model"
        if (!claim(claims, name, kind, "message", m.name, m.pos)) return null
        val decl = if (isUnion) union(m, name, mapped) else record(m, name, here, path, mapped)
        m.dropped.forEach { (what, pos) ->
            report(ImportCodes.DROPPED, "message '${m.name}': $what dropped", pos)
        }
        return decl
    }

    private fun union(
        m: ProtoMessage,
        name: String,
        mapped: List<Pair<ProtoField, FieldType>>,
    ): UnitUnion {
        val oneof = m.oneofs.single()
        if (oneof != "kind") {
            report(
                ImportCodes.APPROXIMATED,
                "union '${m.name}': oneof '$oneof' is named 'kind' in the regenerated message",
                m.pos,
            )
        }
        val members =
            mapped.map { (f, t) ->
                val where = "field '${m.name}.${f.name}'"
                diagnostics += t.notes
                val type = t.type!!
                val stem = stem(type, t.symbol)
                if (f.name != stem) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "union '${m.name}': member element '${f.name}' has no Schemata " +
                            "equivalent; the regenerated oneof names it '$stem'",
                        f.pos,
                    )
                }
                fieldOptions(f, where)
                if (f.options.flag("deprecated")) {
                    report(
                        ImportCodes.DROPPED,
                        "$where: deprecated dropped; a union member cannot be deprecated",
                        f.pos,
                    )
                }
                // The target never marks a member nullable and Schemata refuses it, so a `?` in
                // a hand-written note cannot be kept.
                if (t.noteNullable) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "$where: note '${f.note}' marks the member nullable; the '?' is dropped, " +
                            "a union member cannot be nullable",
                        f.pos,
                    )
                }
                if (t.default != null) {
                    report(
                        ImportCodes.DROPPED,
                        "$where: default dropped; a union member has none",
                        f.pos,
                    )
                }
                UnionMember(type, doc(f.doc, f.trailing), f.number)
            }
        return UnitUnion(
            name = name,
            members = members,
            doc = m.doc,
            annotations = nameAnnotation(name, m.name),
            deprecated = m.options.flag("deprecated"),
        )
    }

    /**
     * The name the target gives a union member of [type]: a scalar's builtin name, else the
     * declaration's `@proto(name)` (here, its proto name when Schemata renamed it) or the snake
     * case of its name.
     */
    private fun stem(type: UnitType, symbol: Symbol?): String {
        if (type is UnitType.Scalar) return type.builtin
        val proto = symbol!!.path.last()
        return if (typeName(proto) != proto) proto else Names.snakeCase(proto)
    }

    private fun record(
        m: ProtoMessage,
        name: String,
        here: List<String>,
        path: List<String>,
        mapped: List<Pair<ProtoField, FieldType>>,
    ): UnitRecord {
        val fields = mutableListOf<UnitField>()
        val claimed = mutableMapOf<String, String>()
        val oneofs = mutableSetOf<String>()
        mapped.forEach { (f, t) ->
            val where = "field '${m.name}.${f.name}'"
            f.oneof?.let { o ->
                if (oneofs.add(o)) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "model '${m.name}': oneof '$o' imported as nullable fields; at most one " +
                            "of them is set, which Schemata cannot say",
                        f.pos,
                    )
                }
            }
            diagnostics += t.notes
            val type = t.type ?: return@forEach
            val fieldName =
                if (ImportNames.isLowerSnake(f.name)) f.name else ImportNames.lowerSnake(f.name)
            val other = claimed.putIfAbsent(fieldName, f.name)
            if (other != null) {
                report(
                    ImportCodes.UNRESOLVED,
                    "field '${m.name}.${f.name}' and field '${m.name}.$other' both lower to " +
                        "'$fieldName'",
                    f.pos,
                    ImportCodes.RENAME_HELP,
                )
                return@forEach
            }
            fieldOptions(f, where)
            fields +=
                UnitField(
                    name = fieldName,
                    type = type,
                    nullable = t.nullable,
                    default = t.default,
                    doc = doc(f.doc, f.trailing),
                    annotations = nameAnnotation(fieldName, f.name),
                    ordinal = f.number,
                    deprecated = f.options.flag("deprecated"),
                )
        }
        val nested = nested(m.messages, m.enums, here, path, mutableMapOf())
        val reserved = mutableListOf<UnitReserved>()
        m.reserved.forEach { r ->
            r.ranges.forEach { (from, to) -> reserved += UnitReserved.Ordinals(from, to) }
        }
        m.reserved.forEach { r ->
            r.names.forEach { original ->
                val reservedName =
                    if (ImportNames.isLowerSnake(original)) original
                    else ImportNames.lowerSnake(original)
                if (reservedName != original) {
                    report(
                        ImportCodes.APPROXIMATED,
                        "model '${m.name}': reserved name '$original' imported as '$reservedName'",
                        r.pos,
                    )
                }
                reserved += UnitReserved.Name(reservedName)
            }
        }
        return UnitRecord(
            name = name,
            fields = fields,
            nested = nested,
            doc = m.doc,
            annotations = nameAnnotation(name, m.name),
            reserved = reserved,
            deprecated = m.options.flag("deprecated"),
        )
    }

    /** `json_name` steers JSON field names, which Schemata derives itself. */
    private fun fieldOptions(f: ProtoField, where: String) {
        if (f.options.any { it.name == "json_name" }) {
            report(ImportCodes.DROPPED, "$where: json_name dropped", f.pos)
        }
    }

    /**
     * A field's type: the proto type, then its `schemata:` note when it fits, then a proto2
     * `[default]`. [scope] is the proto scope its type name resolves in; [enclosing] the Schemata
     * path of its record. Diagnostics are returned, not reported, so a message can be lowered as a
     * union or a record after its member types are known.
     */
    private fun fieldType(
        f: ProtoField,
        scope: List<String>,
        enclosing: List<String>,
        where: String,
    ): FieldType {
        val notes = mutableListOf<Diagnostic>()
        fun note(code: DiagnosticCode, message: String) {
            notes += diagnostic(file, code, message, f.pos)
        }
        val base: Single? =
            when {
                f.type == "map" -> map(f, scope, enclosing, where, ::note)
                f.label == Label.REPEATED ->
                    single(f.type, scope, enclosing, where, ::note)?.let {
                        Single(UnitType.ListOf(it.type, it.nullable, emptyList()), false, it.symbol)
                    }
                else -> single(f.type, scope, enclosing, where, ::note)
            }
        if (base == null) return FieldType(null, false, null, null, notes)
        var type = base.type
        var nullable = base.nullable || f.label == Label.OPTIONAL || f.oneof != null
        var noteNullable = false
        var default: String? = null
        f.note?.let { text ->
            val parsed = NoteText.parse(text)
            val noteType = parsed?.type
            when {
                parsed == null ->
                    note(ImportCodes.APPROXIMATED, "$where: note '$text' cannot be read; ignored")
                noteType != null && !fits(noteType, type, base.symbol) ->
                    note(
                        ImportCodes.APPROXIMATED,
                        "$where: note '$text' does not fit ${protoTypeText(f)}; ignored",
                    )
                // A default alone has no type of its own: it must be a literal the lowered type
                // takes, as the language's default check would otherwise refuse it.
                noteType == null && !defaultFits(parsed.default!!, type, base.symbol) ->
                    note(
                        ImportCodes.APPROXIMATED,
                        "$where: note '$text' does not fit ${protoTypeText(f)}; ignored",
                    )
                else -> {
                    parsed.type?.let {
                        type = merge(it, type)
                        nullable = parsed.nullable || f.oneof != null
                        noteNullable = parsed.nullable
                    }
                    default =
                        parsed.default?.let { literal ->
                            if (base.symbol?.enum == null) literal
                            else
                                context.enumOf(base.symbol).names[literal]
                                    ?: null.also {
                                        note(
                                            ImportCodes.APPROXIMATED,
                                            "$where: default $literal names no value of enum " +
                                                "'${base.symbol.enum!!.name}'; dropped",
                                        )
                                    }
                        }
                }
            }
        }
        f.options
            .firstOrNull { it.name == "default" }
            ?.let { option ->
                if (default != null) return@let
                val literal = protoDefault(option.value, type, base.symbol)
                if (literal == null) {
                    note(
                        ImportCodes.APPROXIMATED,
                        "$where: default ${option.value} has no Schemata literal; dropped",
                    )
                } else {
                    default = literal
                    if (f.label == Label.OPTIONAL && f.note == null) nullable = false
                }
            }
        return FieldType(type, nullable, default, base.symbol, notes, noteNullable)
    }

    /**
     * Whether [literal], a note's default as Schemata source, is one the language takes for [type]:
     * `true` or `false` for a bool, an integer for an integer, an integer or a decimal number for a
     * float or decimal, a quoted string for a string, an enum's value name for an enum (the lookup
     * in the enum's lowering checks the name); no other type has a default.
     */
    private fun defaultFits(literal: String, type: UnitType, symbol: Symbol?): Boolean =
        when {
            type is UnitType.Ref -> symbol?.enum != null
            type !is UnitType.Scalar -> false
            else ->
                when (type.builtin) {
                    "bool" -> literal == "true" || literal == "false"
                    "int32",
                    "int64" -> INTEGER.matches(literal)
                    "float32",
                    "float64",
                    "decimal" -> INTEGER.matches(literal) || DECIMAL.matches(literal)
                    "string" -> literal.startsWith("\"")
                    else -> false
                }
        }

    private fun protoTypeText(f: ProtoField): String =
        when {
            f.type == "map" -> "map<${f.mapKey}, ${f.mapValue}>"
            f.label == Label.REPEATED -> "repeated ${f.type}"
            else -> f.type
        }

    /**
     * A proto2 `[default]` as a Schemata literal, or null when Schemata has none for it: a string
     * is quoted, a number kept in decimal, an enum value named as the enum's lowering names it.
     */
    private fun protoDefault(value: String, type: UnitType, symbol: Symbol?): String? {
        if (symbol?.enum != null) return context.enumOf(symbol).names[value]
        if (type !is UnitType.Scalar) return null
        return when (type.builtin) {
            "string" -> SchemataText.string(value)
            "bool" -> value.takeIf { it == "true" || it == "false" }
            "int32",
            "int64" -> protoInteger(value)?.toString()
            "float32",
            "float64" ->
                value.toBigDecimalOrNull()?.toPlainString()?.let { if ('.' in it) it else "$it.0" }
            else -> null
        }
    }

    /** Decimal, `0x` hex, or leading-zero octal, signed, as proto writes an integer constant. */
    private fun protoInteger(text: String): Long? {
        val negative = text.startsWith("-")
        val digits = text.removePrefix("-").removePrefix("+")
        val magnitude =
            when {
                digits.startsWith("0x") || digits.startsWith("0X") ->
                    digits.substring(2).toLongOrNull(16)
                digits.length > 1 && digits.startsWith("0") -> digits.toLongOrNull(8)
                else -> digits.toLongOrNull()
            } ?: return null
        return if (negative) -magnitude else magnitude
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        try {
            BigDecimal(this)
        } catch (_: NumberFormatException) {
            null
        }

    /**
     * Whether a note's type can stand for what the proto type lowered to: a proto `string` carries
     * `string`, `uuid`, `decimal`, `date`, or `time`; every other scalar carries itself; a
     * reference carries the declaration of that simple name; collections compare element by
     * element.
     */
    private fun fits(note: UnitType, lowered: UnitType, symbol: Symbol?): Boolean =
        when {
            note is UnitType.Scalar && lowered is UnitType.Scalar ->
                if (lowered.builtin == "string") note.builtin in stringCarried
                else note.builtin == lowered.builtin
            note is UnitType.Ref && lowered is UnitType.Ref ->
                symbol != null &&
                    note.name.substringAfterLast('.').let {
                        it == symbol.path.last() || it == typeName(symbol.path.last())
                    }
            note is UnitType.ListOf && lowered is UnitType.ListOf ->
                fits(note.element, lowered.element, symbol)
            note is UnitType.MapOf && lowered is UnitType.MapOf ->
                fits(note.key, lowered.key, null) && fits(note.value, lowered.value, symbol)
            else -> false
        }

    /** The note's scalars, refinements, and nullability, around the proto type's references. */
    private fun merge(note: UnitType, lowered: UnitType): UnitType =
        when {
            note is UnitType.ListOf && lowered is UnitType.ListOf ->
                UnitType.ListOf(
                    merge(note.element, lowered.element),
                    note.nullableElement,
                    note.refinements,
                )
            note is UnitType.MapOf && lowered is UnitType.MapOf ->
                UnitType.MapOf(
                    merge(note.key, lowered.key),
                    merge(note.value, lowered.value),
                    note.nullableValue,
                    note.refinements,
                )
            note is UnitType.Scalar -> note
            else -> lowered
        }

    private fun map(
        f: ProtoField,
        scope: List<String>,
        enclosing: List<String>,
        where: String,
        note: (DiagnosticCode, String) -> Unit,
    ): Single? {
        val keyName = f.mapKey!!
        val key =
            when (keyName) {
                "string",
                "int32",
                "int64" -> UnitType.Scalar(keyName, emptyList())
                "sint32",
                "sfixed32",
                "sint64",
                "sfixed64" -> {
                    val wide = if (keyName.endsWith("32")) "int32" else "int64"
                    note(ImportCodes.WIDENED, "$where: $keyName map key imported as $wide")
                    UnitType.Scalar(wide, emptyList())
                }
                "uint32",
                "fixed32",
                "uint64",
                "fixed64" -> {
                    note(ImportCodes.WIDENED, "$where: $keyName map key imported as int64")
                    UnitType.Scalar("int64", emptyList())
                }
                "bool" -> {
                    note(ImportCodes.DROPPED, "$where: map with a bool key dropped")
                    return null
                }
                else -> single(keyName, scope, enclosing, where, note)?.type ?: return null
            }
        val value = single(f.mapValue!!, scope, enclosing, where, note) ?: return null
        return Single(
            UnitType.MapOf(key, value.type, value.nullable, emptyList()),
            false,
            value.symbol,
        )
    }

    /**
     * A scalar, a well-known type, or a message or enum the symbol table resolves; any other
     * `google.protobuf` type, whose file need not be among the inputs, is a string.
     */
    private fun single(
        name: String,
        scope: List<String>,
        enclosing: List<String>,
        where: String,
        note: (DiagnosticCode, String) -> Unit,
    ): Single? {
        scalar(name, where, note)?.let {
            return Single(it, false, null)
        }
        wellKnown(name, where, note)?.let {
            return it
        }
        val symbol = context.symbols.resolve(name, scope)
        if (symbol == null) {
            val full = name.removePrefix(".")
            if (full.startsWith("google.protobuf.")) {
                note(ImportCodes.WIDENED, "$where: $full imported as string")
                return Single(UnitType.Scalar("string", emptyList()), false, null)
            }
            note(ImportCodes.UNRESOLVED, "$where: type '$name' cannot be resolved")
            return null
        }
        return Single(reference(symbol, enclosing), false, symbol)
    }

    private fun scalar(
        name: String,
        where: String,
        note: (DiagnosticCode, String) -> Unit,
    ): UnitType? {
        fun widened(type: UnitType.Scalar, suffix: String = ""): UnitType.Scalar {
            note(ImportCodes.WIDENED, "$where: $name imported as ${text(type)}$suffix")
            return type
        }
        return when (name) {
            "double" -> UnitType.Scalar("float64", emptyList())
            "float" -> UnitType.Scalar("float32", emptyList())
            "int32",
            "int64",
            "bool",
            "string",
            "bytes" -> UnitType.Scalar(name, emptyList())
            "sint32",
            "sfixed32" -> widened(UnitType.Scalar("int32", emptyList()))
            "sint64",
            "sfixed64" -> widened(UnitType.Scalar("int64", emptyList()))
            "uint32",
            "fixed32" -> widened(UINT32)
            "uint64",
            "fixed64" -> widened(UINT64, "; the top bit is lost")
            else -> null
        }
    }

    /**
     * `google.protobuf` types known without their files: a timestamp and a duration are Schemata
     * scalars; a wrapper is its scalar, nullable; the rest have no Schemata shape.
     */
    private fun wellKnown(
        name: String,
        where: String,
        note: (DiagnosticCode, String) -> Unit,
    ): Single? {
        val simple = name.removePrefix(".").removePrefix("google.protobuf.")
        if (simple == name.removePrefix(".")) return null
        val full = "google.protobuf.$simple"
        return when (simple) {
            "Timestamp" -> Single(UnitType.Scalar("instant", emptyList()), false, null)
            "Duration" -> Single(UnitType.Scalar("duration", emptyList()), false, null)
            in wrappers -> {
                val type = wrappers.getValue(simple)
                note(
                    ImportCodes.APPROXIMATED,
                    "$where: $full imported as ${text(type)}?; the regenerated field is optional, " +
                        "not a wrapper",
                )
                Single(type, true, null)
            }
            "Any" -> {
                note(ImportCodes.WIDENED, "$where: $full imported as bytes")
                Single(UnitType.Scalar("bytes", emptyList()), false, null)
            }
            "Struct",
            "Value",
            "ListValue",
            "FieldMask",
            "Empty" -> {
                note(ImportCodes.WIDENED, "$where: $full imported as string")
                Single(UnitType.Scalar("string", emptyList()), false, null)
            }
            else -> null
        }
    }

    /**
     * [symbol] spelled as Schemata resolves it from inside the record at [enclosing]: a type of
     * another namespace in full (and imported); one of this namespace by the shortest tail of its
     * path that the lookup, innermost record first, finds, else in full.
     */
    private fun reference(symbol: Symbol, enclosing: List<String>): UnitType.Ref {
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

    private companion object {
        const val EMPTY = "google.protobuf.Empty"
        val INTEGER = Regex("-?[0-9]+")
        val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        const val RPC_RENAME_HELP = "keep @proto(name) so the regenerated rpc keeps its proto name"
        val UINT32 = UnitType.Scalar("int64", listOf("min" to "0", "max" to "4294967295"))
        val UINT64 = UnitType.Scalar("int64", listOf("min" to "0"))
        val stringCarried = setOf("string", "uuid", "decimal", "date", "time")
        val wrappers =
            mapOf(
                "DoubleValue" to UnitType.Scalar("float64", emptyList()),
                "FloatValue" to UnitType.Scalar("float32", emptyList()),
                "Int64Value" to UnitType.Scalar("int64", emptyList()),
                "UInt64Value" to UINT64,
                "Int32Value" to UnitType.Scalar("int32", emptyList()),
                "UInt32Value" to UINT32,
                "BoolValue" to UnitType.Scalar("bool", emptyList()),
                "StringValue" to UnitType.Scalar("string", emptyList()),
                "BytesValue" to UnitType.Scalar("bytes", emptyList()),
            )

        fun text(t: UnitType.Scalar): String =
            if (t.refinements.isEmpty()) t.builtin
            else
                t.builtin +
                    t.refinements.joinToString(", ", "(", ")") { "${it.first} = ${it.second}" }
    }
}
