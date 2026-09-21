package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
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
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered

/**
 * Lowers every IR shape to a [ProtoModel]. Each decision that loses information is reported once as
 * [ProtoCodes.LOSSY] at the construct's span and recorded in the field's note; references are
 * spelled as proto resolves them from where they are used, and imports follow from them.
 */
object ProtoLowering {
    private const val TIMESTAMP = "google/protobuf/timestamp.proto"
    private const val DURATION = "google/protobuf/duration.proto"

    fun lower(schema: Schema): Lowered<ProtoModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val packages = schema.namespaces.associate { it.name to ProtoNames.packageOf(it) }
        packages.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size > 1 }
            .forEach { names ->
                // The first namespace is blameless: the clash appears at the one that repeats it.
                val second = schema.namespaces.first { it.name == names[1] }
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.NAME_COLLISION,
                        "namespaces ${names.joinToString(" and ")} both lower to package '${packages.getValue(names.first())}'",
                        second.span,
                    )
            }
        val files =
            schema.namespaces.map { FileLowering(schema, packages, it, diagnostics).lower() }
        return Lowered(ProtoModel(files), diagnostics)
    }

    private class Mapped(val type: ProtoType, val label: Label, val lossy: Boolean)

    private class FileLowering(
        private val schema: Schema,
        private val packages: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        private val imports = sortedSetOf<String>()

        fun lower(): ProtoFile {
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

        private fun kindOf(decl: TypeDecl): String =
            when (decl) {
                is RecordType -> "record"
                is EnumType -> "enum"
                is UnionType -> "union"
            }

        private fun record(record: RecordType, enclosing: List<String>): ProtoMessage {
            val here = enclosing + record.name
            scope(
                record.nested.flatMap { symbols(it) } +
                    record.fields.map {
                        Symbol(ProtoNames.of(it), "field '${it.name}'", it.nameSpan)
                    }
            )
            return ProtoMessage(
                name = ProtoNames.of(record),
                doc = record.doc,
                fields = record.fields.map { field(record, it, here) },
                oneofs = emptyList(),
                nested = record.nested.map { decl(it, here) },
                reserved = ProtoReserved(record.reserved.ordinals, record.reserved.names.sorted()),
                deprecated = ProtoNames.deprecated(record.annotations),
            )
        }

        private fun field(record: RecordType, field: Field, here: List<String>): ProtoField {
            val where = "field '${record.name}.${field.name}'"
            val mapped = map(field.type, field.nullable, where, field.span, here)
            val notes = mutableListOf<String>()
            if (mapped.lossy) notes += ProtoTypes.text(field.type, field.nullable)
            field.default?.let {
                val text = ProtoTypes.text(it)
                lossy("$where: default $text is not carried by proto3", field.span)
                notes += "default = $text"
            }
            return ProtoField(
                number = field.ordinal,
                name = ProtoNames.of(field),
                type = mapped.type,
                label = mapped.label,
                doc = field.doc,
                notes = notes,
                deprecated = ProtoNames.deprecated(field.annotations),
            )
        }

        private fun enum(enum: EnumType): ProtoEnum {
            val name = ProtoNames.of(enum)
            val zero = ProtoNames.zeroValue(name)
            lossy(
                "enum '${enum.name}': proto3 requires a zero value; synthesized $zero = 0",
                enum.nameSpan,
            )
            val values =
                listOf(ProtoEnumValue(zero, 0)) +
                    enum.values.map {
                        ProtoEnumValue(
                            ProtoNames.of(name, it),
                            it.ordinal,
                            it.doc,
                            ProtoNames.deprecated(it.annotations),
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
                deprecated = ProtoNames.deprecated(enum.annotations),
            )
        }

        private fun union(union: UnionType, enclosing: List<String>): ProtoMessage {
            val here = enclosing + union.name
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
                name = ProtoNames.of(union),
                doc = union.doc,
                fields = emptyList(),
                oneofs = listOf(ProtoOneof("kind", null, members)),
                nested = emptyList(),
                reserved = ProtoReserved.NONE,
                deprecated = ProtoNames.deprecated(union.annotations),
            )
        }

        private fun memberName(type: Type): String =
            when (type) {
                is Scalar -> type.builtin.typeName
                is Ref -> ProtoNames.snakeCase(type.target.simpleName)
                is ListOf,
                is MapOf ->
                    error(
                        "union members are named types or scalars; the analyzer rejects collections"
                    )
            }

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
                            )
                            lossy = true
                        }
                        if (type.nullableElement) {
                            lossy(
                                "$where: nullable list elements have no Protobuf representation; lowered to repeated",
                                span,
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
                            )
                            lossy = true
                        }
                        if (type.nullableValue) {
                            lossy(
                                "$where: nullable map values have no Protobuf representation; lowered to map",
                                span,
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
                    "$where: proto cannot nest collections; wrap the element of ${ProtoTypes.text(owner)} in a record",
                    span,
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
                    ProtoType.Named("google.protobuf.Timestamp") to false
                }
                Builtin.DURATION -> {
                    imports += DURATION
                    ProtoType.Named("google.protobuf.Duration") to false
                }
                else -> {
                    lossy(
                        "$where: ${builtin.typeName} has no Protobuf representation; lowered to string",
                        span,
                    )
                    ProtoType.Scalar("string") to true
                }
            }
        }

        private fun isEnum(target: QualifiedName): Boolean = schema.lookup(target) is EnumType

        /**
         * Proto-named segments of a declaration's path: `Order.Line` with any `@proto(name)`
         * applied.
         */
        private fun protoPath(target: QualifiedName): List<String> =
            (1..target.path.size).map { n ->
                ProtoNames.of(schema.lookup(QualifiedName(target.namespace, target.path.take(n))))
            }

        /**
         * Spells a reference as proto resolves it from a message at [here]: a nested type by its
         * remaining path, a type in another package fully qualified (and imported), and a relative
         * name a closer declaration would shadow by its package-qualified form.
         */
        private fun reference(target: QualifiedName, here: List<String>): ProtoType.Named {
            val path = protoPath(target)
            if (target.namespace != namespace.name) {
                imports += target.namespace.replace('.', '/') + ".proto"
                return ProtoType.Named(
                    "${packages.getValue(target.namespace)}.${path.joinToString(".")}"
                )
            }
            val common = here.zip(target.path).takeWhile { (a, b) -> a == b }.size
            val keep = if (common == target.path.size) common - 1 else common
            val relative = path.drop(keep)
            val shadowed =
                (keep + 1..here.size).any { depth ->
                    val scope = schema.lookup(QualifiedName(namespace.name, here.take(depth)))
                    scope.nested.any { ProtoNames.of(it) == relative.first() } &&
                        here.take(depth) + target.path.getOrNull(keep) != target.path.take(keep + 1)
                }
            return ProtoType.Named(
                if (shadowed) "${packages.getValue(namespace.name)}.${path.joinToString(".")}"
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

        private fun lossy(message: String, span: Span) {
            diagnostics += Diagnostic(ProtoCodes.LOSSY, message, span)
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
                    )
            }
        }

        /**
         * A declaration's own symbol followed, for an enum, by every value it puts in the enclosing
         * scope: proto scopes enum values at the scope that holds the enum, not inside it.
         */
        private fun symbols(decl: TypeDecl): List<Symbol> {
            val own = Symbol(ProtoNames.of(decl), "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            if (decl !is EnumType) return listOf(own)
            val name = ProtoNames.of(decl)
            return listOf(
                own,
                Symbol(ProtoNames.zeroValue(name), "the synthesized zero value", decl.nameSpan),
            ) +
                decl.values.map {
                    Symbol(ProtoNames.of(name, it), "value '${it.name}'", it.nameSpan)
                }
        }
    }
}
