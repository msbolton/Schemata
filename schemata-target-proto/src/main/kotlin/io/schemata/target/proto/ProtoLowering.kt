package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.declarationPath
import io.schemata.core.ir.kindWord
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.OverrideNames
import io.schemata.target.deprecated
import io.schemata.target.string
import io.schemata.target.unionMemberStem

/** [decl]'s emitted name: its valid `@proto(name)` override, else its own name. */
private fun OverrideNames.of(decl: TypeDecl): String = nameOverride(decl) ?: decl.name

/**
 * Lowers every IR shape to a [ProtoModel]. Each decision that loses information is reported once as
 * [ProtoCodes.LOSSY] at the construct's span and recorded in the field's note; references are
 * spelled as proto resolves them from where they are used, and imports follow from them.
 */
object ProtoLowering {
    private const val TIMESTAMP = "google/protobuf/timestamp.proto"
    private const val DURATION = "google/protobuf/duration.proto"

    /** The largest field number proto allows. */
    private const val MAX_NUMBER = 536870911

    /** Field numbers proto keeps for its own implementation. */
    private val IMPLEMENTATION_NUMBERS = 19000..19999

    fun lower(schema: Schema): Lowered<ProtoModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val names =
            OverrideNames(
                schema,
                "proto",
                ProtoCodes.INVALID_OVERRIDE,
                diagnostics,
                { if (ProtoNames.isIdentifier(it)) null else "is not a valid identifier" },
            ) {
                "use letters, digits, and underscores, starting with a letter"
            }
        val packages = schema.namespaces.associate { it.name to ProtoNames.packageOf(it) }
        packages.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size > 1 }
            .forEach { clashing ->
                // The first namespace is blameless: the clash appears at the one that repeats it.
                val second = schema.namespaces.first { it.name == clashing[1] }
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.NAME_COLLISION,
                        "namespaces ${clashing.joinToString(" and ")} both lower to package '${packages.getValue(clashing.first())}'",
                        second.span,
                        help = "set `@proto(package = \"…\")` on one namespace",
                    )
            }
        val files =
            schema.namespaces.map { FileLowering(schema, names, packages, it, diagnostics).lower() }
        return Lowered(ProtoModel(files), diagnostics)
    }

    private class Mapped(val type: ProtoType, val label: Label, val lossy: Boolean)

    private class FileLowering(
        private val schema: Schema,
        private val names: OverrideNames,
        private val packages: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        private val imports = sortedSetOf<String>()

        fun lower(): ProtoFile {
            namespace.annotations.string("proto", "package")?.let {
                if (!ProtoNames.isPackage(it)) {
                    invalidOverride(
                        "namespace '${namespace.name}': @proto(package = \"$it\") is not a valid package name",
                        namespace.span,
                        help = "use dotted lower-case identifiers, for example `shop.orders.v1`",
                    )
                }
            }
            scope(namespace.declarations.flatMap { symbols(it) })
            val declarations = namespace.declarations.map { decl(it, emptyList()) }
            return ProtoFile(
                path = namespace.name.replace('.', '/') + ".proto",
                packageName = packages.getValue(namespace.name),
                imports = imports.toList(),
                declarations = declarations,
            )
        }

        /** [enclosing] is the Schemata path of the records this declaration sits inside. */
        private fun decl(decl: TypeDecl, enclosing: List<String>): ProtoDecl =
            when (decl) {
                is RecordType -> record(decl, enclosing)
                is EnumType -> enum(decl)
                is UnionType -> union(decl, enclosing)
            }

        private fun record(record: RecordType, enclosing: List<String>): ProtoMessage {
            val here = enclosing + record.name
            val where = "record '${record.name}'"
            val name = names.of(record)
            val fieldNames =
                record.fields.associateWith {
                    names.overrideName(
                        it.annotations,
                        "field '${record.name}.${it.name}'",
                        it.nameSpan,
                    ) ?: it.name
                }
            scope(
                record.nested.flatMap { symbols(it) } +
                    record.fields.map {
                        Symbol(fieldNames.getValue(it), "field '${it.name}'", it.nameSpan)
                    }
            )
            val fields = record.fields.map { field(record, it, here, fieldNames) }
            val nested = record.nested.map { decl(it, here) }
            reservedNumbers(where, record.reserved.ordinals, record.nameSpan, bounded = true)
            return ProtoMessage(
                name = name,
                doc = record.doc,
                fields = fields,
                oneofs = emptyList(),
                nested = nested,
                reserved = ProtoReserved(record.reserved.ordinals, record.reserved.names.sorted()),
                deprecated = record.annotations.deprecated,
            )
        }

        private fun field(
            record: RecordType,
            field: Field,
            here: List<String>,
            fieldNames: Map<Field, String>,
        ): ProtoField {
            val where = "field '${record.name}.${field.name}'"
            fieldNumber(where, field.ordinal, field.span)
            val mapped = map(field.type, field.nullable, where, field.span, here)
            val notes = mutableListOf<String>()
            if (mapped.lossy) notes += ProtoTypes.text(field.type, field.nullable)
            field.default?.let {
                val text =
                    (it as? EnumRef)?.let { ref ->
                        val enum = schema.lookup(ref.enum) as EnumType
                        valueName(
                            names.of(enum),
                            enum,
                            enum.values.first { v -> v.name == ref.value },
                        )
                    } ?: ProtoTypes.text(it)
                lossy(
                    "$where: default $text is not carried by proto3",
                    field.span,
                    help =
                        "drop the default or apply it in application code; proto3 has no field defaults",
                )
                notes += "default = $text"
            }
            return ProtoField(
                number = field.ordinal,
                name = fieldNames.getValue(field),
                type = mapped.type,
                label = mapped.label,
                doc = field.doc,
                notes = notes,
                deprecated = field.annotations.deprecated,
            )
        }

        private fun enum(enum: EnumType): ProtoEnum {
            val name = names.of(enum)
            enum.values.forEach { names.enumValueName(enum, it) }
            reservedNumbers(
                "enum '${enum.name}'",
                enum.reserved.ordinals,
                enum.nameSpan,
                bounded = false,
            )
            val zero = ProtoNames.zeroValue(name)
            lossy(
                "enum '${enum.name}': proto3 requires a zero value; synthesized $zero = 0",
                enum.nameSpan,
                help = "keep the synthesized zero value; proto3 reads an unset enum as 0",
            )
            val values =
                listOf(ProtoEnumValue(zero, 0)) +
                    enum.values.map {
                        ProtoEnumValue(
                            valueName(name, enum, it),
                            it.ordinal,
                            it.doc,
                            it.annotations.deprecated,
                        )
                    }
            return ProtoEnum(
                name = name,
                doc = enum.doc,
                values = values,
                reserved =
                    ProtoReserved(
                        enum.reserved.ordinals,
                        enum.reserved.names.sorted().map { ProtoNames.valueName(name, it) },
                    ),
                deprecated = enum.annotations.deprecated,
            )
        }

        /**
         * The emitted name of [value]: its valid `@proto(name)` override, else the prefixed form.
         */
        private fun valueName(enumName: String, enum: EnumType, value: EnumValue): String {
            val override = names.enumValueName(enum, value)
            return if (override != value.name) override
            else ProtoNames.valueName(enumName, value.name)
        }

        private fun union(union: UnionType, enclosing: List<String>): ProtoMessage {
            val here = enclosing + union.name
            val name = names.of(union)
            scope(
                listOf(Symbol("kind", "the oneof", union.nameSpan)) +
                    union.members.map { member ->
                        val memberName = memberName(member.type)
                        Symbol(memberName, "member '$memberName'", member.span)
                    }
            )
            val members =
                union.members.map { member ->
                    val memberName = memberName(member.type)
                    val where = "member '${union.name}.$memberName'"
                    fieldNumber(where, member.ordinal, member.span)
                    val mapped = map(member.type, nullable = false, where, member.span, here)
                    val notes =
                        if (mapped.lossy) listOf(ProtoTypes.text(member.type)) else emptyList()
                    ProtoField(
                        member.ordinal,
                        memberName,
                        mapped.type,
                        Label.NONE,
                        member.doc,
                        notes,
                    )
                }
            return ProtoMessage(
                name = name,
                doc = union.doc,
                fields = emptyList(),
                oneofs = listOf(ProtoOneof("kind", null, members)),
                nested = emptyList(),
                reserved = ProtoReserved.NONE,
                deprecated = union.annotations.deprecated,
            )
        }

        private fun memberName(type: Type): String =
            unionMemberStem(type, schema) { names.nameOverride(it) }

        /** Maps a field's or member's type; [nullable] is the field's own `?`. */
        private fun map(
            type: Type,
            nullable: Boolean,
            where: String,
            span: Span,
            here: List<String>,
        ): Mapped {
            var lossy = false
            if (type.hasRefinements()) {
                lossy(
                    "$where: refinements on ${ProtoTypes.text(type)} are not enforced by Protobuf",
                    span,
                    help =
                        "enforce the refinement in application code; Protobuf carries no constraints",
                )
                lossy = true
            }
            val (proto, label) =
                when (type) {
                    is Scalar -> {
                        val (scalar, isLossy) = scalar(type.builtin, where, span)
                        lossy = lossy || isLossy
                        val label =
                            if (nullable && scalar is ProtoType.Scalar) Label.OPTIONAL
                            else Label.NONE
                        scalar to label
                    }
                    is Ref ->
                        reference(type.target, here) to
                            (if (nullable && isEnum(type.target)) Label.OPTIONAL else Label.NONE)
                    is ListOf -> {
                        if (nullable) {
                            lossy(
                                "$where: a nullable list has no Protobuf representation; lowered to repeated",
                                span,
                                help =
                                    "declare the list as `list<T>` with non-nullable elements; an empty list already means absent",
                            )
                            lossy = true
                        }
                        if (type.nullableElement) {
                            lossy(
                                "$where: nullable list elements have no Protobuf representation; lowered to repeated",
                                span,
                                help =
                                    "declare the list as `list<T>` with non-nullable elements; an empty list already means absent",
                            )
                            lossy = true
                        }
                        val element =
                            element(type.element, type, where, span, here) { lossy = true }
                        element to Label.REPEATED
                    }
                    is MapOf -> {
                        if (nullable) {
                            lossy(
                                "$where: a nullable map has no Protobuf representation; lowered to map",
                                span,
                                help =
                                    "declare the map as `map<K, V>` with non-nullable values; a missing key already means absent",
                            )
                            lossy = true
                        }
                        if (type.nullableValue) {
                            lossy(
                                "$where: nullable map values have no Protobuf representation; lowered to map",
                                span,
                                help =
                                    "declare the map as `map<K, V>` with non-nullable values; a missing key already means absent",
                            )
                            lossy = true
                        }
                        val key =
                            ProtoType.Scalar(ProtoTypes.keyword((type.key as Scalar).builtin)!!)
                        val value = element(type.value, type, where, span, here) { lossy = true }
                        ProtoType.MapOf(key, value) to Label.NONE
                    }
                }
            return Mapped(proto, label, lossy)
        }

        /** The element of a list or the value of a map. Collections do not nest in proto. */
        private fun element(
            element: Type,
            owner: Type,
            where: String,
            span: Span,
            here: List<String>,
            markLossy: () -> Unit,
        ): ProtoType =
            when (element) {
                is Scalar -> {
                    val (scalar, isLossy) = scalar(element.builtin, where, span)
                    if (isLossy) markLossy()
                    scalar
                }
                is Ref -> reference(element.target, here)
                is ListOf,
                is MapOf -> nested(owner, where, span)
            }

        private fun nested(owner: Type, where: String, span: Span): ProtoType {
            diagnostics +=
                Diagnostic(
                    ProtoCodes.UNSUPPORTED_NESTING,
                    "$where: proto cannot nest collections; ${ProtoTypes.text(owner)} has a collection element",
                    span,
                    help = "wrap the element in a record",
                )
            return ProtoType.Scalar("bytes") // never rendered: the error above prevents rendering
        }

        /** @return the proto type and whether the mapping lost information. */
        private fun scalar(builtin: Builtin, where: String, span: Span): Pair<ProtoType, Boolean> {
            ProtoTypes.keyword(builtin)?.let {
                return ProtoType.Scalar(it) to false
            }
            return when (builtin) {
                Builtin.INSTANT -> {
                    imports += TIMESTAMP
                    ProtoType.Named(".google.protobuf.Timestamp") to false
                }
                Builtin.DURATION -> {
                    imports += DURATION
                    ProtoType.Named(".google.protobuf.Duration") to false
                }
                Builtin.UUID,
                Builtin.DECIMAL,
                Builtin.DATE,
                Builtin.TIME -> {
                    lossy(
                        "$where: ${builtin.typeName} has no Protobuf representation; lowered to string",
                        span,
                        help = "keep the string form; parse it in application code",
                    )
                    ProtoType.Scalar("string") to true
                }
                Builtin.BOOL,
                Builtin.INT32,
                Builtin.INT64,
                Builtin.FLOAT32,
                Builtin.FLOAT64,
                Builtin.STRING,
                Builtin.BYTES -> error("unreachable: keyword builtins returned above")
            }
        }

        private fun isEnum(target: QualifiedName): Boolean = schema.lookup(target) is EnumType

        /**
         * Spells a reference as proto resolves it from a message at [here]: a nested type by its
         * remaining path, a type in another package package-qualified with a leading dot, which
         * proto resolves absolutely (and imported), and a relative name a closer declaration would
         * shadow by that same absolute form.
         */
        private fun reference(target: QualifiedName, here: List<String>): ProtoType.Named {
            val path = schema.declarationPath(target).map { names.of(it) }
            if (target.namespace != namespace.name) {
                imports += target.namespace.replace('.', '/') + ".proto"
                return ProtoType.Named(
                    ".${packages.getValue(target.namespace)}.${path.joinToString(".")}"
                )
            }
            val common = here.zip(target.path).takeWhile { (a, b) -> a == b }.size
            val keep = if (common == target.path.size) common - 1 else common
            val relative = path.drop(keep)
            val shadowed =
                (keep + 1..here.size).any { depth ->
                    val scope = schema.lookup(QualifiedName(namespace.name, here.take(depth)))
                    scope.nested.any { names.of(it) == relative.first() } &&
                        here.take(depth) + target.path.getOrNull(keep) != target.path.take(keep + 1)
                }
            return ProtoType.Named(
                if (shadowed) ".${packages.getValue(namespace.name)}.${path.joinToString(".")}"
                else relative.joinToString(".")
            )
        }

        private fun Type.hasRefinements(): Boolean =
            when (this) {
                is Scalar -> refinements.hasBounds
                is ListOf -> refinements.hasBounds || element.hasRefinements()
                is MapOf -> refinements.hasBounds || key.hasRefinements() || value.hasRefinements()
                is Ref -> false
            }

        private fun lossy(message: String, span: Span, help: String) {
            diagnostics += Diagnostic(ProtoCodes.LOSSY, message, span, help)
        }

        private fun invalidOverride(message: String, span: Span, help: String) {
            diagnostics += Diagnostic(ProtoCodes.INVALID_OVERRIDE, message, span, help)
        }

        private fun invalidNumber(message: String, span: Span, help: String) {
            diagnostics += Diagnostic(ProtoCodes.INVALID_FIELD_NUMBER, message, span, help)
        }

        /** The numbers proto refuses for a field or a oneof member. */
        private fun fieldNumber(where: String, number: Int, span: Span) {
            if (number !in 1..MAX_NUMBER) {
                invalidNumber(
                    "$where: field number $number exceeds the Protobuf maximum $MAX_NUMBER",
                    span,
                    help = "use an ordinal of at most $MAX_NUMBER",
                )
            }
            if (number in IMPLEMENTATION_NUMBERS) {
                invalidNumber(
                    "$where: field number $number is reserved for the Protobuf implementation " +
                        "(${IMPLEMENTATION_NUMBERS.first} to ${IMPLEMENTATION_NUMBERS.last})",
                    span,
                    help =
                        "use an ordinal outside ${IMPLEMENTATION_NUMBERS.first} to " +
                            "${IMPLEMENTATION_NUMBERS.last}",
                )
            }
        }

        /**
         * Reserved ranges proto refuses. [bounded] is false for an enum, whose values are not
         * capped like field numbers. Overlaps are reported against the range that starts before.
         */
        private fun reservedNumbers(
            where: String,
            ranges: List<IntRange>,
            span: Span,
            bounded: Boolean,
        ) {
            ranges.forEach {
                if (it.first < 1) {
                    invalidNumber(
                        "$where: reserved number ${it.first} must be positive",
                        span,
                        help = "reserve ordinals from #1 upward",
                    )
                }
                if (bounded && it.last > MAX_NUMBER) {
                    invalidNumber(
                        "$where: reserved number ${it.last} exceeds the Protobuf maximum $MAX_NUMBER",
                        span,
                        help = "reserve ordinals of at most $MAX_NUMBER",
                    )
                }
            }
            ranges
                .sortedBy { it.first }
                .zipWithNext { earlier, later ->
                    if (later.first <= earlier.last) {
                        invalidNumber(
                            "$where: reserved range ${later.first} to ${later.last} overlaps " +
                                "${earlier.first} to ${earlier.last}",
                            span,
                            help = "merge or separate the two ranges",
                        )
                    }
                }
        }

        /** One symbol a proto scope holds; [span] is where a later duplicate is reported. */
        private class Symbol(val protoName: String, val holder: String, val span: Span)

        /**
         * Reports [ProtoCodes.NAME_COLLISION] for every repeat of a proto name within one scope.
         */
        private fun scope(symbols: List<Symbol>) {
            val first = mutableMapOf<String, Symbol>()
            for (symbol in symbols) {
                val previous = first.putIfAbsent(symbol.protoName, symbol) ?: continue
                val location =
                    if (previous.holder.startsWith("the ")) ""
                    else " (${previous.span.file}:${previous.span.startLine})"
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.NAME_COLLISION,
                        "proto name '${symbol.protoName}' is already used by ${previous.holder}$location",
                        symbol.span,
                        help = "rename one of them, or set `@proto(name = \"…\")` on one",
                    )
            }
        }

        /**
         * A declaration's own symbol followed, for an enum, by every value it puts in the enclosing
         * scope: proto scopes enum values at the scope that holds the enum, not inside it.
         */
        private fun symbols(decl: TypeDecl): List<Symbol> {
            val own = Symbol(names.of(decl), "${decl.kindWord} '${decl.name}'", decl.nameSpan)
            if (decl !is EnumType) return listOf(own)
            val name = names.of(decl)
            return listOf(
                own,
                Symbol(ProtoNames.zeroValue(name), "the synthesized zero value", decl.nameSpan),
            ) +
                decl.values.map {
                    Symbol(valueName(name, decl, it), "value '${it.name}'", it.nameSpan)
                }
        }
    }
}
