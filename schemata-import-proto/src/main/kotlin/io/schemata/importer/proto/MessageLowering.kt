package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.NoteText
import io.schemata.importer.UnionMember
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitReserved
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.target.Names
import java.math.BigDecimal

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

/** A sink for one field's diagnostics; [help] replaces the code's stock help when given. */
internal fun interface Note {
    fun report(code: DiagnosticCode, message: String, help: String?)

    operator fun invoke(code: DiagnosticCode, message: String) = report(code, message, null)

    operator fun invoke(code: DiagnosticCode, message: String, help: String?) =
        report(code, message, help)
}

/** Lowers proto messages to records and unions: fields, oneofs, maps, nested messages, notes. */
internal class MessageLowering(private val lowering: FileLowering) {
    /**
     * A record, or a union when the message is exactly one oneof whose members lower to distinct
     * types, which is how the target writes a union.
     */
    internal fun message(
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
        if (!lowering.claim(claims, name, kind, "message", m.name, m.pos)) return null
        val decl = if (isUnion) union(m, name, mapped) else record(m, name, here, path, mapped)
        m.dropped.forEach { (what, pos) ->
            lowering.report(ImportCodes.DROPPED, "message '${m.name}': $what dropped", pos)
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
            lowering.report(
                ImportCodes.APPROXIMATED,
                "union '${m.name}': oneof '$oneof' is named 'kind' in the regenerated message",
                m.pos,
            )
        }
        val members =
            mapped.map { (f, t) ->
                val where = "field '${m.name}.${f.name}'"
                lowering.diagnostics += t.notes
                val type = t.type!!
                val stem = stem(type, t.symbol)
                if (f.name != stem) {
                    lowering.report(
                        ImportCodes.APPROXIMATED,
                        "union '${m.name}': member element '${f.name}' has no Schemata " +
                            "equivalent; the regenerated oneof names it '$stem'",
                        f.pos,
                    )
                }
                fieldOptions(f, where)
                if (f.options.flag("deprecated")) {
                    lowering.report(
                        ImportCodes.DROPPED,
                        "$where: deprecated dropped; a union member cannot be deprecated",
                        f.pos,
                    )
                }
                // The target never marks a member nullable and Schemata refuses it, so a `?` in
                // a hand-written note cannot be kept.
                if (t.noteNullable) {
                    lowering.report(
                        ImportCodes.APPROXIMATED,
                        "$where: note '${f.note}' marks the member nullable; the '?' is dropped, " +
                            "a union member cannot be nullable",
                        f.pos,
                    )
                }
                if (t.default != null) {
                    lowering.report(
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
                    lowering.report(
                        ImportCodes.APPROXIMATED,
                        "model '${m.name}': oneof '$o' imported as nullable fields; at most one " +
                            "of them is set, which Schemata cannot say",
                        f.pos,
                    )
                }
            }
            lowering.diagnostics += t.notes
            val type = t.type ?: return@forEach
            val fieldName =
                if (ImportNames.isLowerSnake(f.name)) f.name else ImportNames.lowerSnake(f.name)
            val other = claimed.putIfAbsent(fieldName, f.name)
            if (other != null) {
                lowering.report(
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
        val nested = lowering.nested(m.messages, m.enums, here, path, mutableMapOf())
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
                    lowering.report(
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
            lowering.report(ImportCodes.DROPPED, "$where: json_name dropped", f.pos)
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
        val note = Note { code, message, help ->
            notes +=
                if (help == null) diagnostic(lowering.file, code, message, f.pos)
                else diagnostic(lowering.file, code, message, f.pos, help)
        }
        val base: Single? =
            when {
                f.type == "map" -> map(f, scope, enclosing, where, note)
                f.label == Label.REPEATED ->
                    single(f.type, scope, enclosing, where, note)?.let {
                        Single(UnitType.ListOf(it.type, it.nullable, emptyList()), false, it.symbol)
                    }
                else -> single(f.type, scope, enclosing, where, note)
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
                                lowering.context.enumOf(base.symbol).names[literal]
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
        if (symbol?.enum != null) return lowering.context.enumOf(symbol).names[value]
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
        note: Note,
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
        note: Note,
    ): Single? {
        scalar(name, where, note)?.let {
            return Single(it, false, null)
        }
        wellKnown(name, where, note)?.let {
            return it
        }
        val symbol = lowering.context.symbols.resolve(name, scope, lowering.file)
        if (symbol == null) {
            val full = name.removePrefix(".")
            if (full.startsWith("google.protobuf.")) {
                note(ImportCodes.WIDENED, "$where: $full imported as string")
                return Single(UnitType.Scalar("string", emptyList()), false, null)
            }
            val unimported = lowering.unimported(name, scope)
            if (unimported != null) {
                note(
                    ImportCodes.UNRESOLVED,
                    "$where: type '$name' cannot be resolved; ${unimported.first}",
                    unimported.second,
                )
            } else note(ImportCodes.UNRESOLVED, "$where: type '$name' cannot be resolved")
            return null
        }
        return Single(lowering.reference(symbol, enclosing), false, symbol)
    }

    private fun scalar(name: String, where: String, note: Note): UnitType? {
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
    private fun wellKnown(name: String, where: String, note: Note): Single? {
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

    private companion object {
        val INTEGER = Regex("-?[0-9]+")
        val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
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
