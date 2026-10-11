package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Service
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.declarationPath
import io.schemata.core.ir.kindWord
import io.schemata.core.ir.storedFields
import io.schemata.lang.Diagnostic
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.Names
import io.schemata.target.OverrideNames
import io.schemata.target.ProtoPackages
import io.schemata.target.ProtoUnit
import io.schemata.target.deprecated
import io.schemata.target.named
import io.schemata.target.referencesByKey
import io.schemata.target.string
import io.schemata.target.unionMemberStem

/**
 * Lowers every IR shape to a [ProtoModel]. Each decision that loses information is reported once as
 * [ProtoCodes.LOSSY] at the construct's span and recorded in the field's note; references are
 * spelled as proto resolves them from where they are used, and imports follow from them.
 */
object ProtoLowering {
    private const val TIMESTAMP = "google/protobuf/timestamp.proto"
    private const val DURATION = "google/protobuf/duration.proto"
    private const val EMPTY = "google/protobuf/empty.proto"

    /** What to do about a nullable list or list element: proto has no such thing. */
    private const val LIST_HELP = "declare the list as `T[]`; an empty list already means absent"

    /** What to do about a nullable map or map value: proto has no such thing. */
    private const val MAP_HELP =
        "declare the map as `map<K, V>` with non-nullable values; a missing key already means absent"

    /** The largest field number proto allows. */
    private const val MAX_NUMBER = 536870911

    /** Field numbers proto keeps for its own implementation. */
    private val IMPLEMENTATION_NUMBERS = 19000..19999

