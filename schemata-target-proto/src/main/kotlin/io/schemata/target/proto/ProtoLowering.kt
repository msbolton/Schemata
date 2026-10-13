package io.schemata.target.proto

import io.schemata.core.ir.AnnotationValue
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
import io.schemata.target.ProtoFileOptions
import io.schemata.target.ProtoLayout
import io.schemata.target.ProtoPackages
import io.schemata.target.ProtoUnit
import io.schemata.target.deprecated
import io.schemata.target.isKeyRecord
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

    /** Both PHP namespace options refuse a value the same way. */
    private val PHP_NAMESPACE =
        "is not a valid PHP namespace" to
            "use identifiers separated by backslashes, for example `\"Example\\\\Orders\"`"

    /** Per string file option, why a value of the wrong shape is refused and what to write. */
    private val OPTION_PROBLEMS: Map<String, Pair<String, String>> =
        mapOf(
            "java_package" to
                ("is not a valid Java package" to
                    "use dotted identifiers, for example `com.example.orders`"),
            "csharp_namespace" to
                ("is not a valid C# namespace" to
                    "use dotted identifiers, for example `Example.Orders`"),
            "php_namespace" to PHP_NAMESPACE,
            "php_metadata_namespace" to PHP_NAMESPACE,
            "ruby_package" to
                ("is not a valid Ruby module" to
                    "use identifiers separated by `::`, for example `Example::Orders`"),
            "java_outer_classname" to
                ("is not a valid Java class name" to
                    "use a single identifier, for example `OrdersProto`"),
            "objc_class_prefix" to
                ("is not a valid Objective-C class prefix" to
                    "use a single identifier, for example `EXO`"),
            "php_class_prefix" to
                ("is not a valid PHP class prefix" to "use a single identifier, for example `Exo`"),
            "swift_prefix" to
                ("is not a valid Swift prefix" to "use a single identifier, for example `EXO`"),
            "go_package" to
                ("is empty or holds a control character" to
                    "give the Go import path, for example `example.com/orders;orderspb`"),
        )

    fun lower(written: Schema, layout: ProtoLayout = ProtoLayout.SCHEMA): Lowered<ProtoModel> {
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
        val packages = ProtoPackages.of(schema, layout)
        // Under the type layout every schema keeps its own package, so a reference cycle between
        // schemas merges nothing and there is no merge to report.
        if (layout == ProtoLayout.SCHEMA) {
            packages.units.filter { it.merged }.forEach { reportCycle(it, diagnostics) }
        }
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
        val renames = groupRenames(schema, packages, names, diagnostics)
        // Under the type layout declarations that reference each other share one file whichever
        // schemas declare them, so the files are found over every schema at once: a file's host,
        // package and name depend on all of its members.
        val components =
            if (layout == ProtoLayout.TYPE) {
                ProtoTypeFiles.components(packages) { fileName(names, renames, it) }
            } else {
                emptyList()
            }
        // A declaration moved into another scope's file may collide with a name there. The host
        // was chosen above, before these renames; each file is named after them, so compiling the
        // file's import again names it the same.
        val allRenames =
            renames + movedRenames(schema, packages, components, renames, names, diagnostics)
        val typeFiles = ProtoTypeFiles.typeFiles(components) { fileName(names, allRenames, it) }
        // A reference into another scope imports the file holding its target, so every unit is
        // partitioned before any is lowered.
        val fileOf = HashMap<QualifiedName, String>()
        val scopeOf = HashMap<QualifiedName, ProtoUnit>()
        val lowerings =
            packages.units.map { unit ->
                FileLowering(
                    schema,
                    names,
                    allRenames,
                    unit,
                    layout,
                    typeFiles.filter { it.scope === unit },
                    fileOf,
                    scopeOf,
                    diagnostics,
                )
            }
        lowerings.forEach { it.partition() }
        val files = lowerings.flatMap { it.lower() }
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
     * Reports a file option two members of a merged unit declare differently: they share one file,
     * or under the type layout one package, which holds one value per option, so that is an error
     * at the later member.
     */
    private fun optionConflict(
        conflict: ProtoFileOptions.Conflict,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val (key, first, firstValue, second, secondValue) = conflict
        val placeholder = if (ProtoFileOptions.key(key)?.bool == true) "…" else "\"…\""
        diagnostics +=
            Diagnostic(
                ProtoCodes.INVALID_OVERRIDE,
                "schemas ${first.name} and ${second.name} reference each other but declare $key " +
                    "'${OverrideNames.escaped(optionText(firstValue))}' and " +
                    "'${OverrideNames.escaped(optionText(secondValue))}'",
                second.span,
                help = "give every schema in the cycle the same `@proto($key: $placeholder)`",
            )
    }

    /** A file option's value as its text: a string as written, a boolean as `true` or `false`. */
    private fun optionText(value: AnnotationValue): String =
        when (value) {
            is AnnotationValue.Str -> value.value
            is AnnotationValue.Bool -> value.value.toString()
            else -> error("a file option is a string or a boolean, not $value")
        }

    /** A file option's value as a proto literal: a string quoted and escaped, a boolean bare. */
    private fun optionLiteral(value: AnnotationValue): String =
        if (value is AnnotationValue.Str) SchemataText.string(value.value) else optionText(value)

    /**
     * The name of the file [decl] would have of its own under the type layout: its emitted name in
     * snake case, from its valid `@proto(name)` override or the name [renames] gave it.
     */
    private fun fileName(
        names: OverrideNames,
        renames: Map<QualifiedName, String>,
        decl: TypeDecl,
    ): String =
        Names.snakeCase(names.nameOverride(decl) ?: renames[decl.qualifiedName] ?: decl.name)

    /**
     * The new names of top-level declarations that would collide in a merged unit's one package
     * scope, keyed by qualified name. The map is global because a schema outside the unit spells
     * the declaration by its emitted name too, so every file must see the rename before any file
     * lowers. A declared `@proto(name)` and a service name are chosen by the author and never
     * renamed: they claim their names first. Then members are walked in name order, and a plain
     * name that is already claimed, or used by an earlier member, is prefixed with its schema's
     * name in upper camel case (`vnd_work_orders` + `Task` → `VndWorkOrdersTask`). The earlier
     * member keeps the plain name because schema names are the one order every build agrees on, so
     * which declaration is renamed never depends on file paths or source order. Names repeated
     * within one member are the author's own collision, and two colliding declared names have no
     * name to fall back to, so both are left for the name-collision check, as is a prefixed name
     * that is itself taken.
     *
     * A key record is named for its model, `<Model>Key`, and takes the model's `@proto(name)` with
     * the suffix, so the author renames it through the model: a message about one names the model
     * whose key it is, and its help points at the models.
     */
    private fun groupRenames(
        schema: Schema,
        packages: ProtoPackages,
        names: OverrideNames,
        diagnostics: MutableList<Diagnostic>,
    ): Map<QualifiedName, String> {
        val renames = mutableMapOf<QualifiedName, String>()
        fun claim(member: Namespace, decl: TypeDecl) =
            Claim(member, schema.holder(decl), schema.isKeyRecord(decl))
        for (unit in packages.units.filter { it.merged }) {
            val claimed = mutableMapOf<String, Claim>()
            val plain = mutableListOf<Pair<Namespace, TypeDecl>>()
            for (member in unit.members) {
                for (decl in member.declarations) {
                    val declared = names.nameOverride(decl)
                    if (declared == null) plain += member to decl
                    else claimed.putIfAbsent(declared, claim(member, decl))
                }
                for (service in member.services) {
                    claimed.putIfAbsent(
                        claimedName(service),
                        Claim(member, "service '${service.qualifiedName}'", key = false),
                    )
                }
            }
            val taken = (claimed.keys + plain.map { (_, decl) -> decl.name }).toMutableSet()
            for ((member, decl) in plain) {
                val own = claim(member, decl)
                val user = claimed.putIfAbsent(decl.name, own) ?: continue
                if (user.member === member) continue
                val prefix = member.name.split('.').joinToString("") { Names.upperCamel(it) }
                val renamed = prefix + decl.name
                if (!taken.add(renamed)) continue
                renames[decl.qualifiedName] = renamed
                val those = if (own.key || user.key) "one of the models" else "one of them"
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.LOSSY,
                        "${own.holder}: proto name '${decl.name}' is also used by ${user.holder}; " +
                            "written as '$renamed'",
                        decl.nameSpan,
                        help = "set `@proto(name: \"…\")` on $those to choose the name",
                    )
            }
        }
        return renames
    }

    /**
     * The name [service] claims in its package scope: its valid `@proto(name)`, else its own. A
     * service whose override is invalid keeps its own name; its file reports the override.
     */
    private fun claimedName(service: Service): String =
        service.annotations.string("proto", "name")?.takeIf { ProtoNames.isIdentifier(it) }
            ?: service.name

    /**
     * The new names of declarations moved into another scope's file that would collide there, keyed
     * by qualified name. Under the type layout each package is its own scope, and a declaration in
     * a cycle with a declaration of another scope's schema can be written in that schema's file,
     * the host's, and so joins the host's scope. The scope's own declarations that stay and its
     * services claim their names first: their schema put them there. Then moved declarations with a
     * valid `@proto(name)`, or a name their own cycle's renames gave them, claim theirs; the author
     * or that rename chose it, so it never changes. Then each moved plain name, component by
     * component in file order, that another schema already claimed is prefixed with its schema's
     * name in upper camel case, with the same note as a cycle's renames. A prefixed name that is
     * itself taken is left for the name-collision check. The host was chosen before these renames,
     * so they never move a file to another package; the file is named by the smallest name after
     * them.
     */
    private fun movedRenames(
        schema: Schema,
        packages: ProtoPackages,
        components: List<TypeComponent>,
        renames: Map<QualifiedName, String>,
        names: OverrideNames,
        diagnostics: MutableList<Diagnostic>,
    ): Map<QualifiedName, String> {
        val movedNames = components.flatMap { it.moved }.map { it.qualifiedName }.toSet()
        if (movedNames.isEmpty()) return emptyMap()
        val byName = packages.units.flatMap { it.members }.associateBy { it.name }
        fun claim(decl: TypeDecl) =
            Claim(
                byName.getValue(decl.qualifiedName.namespace),
                schema.holder(decl),
                schema.isKeyRecord(decl),
            )
        val result = mutableMapOf<QualifiedName, String>()
        for (scope in packages.units) {
            val incoming = components.filter { it.scope === scope }.flatMap { it.moved }
            if (incoming.isEmpty()) continue
            val claimed = mutableMapOf<String, Claim>()
            for (member in scope.members) {
                for (decl in member.declarations) {
                    if (decl.qualifiedName in movedNames) continue
                    val name = names.nameOverride(decl) ?: renames[decl.qualifiedName] ?: decl.name
                    claimed.putIfAbsent(name, claim(decl))
                }
                for (service in member.services) {
                    claimed.putIfAbsent(
                        claimedName(service),
                        Claim(member, "service '${service.qualifiedName}'", key = false),
                    )
                }
            }
            val plain = mutableListOf<TypeDecl>()
            for (decl in incoming) {
                val chosen = names.nameOverride(decl) ?: renames[decl.qualifiedName]
                if (chosen == null) plain += decl else claimed.putIfAbsent(chosen, claim(decl))
            }
            val taken = (claimed.keys + plain.map { it.name }).toMutableSet()
            for (decl in plain) {
                val own = claim(decl)
                val user = claimed.putIfAbsent(decl.name, own) ?: continue
                if (user.member === own.member) continue
                val prefix = own.member.name.split('.').joinToString("") { Names.upperCamel(it) }
                val renamed = prefix + decl.name
                if (!taken.add(renamed)) continue
                result[decl.qualifiedName] = renamed
                val those = if (own.key || user.key) "one of the models" else "one of them"
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.LOSSY,
                        "${own.holder}: proto name '${decl.name}' is also used by ${user.holder}; " +
                            "written as '$renamed'",
                        decl.nameSpan,
                        help = "set `@proto(name: \"…\")` on $those to choose the name",
                    )
            }
        }
        return result
    }

    /**
     * A name claimed in a merged unit's package scope: the [member] that claimed it, how a message
     * names the claimant, and whether it is a [key] record.
     */
    private class Claim(val member: Namespace, val holder: String, val key: Boolean)

    /**
     * How a message names [decl] for a reader of the merged file: by its qualified name, and a key
     * record, which the author never wrote, as the key of its model.
     */
    private fun Schema.holder(decl: TypeDecl): String =
        if (!isKeyRecord(decl)) "${decl.kindWord} '${decl.qualifiedName}'"
        else "the key of model '${keyModel(decl)}'"

    /** The model whose key the key record [decl] holds: `SeatKey` → `Seat`, beside it. */
    private fun keyModel(decl: TypeDecl): QualifiedName {
        val qn = decl.qualifiedName
        return QualifiedName(qn.namespace, qn.path.dropLast(1) + decl.name.removeSuffix("Key"))
    }

    private class Mapped(val type: ProtoType, val label: Label, val lossy: Boolean)

    /**
     * Lowers one [unit] to its files. Under the schema layout the unit is a schema or a reference
     * cycle of schemas; its members share its package scope and one file: every member's
     * declarations, member by member in name order, then every member's services. Under the type
     * layout the unit is a package scope, a schema or the schemas of one cycle that declare one
     * package, and its files are [typeFiles], found over every schema at once, and one file per
     * service. A type file of this scope may hold declarations of other scopes' schemas that
     * reference one of its declarations in a cycle, and a declaration of this scope may sit in
     * another scope's file. [fileOf] and [scopeOf] map every top-level declaration of every unit to
     * the path of its file and to the scope that file belongs to; [partition] fills this unit's
     * part, and [lower] reads all of it.
     */
    private class FileLowering(
        private val schema: Schema,
        private val names: OverrideNames,
        private val renames: Map<QualifiedName, String>,
        private val unit: ProtoUnit,
        private val layout: ProtoLayout,
        private val typeFiles: List<TypeFile>,
        private val fileOf: MutableMap<QualifiedName, String>,
        private val scopeOf: MutableMap<QualifiedName, ProtoUnit>,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /** The imports of the file being lowered, the one at [current]. */
        private val imports = sortedSetOf<String>()
        private var current = unit.path

        /**
         * The top-level declarations the unit's files hold, set by [partition]. Under the type
         * layout they are the scope's: its members' declarations that stay and the declarations
         * moved into its files, in source order with schemas by name.
         */
        private var typeDecls: List<TypeDecl> = emptyList()
        private val serviceDecls = unit.members.flatMap { it.services }

        // Computed once: an invalid service override is reported each time it is read. Lazy so
        // that under the schema layout an invalid one is reported with the unit's scope check,
        // after its package and options and before the declarations' invalid overrides.
        private val serviceNames by lazy { serviceDecls.associateWith { serviceName(it) } }
        private var files: List<TypeFile> = emptyList()

        /**
         * protoc names a file's Java wrapper class after the file, and with `java_multiple_files` a
         * message whose name differs from it only by case would be written to the same Java file
         * name, which a case-insensitive file system holds once. So under the type layout every
         * file of a scope whose options set `java_multiple_files: true` names its wrapper itself,
         * unless the scope's options set `java_outer_classname`, which only a scope writing one
         * file may.
         */
        private val namesWrappers: Boolean by lazy {
            layout == ProtoLayout.TYPE &&
                ProtoFileOptions.of(unit).values.let {
                    it["java_multiple_files"] == AnnotationValue.Bool(true) &&
                        "java_outer_classname" !in it
                }
        }

        /**
         * [decl]'s emitted name: its valid `@proto(name)` override, else the name it was given to
         * avoid a collision in its cycle's file, else its own name.
         */
        private fun emitted(decl: TypeDecl): String =
            names.nameOverride(decl) ?: renames[decl.qualifiedName] ?: decl.name

        /**
         * The note that keeps what proto lost of [type]. A reference in it is spelled by its
         * emitted name, the name the field's proto type ends with, so that a reader of the file can
         * tell the note belongs to the type.
         */
        private fun note(type: Type, nullable: Boolean = false): String =
            ProtoTypes.text(type, nullable) { emitted(schema.lookup(it)) }

        /**
         * Spreads the unit over its files and records where each top-level declaration lives and
         * which scope's file holds it.
         */
        fun partition() {
            files =
                when (layout) {
                    ProtoLayout.SCHEMA -> listOf(ProtoTypeFiles.whole(unit))
                    ProtoLayout.TYPE ->
                        (typeFiles +
                                ProtoTypeFiles.serviceFiles(unit) {
                                    Names.snakeCase(serviceNames.getValue(it))
                                })
                            .sortedBy { it.path }
                }
            for (file in files) {
                for (decl in file.declarations) {
                    fileOf[decl.qualifiedName] = file.path
                    scopeOf[decl.qualifiedName] = unit
                }
            }
            typeDecls =
                when (layout) {
                    ProtoLayout.SCHEMA -> unit.members.flatMap { it.declarations }
                    ProtoLayout.TYPE -> {
                        val held =
                            files
                                .flatMap { file -> file.declarations.map { it.qualifiedName } }
                                .toSet()
                        schema.namespaces
                            .sortedBy { it.name }
                            .flatMap { it.declarations }
                            .filter { it.qualifiedName in held }
                    }
                }
            if (layout == ProtoLayout.TYPE) {
                for (file in files) {
                    if (file.shared) sharedFile(file)
                    movedFile(file)
                }
                fileCollisions()
                wrapperCollisions()
            }
        }

        fun lower(): List<ProtoFile> {
            unit.members.forEach { declaredPackage(it) }
            unit.members.forEach { declaredOptions(it) }
            if (layout == ProtoLayout.TYPE && files.size > 1) {
                unit.members.forEach { outerClassname(it) }
            }
            // Each option takes one value across the unit, reported once if its members disagree.
            // Under the schema layout the members share their file. Under the type layout they
            // share one package, and the importer reads a package back as one schema with one
            // value per option, so files of one package that disagreed could not be compiled back
            // from their import. A file holding declarations moved from another scope takes this
            // scope's options, never theirs.
            val resolved = ProtoFileOptions.of(unit)
            resolved.conflicts.forEach { optionConflict(it, diagnostics) }
            val unitOptions = literals(resolved.values)
            // Every file of the unit is one package scope, and services share it with messages
            // and enums. An invalid service override is reported before the declarations' own.
            val serviceSymbols =
                serviceDecls.map {
                    Symbol(serviceNames.getValue(it), "service '${it.name}'", it.nameSpan)
                }
            scope(typeDecls.flatMap { symbols(it) } + serviceSymbols)
            return files.map { file ->
                imports.clear()
                current = file.path
                val declarations = file.declarations.map { decl(it) }
                val services = file.services.map { service(it, serviceNames.getValue(it)) }
                ProtoFile(
                    path = file.path,
                    packageName = file.scope.packageName,
                    imports = imports.toList(),
                    declarations = declarations,
                    services = services,
                    options = fileOptions(unitOptions, file),
                )
            }
        }

        /** Option [values] as proto literals, in key order. */
        private fun literals(values: Map<String, AnnotationValue>): Map<String, String> =
            values.mapValues { (_, value) -> optionLiteral(value) }

        /**
         * [options] as [file] writes them, in key order: a wrapper class name the file chooses
         * itself takes `java_outer_classname`'s place, after `java_package`.
         */
        private fun fileOptions(
            options: Map<String, String>,
            file: TypeFile,
        ): List<Pair<String, String>> =
            ProtoFileOptions.keys.mapNotNull { key ->
                if (namesWrappers && key.name == "java_outer_classname") {
                    key.name to SchemataText.string(wrapper(file))
                } else {
                    options[key.name]?.let { key.name to it }
                }
            }

        /** `http_status.proto` → `HttpStatusProto`: the file's stem in upper camel case. */
        private fun wrapper(file: TypeFile): String =
            Names.upperCamel(file.path.substringAfterLast('/').removeSuffix(".proto")) + "Proto"

        /** The member whose name [file] takes; every type file has one. */
        private fun naming(file: TypeFile): TypeDecl =
            checkNotNull(file.naming) { "a type file is named after one of its declarations" }

        /**
         * Reports why declarations share [file]: protoc rejects files that import each other, so
         * declarations that reference each other cannot be files of their own. The note sits at the
         * member that names the file, which is listed first and is the one an `@proto(name)`
         * renames the file through; the others follow in file order.
         */
        private fun sharedFile(file: TypeFile) {
            val naming = naming(file)
            val listed =
                (listOf(naming) + (file.declarations - naming)).map {
                    "${it.kindWord} '${emitted(it)}'"
                }
            lossy(
                "${listed.dropLast(1).joinToString(", ")} and ${listed.last()} reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share ${file.path}",
                naming.nameSpan,
                help = "set `@proto(name: \"…\")` on '${emitted(naming)}' to name the file",
            )
        }

        /**
         * Reports the declarations of other scopes' schemas that [file] holds. Each references a
         * declaration of the file's host schema in a cycle, and protoc rejects files that import
         * each other, so it is written in the host's file, under the package and with the options
         * of the host's scope rather than its own. The note sits at the first of them; its help
         * names the two ways to give a declaration back to its own schema's package.
         */
        private fun movedFile(file: TypeFile) {
            if (file.moved.isEmpty()) return
            val listed = file.moved.map { "${it.kindWord} '${it.qualifiedName}'" }
            val subject =
                if (listed.size == 1) "${listed.single()} is"
                else "${listed.dropLast(1).joinToString(", ")} and ${listed.last()} are"
            lossy(
                "$subject written under package '${file.scope.packageName}' in ${file.path}",
                file.moved.first().nameSpan,
                help =
                    "break the reference cycle, or set `@proto(name: \"…\")` so that a declaration " +
                        "of its own schema names the file",
            )
        }

        /**
         * What names one file in a collision message: its [text], the [name] it is emitted as, the
         * [span] a report sits at, and for a key record the emitted name of the [model] whose key
         * it is, since the author renames a key record only through its model.
         */
        private class FileHolder(
            val text: String,
            val name: String,
            val span: Span,
            val model: String?,
        )

        private fun holder(file: TypeFile): FileHolder {
            val service = file.services.firstOrNull()
            if (service != null) {
                val name = serviceNames.getValue(service)
                return FileHolder("service '$name'", name, service.nameSpan, null)
            }
            val naming = naming(file)
            val name = emitted(naming)
            if (!schema.isKeyRecord(naming)) {
                return FileHolder("${naming.kindWord} '$name'", name, naming.nameSpan, null)
            }
            val model = emitted(schema.lookup(keyModel(naming)))
            return FileHolder("the key of model '$model'", name, naming.nameSpan, model)
        }

        /**
         * Reports every file whose path an earlier file of the unit already has: one would
         * overwrite the other. Files are sorted by path, and declarations come before services, so
         * the report sits at the later one. Each file is named by its emitted name, which is the
         * name the message gives. Two files emitted under one name are already a repeat in the
         * package scope, which the scope check reports, so they are not reported again here.
         */
        private fun fileCollisions() {
            for (same in files.groupBy { it.path }.values.filter { it.size > 1 }) {
                val holders = same.map { holder(it) }
                val first = holders.first()
                for ((i, later) in holders.withIndex().drop(1)) {
                    if (holders.take(i).any { it.name == later.name }) continue
                    val help =
                        when {
                            later.model != null -> "on model '${later.model}'"
                            first.model != null -> "on ${later.text}"
                            else -> "on one of them"
                        }
                    diagnostics +=
                        Diagnostic(
                            ProtoCodes.NAME_COLLISION,
                            "${first.text} and ${later.text} both lower to file '${same[i].path}'",
                            later.span,
                            help = "set `@proto(name: \"…\")` $help",
                        )
                }
            }
        }

        /**
         * Reports every top-level declaration or service whose emitted name is, ignoring case, the
         * wrapper class name of a file of the scope: the scope's options put every file it writes
         * in one Java package, whichever schema declared what the file holds, so both would become
         * one Java file. Declarations come in source order with schemas by name, then services.
         */
        private fun wrapperCollisions() {
            if (!namesWrappers) return
            val wrappers = files.associateBy { wrapper(it).lowercase() }
            val named =
                typeDecls.map { Triple(it.kindWord, emitted(it), it.nameSpan) } +
                    serviceDecls.map { Triple("service", serviceNames.getValue(it), it.nameSpan) }
            for ((kind, name, span) in named) {
                val file = wrappers[name.lowercase()] ?: continue
                diagnostics +=
                    Diagnostic(
                        ProtoCodes.NAME_COLLISION,
                        "$kind '$name' and the Java class of ${file.path.substringAfterLast('/')} " +
                            "both lower to '${wrapper(file)}'",
                        span,
                        help = "set `@proto(name: \"…\")` on it",
                    )
            }
        }

        /**
         * `java_outer_classname` names the Java class that wraps one file's messages. Under the
         * type layout every file of a scope takes the scope's options, so every file it writes
         * would claim that one class, and when it writes several the generated code would not
         * compile. Each member declaring the key is reported. A scope writing one file keeps it, as
         * the schema layout does; a file holding declarations moved from other scopes counts for
         * its host's scope only.
         */
        private fun outerClassname(namespace: Namespace) {
            val value =
                ProtoFileOptions.declared(namespace)["java_outer_classname"] as? AnnotationValue.Str
                    ?: return
            invalidOverride(
                "schema '${namespace.name}': " +
                    "@proto(java_outer_classname: \"${OverrideNames.escaped(value.value)}\") " +
                    "names one Java class for every file of the type layout",
                namespace.span,
                help = "drop the key or compile with --proto-layout schema",
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

        /**
         * Each string option [namespace] declares must have its key's shape, or the generator of
         * its language would refuse it or write code that does not compile. A boolean always fits.
         */
        private fun declaredOptions(namespace: Namespace) {
            val declared = ProtoFileOptions.declared(namespace)
            for (key in ProtoFileOptions.keys) {
                val value = declared[key.name] as? AnnotationValue.Str ?: continue
                if (ProtoNames.fitsOption(key.shape, value.value)) continue
                val (tail, help) = OPTION_PROBLEMS.getValue(key.name)
                invalidOverride(
                    "schema '${namespace.name}': " +
                        "@proto(${key.name}: \"${OverrideNames.escaped(value.value)}\") $tail",
                    namespace.span,
                    help = help,
                )
            }
        }

        private fun serviceName(service: Service): String =
            names.overrideName(service.annotations, "service '${service.name}'", service.nameSpan)
                ?: service.name

        /**
         * A service's rpcs, named in a scope of their own. What proto cannot spell rides in
         * schemata notes: an ordinal unless it is both the rpc's 1-based position and one past the
         * ordinal before it, so an importer that reads a missing ordinal either way gets it back;
         * the HTTP binding as the formatter prints it; and the service's `reserved` statement.
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
                    val previous = if (index == 0) 0 else service.operations[index - 1].ordinal
                    if (op.ordinal != index + 1 || op.ordinal != previous + 1) {
                        notes += "#${op.ordinal}"
                    }
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
            if (mapped.lossy) notes += note(field.type, field.nullable)
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
                    val notes = if (mapped.lossy) listOf(note(member.type)) else emptyList()
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
         * Spells a reference as proto resolves it from a message at [here], and imports the file
         * holding the type when it is not this one. A type whose file is in another scope is
         * package-qualified with a leading dot, which proto resolves absolutely. A type whose file
         * is in this scope (from this schema, another schema of its package, or another schema
         * whose declaration one of this scope's files holds) is spelled relatively, by its
         * remaining path, so that the file reads back as one schema with the same spelling: every
         * declaration the scope's files hold sits in its one package scope, whichever file holds
         * it. Proto looks a relative name up from the innermost enclosing message outward, so where
         * a declaration nested along [here]'s path has the name the relative spelling starts with,
         * that name would capture the reference, and it takes the absolute form instead.
         */
        private fun reference(target: QualifiedName, here: QualifiedName): ProtoType.Named {
            val path = schema.declarationPath(target).map { emitted(it) }
            val top = QualifiedName(target.namespace, target.path.take(1))
            // The scope of the file holding the target: under the type layout a declaration moved
            // into another schema's file takes that file's package.
            val home = scopeOf.getValue(top)
            val file = fileOf.getValue(top)
            if (home !== unit) {
                imports += file
                return ProtoType.Named(".${home.packageName}.${path.joinToString(".")}")
            }
            // Another file of this scope is imported too; under the schema layout the unit is one
            // file, so nothing is.
            if (file != current) imports += file
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