    fun lower(written: Schema): Lowered<ProtoModel> {
        // A reference to a keyed model carries the model's key, as a foreign key does.
        val schema = written.referencesByKey()
        val diagnostics = mutableListOf<Diagnostic>()
        val names =
            OverrideNames(
                "proto",
                ProtoCodes.INVALID_OVERRIDE,
                diagnostics,
                { if (ProtoNames.isIdentifier(it)) null else "is not a valid identifier" },
            ) {
                "use letters, digits, and underscores, starting with a letter"
            }
        val packages = ProtoPackages.of(schema)
        packages.units.filter { it.merged }.forEach { reportCycle(it, diagnostics) }
        packages.units
            .groupBy { it.packageName }
            .values
            .filter { it.size > 1 }
            .forEach { clashing ->
                // The first unit is blameless: the clash appears at the one that repeats it. A
                // unit is named by its first schema.
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.NAME_COLLISION,
                        "schemas ${clashing.joinToString(" and ") { it.members.first().name }} both lower to package '${clashing.first().packageName}'",
                        clashing[1].members.first().span,
                        help = "set `@proto(package: \"…\")` on one schema",
                    )
            }
        val renames = groupRenames(packages, names, diagnostics)
        val files =
            packages.units.map {
                FileLowering(schema, names, renames, packages, it, diagnostics).lower()
            }
        return Lowered(ProtoModel(files), diagnostics)
    }

    /**
     * Reports how a reference cycle became one file: protoc rejects files that import each other,
     * so schemas that reference each other in a cycle share one. Members that declare different
     * packages cannot share the file, so that is an error at the first member that differs. A
     * package no member declared is a warning at the first member, saying which package the file
     * took and how to choose it.
     */
    private fun reportCycle(unit: ProtoUnit, diagnostics: MutableList<Diagnostic>) {
        val conflict = unit.conflict
        if (conflict != null) {
            val (first, second) = conflict
            diagnostics +=
                Diagnostic(
                    ProtoCodes.INVALID_OVERRIDE,
                    "schemas ${first.name} and ${second.name} reference each other but declare packages " +
                        "'${ProtoPackages.declared(first)}' and '${ProtoPackages.declared(second)}'",
                    second.span,
                    help = "give every schema in the cycle the same `@proto(package: \"…\")`",
                )
        } else if (unit.derived) {
            val listed = unit.members.map { it.name }
            diagnostics +=
                Diagnostic(
                    ProtoCodes.LOSSY,
                    "schemas ${listed.dropLast(1).joinToString(", ")} and ${listed.last()} reference each other; " +
                        "Protobuf cannot import files in a cycle, so they are written as one file under package '${unit.packageName}'",
                    unit.members.first().span,
                    help =
                        "set `@proto(package: \"…\")` to one value on each of them to choose the package",
                )
        }
    }

    /**
     * The new names of top-level declarations that would collide in a merged unit's one package
     * scope, keyed by qualified name. The map is global because a schema outside the unit spells
     * the declaration by its emitted name too, so every file must see the rename before any file
     * lowers. A declared `@proto(name)` and a service name are chosen by the author and never
     * renamed: they claim their names first. Then members are walked in name order, and a plain
     * name that is already claimed, or used by an earlier member, is prefixed with its schema's
     * name in upper camel case (`uc2_system_task` + `Task` → `Uc2SystemTaskTask`). The earlier
     * member keeps the plain name because schema names are the one order every build agrees on, so
     * which declaration is renamed never depends on file paths or source order. Names repeated
     * within one member are the author's own collision, and two colliding declared names have no
     * name to fall back to, so both are left for the name-collision check, as is a prefixed name
     * that is itself taken.
     */
    private fun groupRenames(
        packages: ProtoPackages,
        names: OverrideNames,
        diagnostics: MutableList<Diagnostic>,
    ): Map<QualifiedName, String> {
        val renames = mutableMapOf<QualifiedName, String>()
        for (unit in packages.units.filter { it.merged }) {
            // Each claimed name, with the schema that claimed it and how a message names it.
            val claimed = mutableMapOf<String, Pair<Namespace, String>>()
            val plain = mutableListOf<Pair<Namespace, TypeDecl>>()
            for (member in unit.members) {
                for (decl in member.declarations) {
                    val declared = names.nameOverride(decl)
                    if (declared == null) plain += member to decl
                    else claimed.putIfAbsent(declared, member to holder(decl))
                }
                for (service in member.services) {
                    // A service whose override is invalid keeps its own name; the file reports it.
                    val name =
                        service.annotations.string("proto", "name")?.takeIf {
                            ProtoNames.isIdentifier(it)
                        } ?: service.name
                    claimed.putIfAbsent(name, member to "service '${service.qualifiedName}'")
                }
            }
            val taken = (claimed.keys + plain.map { (_, decl) -> decl.name }).toMutableSet()
            for ((member, decl) in plain) {
                val (owner, user) =
                    claimed.putIfAbsent(decl.name, member to holder(decl)) ?: continue
                if (owner === member) continue
                val prefix = member.name.split('.').joinToString("") { Names.upperCamel(it) }
                val renamed = prefix + decl.name
                if (!taken.add(renamed)) continue
                renames[decl.qualifiedName] = renamed
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.LOSSY,
                        "${holder(decl)}: proto name '${decl.name}' is also used by $user; " +
                            "written as '$renamed'",
                        decl.nameSpan,
                        help = "set `@proto(name: \"…\")` on one of them to choose the name",
                    )
            }
        }
        return renames
    }

    /** How a message names [decl] for a reader of the merged file: by its qualified name. */
    private fun holder(decl: TypeDecl): String = "${decl.kindWord} '${decl.qualifiedName}'"

    private class Mapped(val type: ProtoType, val label: Label, val lossy: Boolean)

    /**
     * Lowers one [unit] to its file. The unit's members share the file and its package scope: every
     * member's declarations, member by member in name order, then every member's services.
     */
    private class FileLowering(
        private val schema: Schema,
        private val names: OverrideNames,
        private val renames: Map<QualifiedName, String>,
        private val packages: ProtoPackages,
        private val unit: ProtoUnit,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        private val imports = sortedSetOf<String>()

        /**
         * [decl]'s emitted name: its valid `@proto(name)` override, else the name it was given to
         * avoid a collision in its cycle's file, else its own name.
         */
        private fun emitted(decl: TypeDecl): String =
            names.nameOverride(decl) ?: renames[decl.qualifiedName] ?: decl.name

        fun lower(): ProtoFile {
            unit.members.forEach { declaredPackage(it) }
            val typeDecls = unit.members.flatMap { it.declarations }
            val serviceDecls = unit.members.flatMap { it.services }
            // Services share the package scope with messages and enums.
            val serviceNames = serviceDecls.associateWith { serviceName(it) }
            scope(
                typeDecls.flatMap { symbols(it) } +
                    serviceDecls.map {
                        Symbol(serviceNames.getValue(it), "service '${it.name}'", it.nameSpan)
                    }
            )
            val declarations = typeDecls.map { decl(it) }
            val services = serviceDecls.map { service(it, serviceNames.getValue(it)) }
            return ProtoFile(
                path = unit.path,
                packageName = unit.packageName,
                imports = imports.toList(),
                declarations = declarations,
                services = services,
            )
        }

        private fun declaredPackage(namespace: Namespace) {
            ProtoPackages.declared(namespace)?.let {
                if (!ProtoNames.isPackage(it)) {
                    invalidOverride(
                        "schema '${namespace.name}': @proto(package: \"$it\") is not a valid package name",
                        namespace.span,
                        help = "use dotted lower-case identifiers, for example `shop.orders.v1`",
                    )
                }
            }
        }

        private fun serviceName(service: Service): String =
            names.overrideName(service.annotations, "service '${service.name}'", service.nameSpan)
                ?: service.name

        /**
         * A service's rpcs, named in a scope of their own. What proto cannot spell rides in
         * schemata notes: an ordinal that is not the rpc's 1-based position, the HTTP binding as
         * the formatter prints it, and the service's `reserved` statement.
         */
        private fun service(service: Service, name: String): ProtoService {
            val rpcNames =
                service.operations.associateWith {
                    names.overrideName(
                        it.annotations,
                        "operation '${service.name}.${it.name}'",
                        it.nameSpan,
                    ) ?: Names.upperCamel(it.name)
                }
            scope(
                service.operations.map {
                    Symbol(rpcNames.getValue(it), "operation '${it.name}'", it.nameSpan)
                }
            )
            val shadowing = rpcNames.values.toSet()
            val rpcs =
                service.operations.mapIndexed { index, op ->
                    val notes = mutableListOf<String>()
                    if (op.ordinal != index + 1) notes += "#${op.ordinal}"
                    op.binding?.let { notes += "${it.verb.lower} ${SchemataText.string(it.path)}" }
                    ProtoRpc(
                        name = rpcNames.getValue(op),
                        request = rpcType(service, op.request, shadowing),
                        response = rpcType(service, op.response, shadowing),
                        doc = op.doc,
                        notes = notes,
                        deprecated = op.annotations.deprecated,
                    )
                }
            return ProtoService(
                name = name,
                doc = service.doc,
                rpcs = rpcs,
                notes = listOfNotNull(reservedNote(service.reserved)),
                deprecated = service.annotations.deprecated,
            )
        }

        /**
         * No payload is `google.protobuf.Empty`, which proto offers for exactly that. It is spelled
         * with a leading dot, as the other well-known types are: a relative `google.…` would
         * resolve against any `google` visible from the file's package first.
         *
         * protoc resolves an rpc's types from inside the service, where every rpc name of the
         * service is a symbol, so a relative payload whose first segment is one of [rpcNames] would
         * resolve to that rpc. Such a payload is spelled from the package, with a leading dot,
         * whether it is the bare name or a path through it (`Order.Line`); any other relative
         * payload keeps its short spelling.
         */
        private fun rpcType(
            service: Service,
            payload: Payload?,
            rpcNames: Set<String>,
        ): ProtoRpcType {
            if (payload == null) {
                imports += EMPTY
                return ProtoRpcType(".google.protobuf.Empty", stream = false)
            }
            // An rpc sits at the top of its schema, inside no declaration.
            val here = QualifiedName(service.qualifiedName.namespace, emptyList())
            val reference = reference(payload.target, here).reference
            val spelled =
                if (!reference.startsWith(".") && reference.substringBefore('.') in rpcNames) {
                    ".${unit.packageName}.$reference"
                } else {
                    reference
                }
            return ProtoRpcType(spelled, payload.stream)
        }

        /** `reserved #3, #5..#7, "archive"`: ordinals as `#n` or `#a..#b`, then quoted names. */
        private fun reservedNote(reserved: Reserved): String? {
            // Ordinals then names: the importer prints its `reserved` statement in this order.
            if (reserved.ordinals.isEmpty() && reserved.names.isEmpty()) return null
            val items =
                reserved.ordinals.map { SchemataText.ordinalRange(it.first, it.last) } +
                    reserved.names.map { SchemataText.string(it) }
            return "reserved " + items.joinToString(", ")
        }

        private fun decl(decl: TypeDecl): ProtoDecl =
            when (decl) {
                is RecordType -> record(decl)
                is EnumType -> enum(decl)
                is UnionType -> union(decl)
            }

        private fun record(record: RecordType): ProtoMessage {
            val here = record.qualifiedName
            val where = "model '${record.name}'"
            val name = emitted(record)
            // A back-reference is virtual: the forward reference on the other message carries it.
            val stored = record.storedFields
            val fieldNames =
                stored.associateWith {
                    names.overrideName(
                        it.annotations,
                        "field '${record.name}.${it.name}'",
                        it.nameSpan,
                    ) ?: it.name
                }
            scope(
                record.nested.flatMap { symbols(it) } +
                    stored.map {
                        Symbol(fieldNames.getValue(it), "field '${it.name}'", it.nameSpan)
                    }
            )
            jsonNames(record, fieldNames)
            val fields = stored.map { field(record, it, here, fieldNames) }
            val nested = record.nested.map { decl(it) }
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
            here: QualifiedName,
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
                            emitted(enum),
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
            val name = emitted(enum)
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
        private fun valueName(enumName: String, enum: EnumType, value: EnumValue): String =
            names.enumValueOverride(enum, value) ?: ProtoNames.valueName(enumName, value.name)

        private fun union(union: UnionType): ProtoMessage {
            val here = union.qualifiedName
            val name = emitted(union)
            scope(
                listOf(Symbol("kind", "the oneof", union.nameSpan)) +
                    union.members.map { member ->
                        val memberName = memberName(member.named)
                        Symbol(memberName, "member '$memberName'", member.span)
                    }
            )
            val members =
                union.members.map { member ->
                    val memberName = memberName(member.named)
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
            unionMemberStem(type, schema) {
                names.nameOverride(it) ?: renames[it.qualifiedName]?.let(Names::snakeCase)
            }

        /** Maps a field's or member's type; [nullable] is the field's own `?`. */
        private fun map(
            type: Type,
            nullable: Boolean,
            where: String,
            span: Span,
            here: QualifiedName,
        ): Mapped {
            val refined = type.hasRefinements()
            if (refined) {
                lossy(
                    "$where: refinements on ${ProtoTypes.text(type)} are not enforced by Protobuf",
                    span,
                    help =
                        "enforce the refinement in application code; Protobuf carries no constraints",
                )
            }
            val mapped =
                when (type) {
                    is Scalar -> scalarField(type, nullable, where, span)
                    is Ref -> refField(type, nullable, here)
                    is ListOf -> listField(type, nullable, where, span, here)
                    is MapOf -> mapField(type, nullable, where, span, here)
                }
            return Mapped(mapped.type, mapped.label, mapped.lossy || refined)
        }

        private fun scalarField(
            type: Scalar,
            nullable: Boolean,
            where: String,
            span: Span,
        ): Mapped {
            val (scalar, isLossy) = scalar(type.builtin, where, span)
            // A nullable Timestamp or Duration has no `optional`: the message's presence already
            // says absent. Nothing in the output records that the field was nullable, so the
            // note does, for whoever reads the file back.
            val lossy = isLossy || (nullable && scalar !is ProtoType.Scalar)
            val label = if (nullable && scalar is ProtoType.Scalar) Label.OPTIONAL else Label.NONE
            return Mapped(scalar, label, lossy)
        }

        private fun refField(type: Ref, nullable: Boolean, here: QualifiedName): Mapped {
            // `optional` is written only for an enum, so a nullable message-typed field is
            // otherwise indistinguishable from a required one.
            val enum = isEnum(type.target)
            return Mapped(
                reference(type.target, here),
                if (nullable && enum) Label.OPTIONAL else Label.NONE,
                lossy = nullable && !enum,
            )
        }

        private fun listField(
            type: ListOf,
            nullable: Boolean,
            where: String,
            span: Span,
            here: QualifiedName,
        ): Mapped {
            if (nullable) {
                lossy(
                    "$where: a nullable list has no Protobuf representation; lowered to repeated",
                    span,
                    help = LIST_HELP,
                )
            }
            if (type.nullableElement) {
                lossy(
                    "$where: nullable list elements have no Protobuf representation; lowered to repeated",
                    span,
                    help = LIST_HELP,
                )
            }
            val element = element(type.element, type, where, span, here)
            return Mapped(
                element.type,
                Label.REPEATED,
                nullable || type.nullableElement || element.lossy,
            )
        }

        private fun mapField(
            type: MapOf,
            nullable: Boolean,
            where: String,
            span: Span,
            here: QualifiedName,
        ): Mapped {
            if (nullable) {
                lossy(
                    "$where: a nullable map has no Protobuf representation; lowered to map",
                    span,
                    help = MAP_HELP,
                )
            }
            if (type.nullableValue) {
                lossy(
                    "$where: nullable map values have no Protobuf representation; lowered to map",
                    span,
                    help = MAP_HELP,
                )
            }
            val key = ProtoType.Scalar(ProtoTypes.keyword((type.key as Scalar).builtin)!!)
            val value = element(type.value, type, where, span, here)
            return Mapped(
                ProtoType.MapOf(key, value.type),
                Label.NONE,
                nullable || type.nullableValue || value.lossy,
            )
        }

        /** The element of a list or the value of a map. Collections do not nest in proto. */
        private fun element(
            element: Type,
            owner: Type,
            where: String,
            span: Span,
            here: QualifiedName,
        ): Mapped =
            when (element) {
                is Scalar -> {
                    val (scalar, isLossy) = scalar(element.builtin, where, span)
                    Mapped(scalar, Label.NONE, isLossy)
                }
                is Ref -> Mapped(reference(element.target, here), Label.NONE, lossy = false)
                is ListOf,
                is MapOf -> Mapped(nested(owner, where, span), Label.NONE, lossy = false)
            }

        private fun nested(owner: Type, where: String, span: Span): ProtoType {
            diagnostics +=
                Diagnostic(
                    ProtoCodes.UNSUPPORTED_NESTING,
                    "$where: proto cannot nest collections; ${ProtoTypes.text(owner)} has a collection element",
                    span,
                    help = "wrap the element in a model",
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
         * Spells a reference as proto resolves it from a message at [here]. A type in another file
         * is package-qualified with a leading dot, which proto resolves absolutely, and imported. A
         * type in this file, from this schema or another member of its cycle, is spelled
         * relatively, by its remaining path, so that the file reads back as one schema with the
         * same spelling; every member's top-level declarations sit in the one package scope. Proto
         * looks a relative name up from the innermost enclosing message outward, so where a
         * declaration nested along [here]'s path has the name the relative spelling starts with,
         * that name would capture the reference, and it takes the absolute form instead.
         */
        private fun reference(target: QualifiedName, here: QualifiedName): ProtoType.Named {
            val path = schema.declarationPath(target).map { emitted(it) }
            val home = packages.unitOf(target.namespace)
            if (home !== unit) {
                imports += home.path
                return ProtoType.Named(".${home.packageName}.${path.joinToString(".")}")
            }
            // Enclosing declarations shared with the target are left out of the spelling; another
            // schema's declarations share none.
            val common =
                if (target.namespace != here.namespace) 0
                else here.path.zip(target.path).takeWhile { (a, b) -> a == b }.size
            val keep = if (common == target.path.size) common - 1 else common
            val relative = path.drop(keep)
            val shadowed =
                (keep + 1..here.path.size).any { depth ->
                    val enclosing = here.path.take(depth)
                    val scope = schema.lookup(QualifiedName(here.namespace, enclosing))
                    // A nested name that is the target's own enclosing declaration leads to it.
                    scope.nested.any { emitted(it) == relative.first() } &&
                        QualifiedName(here.namespace, enclosing + target.path[keep]) !=
                            QualifiedName(target.namespace, target.path.take(keep + 1))
                }
            return ProtoType.Named(
                if (shadowed) ".${unit.packageName}.${path.joinToString(".")}"
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
                        help = "rename one of them, or set `@proto(name: \"…\")` on one",
                    )
            }
        }

        /**
         * Reports [ProtoCodes.JSON_NAME_COLLISION] for every field whose protoc JSON name an
         * earlier field of the record already has; protoc rejects the file. Two fields with one
         * proto name are a [ProtoCodes.NAME_COLLISION] instead, reported by [scope].
         */
        private fun jsonNames(record: RecordType, fieldNames: Map<Field, String>) {
            val first = mutableMapOf<String, Field>()
            for (field in record.storedFields) {
                val protoName = fieldNames.getValue(field)
                val json = ProtoNames.jsonName(protoName)
                val previous = first.putIfAbsent(json, field) ?: continue
                if (fieldNames.getValue(previous) == protoName) continue
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.JSON_NAME_COLLISION,
                        "fields '${record.name}.${previous.name}' and '${record.name}.${field.name}' share the Protobuf JSON name '$json'",
                        field.nameSpan,
                        help = "rename one of them, or set `@proto(name: \"…\")` on one",
                    )
            }
        }

        /**
         * A declaration's own symbol followed, for an enum, by every value it puts in the enclosing
         * scope: proto scopes enum values at the scope that holds the enum, not inside it.
         */
        private fun symbols(decl: TypeDecl): List<Symbol> {
            val own = Symbol(emitted(decl), "${decl.kindWord} '${decl.name}'", decl.nameSpan)
            if (decl !is EnumType) return listOf(own)
            val name = emitted(decl)
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
