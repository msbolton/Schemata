package io.schemata.importer.sql

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.NoteText
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnionMember
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.target.Names
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Postgres tables read back into Schemata records. Every table becomes a record unless it is a
 * child table; then each record's columns are read left to right by the structures the SQL target
 * writes, recognised by their shape (key columns, foreign-key targets, check expressions) and never
 * by a constraint's name, so a hand-written file reads as the target's own output does:
 * - a `<f>_kind` text column whose `IN` check lists the members, with member columns `<f>_<m>` or
 *   `<f>_<m>_…` and per-member presence checks, is a union field;
 * - a column group under one prefix with an all-or-none check is a nullable embedded record;
 * - a foreign key to a table's primary key is a reference;
 * - any other column is a scalar, an enum (`text` with an `IN` check), a list (an array), or a
 *   value stored as json, its checks becoming refinements;
 * - a table keyed by a parent's key plus `position` (a list) or `key` (a map), with a cascading
 *   foreign key to that parent, is a list or map field of the parent.
 */
internal object SqlImport {
    fun lower(
        files: List<SqlFile>,
        namespaces: Map<String, SchemaNamespace>,
        diagnostics: MutableList<Diagnostic>,
    ): List<SchemataUnit> = Lowering(Catalog(files, diagnostics), namespaces, diagnostics).units()
}

private val BUILTINS =
    setOf(
        "bool",
        "int32",
        "int64",
        "float32",
        "float64",
        "decimal",
        "string",
        "bytes",
        "uuid",
        "date",
        "time",
        "instant",
        "duration",
    )

// ---- the catalog ----

private class CheckInfo(val check: SqlConstraint.Check) {
    var consumed = false
    val expr: SqlExpr
        get() = check.expr
}

private class FkInfo(val fk: SqlConstraint.ForeignKey) {
    var consumed = false
    val columns: List<String>
        get() = fk.columns
}

/** One table with everything later statements say about it folded in. */
private class TableInfo(val schema: String, val table: SqlTable, val file: SqlFile) {
    val key = "$schema.${table.name}"
    val name: String
        get() = table.name

    var pk: List<String>? = null
    val uniques = mutableListOf<List<String>>()
    val checks = mutableListOf<CheckInfo>()
    val fks = mutableListOf<FkInfo>()
    val indexes = mutableListOf<SqlStatement.CreateIndex>()
    var doc: String? = null
    val columnDocs = mutableMapOf<String, String>()
    var child: ChildInfo? = null

    fun column(name: String): SqlColumn? = table.columns.firstOrNull { it.name == name }

    fun add(c: SqlConstraint) {
        when (c) {
            is SqlConstraint.PrimaryKey -> if (pk == null) pk = c.columns
            is SqlConstraint.Unique -> uniques += c.columns
            is SqlConstraint.Check -> checks += CheckInfo(c)
            is SqlConstraint.ForeignKey -> fks += FkInfo(c)
        }
    }
}

/**
 * A child table of [parent]: [field] is the parent's field it lowers to, a map when keyed by `key`,
 * a list when keyed by `position`.
 */
private class ChildInfo(
    val info: TableInfo,
    val parent: TableInfo,
    val field: String,
    val map: Boolean,
    val parentFk: FkInfo,
) {
    val keyColumns: List<String>
        get() = parentFk.columns + (if (map) "key" else "position")
}

/**
 * Every table across the inputs, keyed `schema.table` in the order created, with the `ALTER TABLE …
 * ADD`, `CREATE INDEX`, and `COMMENT ON` statements of every file attached to the table they name,
 * and the child tables found.
 */
private class Catalog(files: List<SqlFile>, diagnostics: MutableList<Diagnostic>) {
    val tables = LinkedHashMap<String, TableInfo>()
    val children = LinkedHashMap<String, MutableList<ChildInfo>>()

    init {
        for (f in files) {
            for (s in f.statements.filterIsInstance<SqlStatement.CreateTable>()) {
                val info = TableInfo(s.table.schema ?: PUBLIC, s.table, f)
                if (tables.putIfAbsent(info.key, info) != null) {
                    report(
                        diagnostics,
                        f,
                        ImportCodes.UNRESOLVED,
                        "${f.path}: table '${info.key}' is created twice; the second is ignored",
                        s.table.pos,
                        "remove one of them",
                    )
                }
            }
        }
        tables.values.forEach { t -> t.table.constraints.forEach(t::add) }
        for (f in files) {
            for (s in f.statements) {
                when (s) {
                    is SqlStatement.AlterAdd -> resolve(s.schema, s.table)?.add(s.constraint)
                    is SqlStatement.CreateIndex -> resolve(s.schema, s.table)?.indexes?.add(s)
                    is SqlStatement.CommentOn ->
                        resolve(s.schema, s.table)?.let { t ->
                            if (s.column == null) t.doc = s.text
                            else t.columnDocs[s.column] = s.text
                        }
                    else -> {}
                }
            }
        }
        tables.values.forEach(::findParent)
    }

    /**
     * The table [name] in [schema]; unqualified, the one in `public`, else the only table of that
     * name in any schema.
     */
    fun resolve(schema: String?, name: String): TableInfo? =
        if (schema != null) tables["$schema.$name"]
        else tables["$PUBLIC.$name"] ?: tables.values.singleOrNull { it.name == name }

    /**
     * Makes [t] a child of the table its cascading foreign key points at, when the key, the foreign
     * key, and the name all have the shape the SQL target gives a child table: the parent's key
     * columns, each prefixed with the parent's name, then `position` (an integer) or `key`, with
     * the table named `<parent>_<field>`.
     */
    private fun findParent(t: TableInfo) {
        val pk = t.pk ?: return
        for (fk in t.fks) {
            if (fk.fk.onDelete != "CASCADE") continue
            val p = resolve(fk.fk.refSchema, fk.fk.refTable) ?: continue
            if (p === t) continue
            val parentKey = p.pk ?: continue
            if (fk.fk.refColumns.ifEmpty { parentKey } != parentKey) continue
            if (fk.columns != parentKey.map { "${p.name}_$it" }) continue
            if (pk.size != fk.columns.size + 1 || pk.dropLast(1) != fk.columns) continue
            val map =
                when (pk.last()) {
                    "position" -> if (t.column("position")?.type == "integer") false else continue
                    "key" -> true
                    else -> continue
                }
            val prefix = "${p.name}_"
            if (!t.name.startsWith(prefix) || t.name.length == prefix.length) continue
            val child = ChildInfo(t, p, t.name.removePrefix(prefix), map, fk)
            fk.consumed = true
            t.child = child
            children.getOrPut(p.key) { mutableListOf() } += child
            return
        }
    }
}

// ---- what a record lowers to, before it is built ----

private sealed interface Nested {
    val name: String

    fun decl(): UnitDecl
}

private class FixedDecl(val value: UnitDecl) : Nested {
    override val name: String
        get() = value.name

    override fun decl(): UnitDecl = value
}

/**
 * A record being lowered: its fields as [Slot]s, which keys, uniques, and indexes still mark, and
 * its nested declarations. Nested names avoid [reserved] (the namespace's records) and every
 * enclosing record's name. [key] is a primary key whose fields are not in key order, and [uniques]
 * and [indexes] the constraints over more than one field, each as the fields' names.
 */
private class RecordSpec(
    override var name: String,
    val parent: RecordSpec?,
    private val reserved: Set<String>,
) : Nested {
    val slots = mutableListOf<Slot>()
    val nested = mutableListOf<Nested>()
    val annotations = mutableListOf<UnitAnnotation>()
    var key: List<String> = emptyList()
    val uniques = mutableListOf<List<String>>()
    val indexes = mutableListOf<List<String>>()
    var doc: String? = null
    private val names = mutableSetOf<String>()
    private val fieldNames = mutableSetOf<String>()

    fun child(name: String) = RecordSpec(name, this, reserved)

    private fun taken(n: String): Boolean =
        n in names || n in reserved || generateSequence(this) { it.parent }.any { it.name == n }

    /** [base], else [base] + [suffix], else numbered from 2; claimed for a nested declaration. */
    fun claim(base: String, suffix: String = ""): String {
        val first = listOf(base, base + suffix).firstOrNull { !taken(it) }
        val name =
            first ?: generateSequence(2) { it + 1 }.map { "$base$suffix$it" }.first { !taken(it) }
        names += name
        return name
    }

    /** A nested declaration called [n] here or in an enclosing record. */
    fun lookup(n: String): Nested? = nested.firstOrNull { it.name == n } ?: parent?.lookup(n)

    /**
     * A field name for [raw]: lower-snake, numbered past a taken one, and the column it renames.
     */
    fun fieldName(raw: String): Pair<String, String?> {
        val base = if (ImportNames.isLowerSnake(raw)) raw else ImportNames.lowerSnake(raw)
        val name =
            generateSequence(1) { it + 1 }
                .map { if (it == 1) base else "${base}_$it" }
                .first { it !in fieldNames }
        fieldNames += name
        return name to raw.takeIf { it != name }
    }

    override fun decl(): UnitRecord =
        UnitRecord(
            name,
            slots.map { it.build() },
            nested.map { it.decl() },
            doc,
            annotations,
            key = key,
            uniques = uniques,
            indexes = indexes,
        )
}

/**
 * One field being lowered. [columns] are the columns it stands for, which a unique, index, or
 * primary key must match; [inner] are the records whose own fields stand for some of them.
 * [onDelete] is a reference's `ON DELETE` action as the language spells it.
 */
private class Slot(
    val name: String,
    var type: UnitType,
    val nullable: Boolean,
    val default: String?,
    val doc: String?,
    val columns: List<String>,
    val scalar: Boolean,
    val inner: List<RecordSpec> = emptyList(),
) {
    var anchor = 0
    var key = false
    var unique = false
    var index = false
    var column: String? = null
    var sqlType: String? = null
    var strategy: String? = null
    var onDelete: String? = null

    fun build(): UnitField =
        UnitField(
            name,
            type,
            nullable,
            default,
            doc,
            buildList {
                column?.let { add(UnitAnnotation("sql", "column", SchemataText.string(it))) }
                sqlType?.let { add(UnitAnnotation("sql", "type", SchemataText.string(it))) }
                strategy?.let { add(UnitAnnotation("sql", "strategy", it)) }
            },
            options =
                buildList {
                    if (key) add("id" to null)
                    if (unique) add("unique" to null)
                    if (index) add("index" to null)
                },
            onDelete = onDelete,
        )
}

/** A column as one level of the lowering sees it: [local] is its name past the level's prefix. */
private class Col(val column: SqlColumn, val local: String, val nullable: Boolean) {
    val name: String
        get() = column.name
}

/** What one top-level record's lowering shares: its namespace, imports, and json fields. */
private class Run(
    val namespace: String,
    val imports: MutableSet<String>,
    val topNames: Set<String>,
) {
    val json = mutableListOf<JsonUse>()
}

/** A table being lowered into a record; [label] names the record in messages. */
private class TableCtx(val info: TableInfo, val label: String, val run: Run)

/** A field stored as json whose note names types to find once the record is complete. */
private class JsonUse(
    val owner: RecordSpec,
    val slot: Slot,
    val ctx: TableCtx,
    val column: SqlColumn,
)

/** A scalar column's type and what goes with it. */
private class ScalarOut(
    val type: UnitType,
    val default: String?,
    val sqlType: String?,
    val strategy: String?,
    val json: Boolean,
    val keyable: Boolean,
)

private class Lowering(
    val catalog: Catalog,
    val namespaces: Map<String, SchemaNamespace>,
    val diagnostics: MutableList<Diagnostic>,
) {
    /** Where diagnostics go; a column group collects its own to report them in column order. */
    private var sink: MutableList<Diagnostic> = diagnostics

    /** The record each table that is not a child table lowers to, unique within its namespace. */
    private val recordNames = LinkedHashMap<String, String>()

    init {
        catalog.tables.values
            .filter { it.child == null }
            .groupBy { namespaceOf(it) }
            .values
            .forEach { group ->
                val taken = mutableSetOf<String>()
                group.forEach { t ->
                    val base = ImportNames.upperCamel(t.name)
                    val name =
                        generateSequence(1) { it + 1 }
                            .map { if (it == 1) base else "$base$it" }
                            .first { it !in taken }
                    taken += name
                    recordNames[t.key] = name
                }
            }
    }

    private fun namespaceOf(t: TableInfo): String = namespaces.getValue(t.schema).name

    fun units(): List<SchemataUnit> =
        namespaces.entries.groupBy({ it.value.name }, { it.key }).mapNotNull { (namespace, schemas)
            ->
            val tables = catalog.tables.values.filter { it.schema in schemas && it.child == null }
            if (tables.isEmpty()) return@mapNotNull null
            val first = namespaces.getValue(schemas.first())
            val topNames = tables.map { recordNames.getValue(it.key) }.toSet()
            val imports = LinkedHashSet<String>()
            val records = tables.map { record(it, Run(namespace, imports, topNames)) }
            SchemataUnit(
                namespace,
                first.annotations,
                null,
                imports.toList(),
                records,
                sourcePath = first.sourcePath,
            )
        }

    // ---- diagnostics ----

    private fun say(ctx: TableCtx, column: SqlColumn, code: DiagnosticCode, message: String) {
        report(
            sink,
            ctx.info.file,
            code,
            "column '${ctx.label}.${column.name}': $message",
            column.pos,
        )
    }

    private fun sayTable(info: TableInfo, code: DiagnosticCode, message: String) {
        report(sink, info.file, code, "table '${info.name}': $message", info.table.pos)
    }

    private inline fun <T> capture(into: MutableList<Diagnostic>, block: () -> T): T {
        val saved = sink
        sink = into
        try {
            return block()
        } finally {
            sink = saved
        }
    }

    // ---- records ----

    private fun record(info: TableInfo, run: Run): UnitRecord {
        val name = recordNames.getValue(info.key)
        val spec = RecordSpec(name, null, run.topNames)
        spec.doc = info.doc
        if (Names.snakeCase(name) != info.name) {
            spec.annotations += UnitAnnotation("sql", "table", SchemataText.string(info.name))
        }
        val ctx = TableCtx(info, name, run)
        reportDropped(ctx)
        val pk = info.pk.orEmpty().toSet()
        dropKeyReferences(ctx, pk)
        val cols = info.table.columns.map { Col(it, it.name, !it.notNull && it.name !in pk) }
        spec.slots += lowerColumns(ctx, spec, cols, "", keyColumns = pk)
        spec.slots += childSlots(ctx, spec)
        keys(ctx, spec)
        constraints(ctx, spec)
        leftovers(ctx)
        resolveJson(run)
        return spec.decl()
    }

    private fun reportDropped(ctx: TableCtx) {
        ctx.info.table.dropped.forEach { what ->
            val generated = Regex("^GENERATED … STORED on column '(.*)'$").find(what)
            val column = generated?.let { ctx.info.column(it.groupValues[1]) }
            if (column != null) {
                say(
                    ctx,
                    column,
                    ImportCodes.DROPPED,
                    "GENERATED … STORED dropped; imported as a plain column",
                )
            } else sayTable(ctx.info, ImportCodes.DROPPED, "$what dropped")
        }
    }

    /**
     * A foreign key over any of the primary key's columns [pk] cannot be a reference, since a key
     * field cannot be one: its columns stay plain key fields and the foreign key is dropped. One
     * that would not have been a reference anyway is reported as such.
     */
    private fun dropKeyReferences(ctx: TableCtx, pk: Set<String>) {
        for (fk in ctx.info.fks) {
            if (fk.consumed || fk.columns.none { it in pk }) continue
            val first = ctx.info.column(fk.columns.first()) ?: continue
            if (refTarget(ctx, fk, first) == null) continue
            fk.consumed = true
            sayTable(
                ctx.info,
                ImportCodes.DROPPED,
                "foreign key over (${fk.columns.joinToString(", ")}) dropped; a key field cannot be a reference",
            )
        }
    }

    /**
     * The fields [cols] lower to, one level of a record: unions first, then embedded records, then
     * references, then scalars, each claiming its columns, in the order of each field's first
     * column. [prefix] is what [cols]' names carry beyond their [Col.local] names. A column of
     * [keyColumns] is always a plain scalar, since a key field can be nothing else.
     */
    private fun lowerColumns(
        ctx: TableCtx,
        spec: RecordSpec,
        cols: List<Col>,
        prefix: String,
        keyColumns: Set<String> = emptySet(),
    ): List<Slot> {
        val index = cols.withIndex().associate { it.value.name to it.index }
        val claimed = keyColumns.filterTo(mutableSetOf()) { it in index }
        val slots = mutableListOf<Slot>()
        val notes = mutableListOf<Pair<Int, List<Diagnostic>>>()
        fun step(anchor: Int, block: () -> Slot?) {
            val found = mutableListOf<Diagnostic>()
            capture(found, block)?.let {
                it.anchor = anchor
                slots += it
            }
            notes += anchor to found
        }
        for (c in cols) {
            if (c.name !in claimed) {
                step(index.getValue(c.name)) { union(ctx, spec, cols, prefix, c, claimed) }
            }
        }
        for ((check, columns) in embedCandidates(ctx, cols, claimed, index)) {
            val first = columns.minOf { index.getValue(it) }
            step(first) { embed(ctx, spec, cols, prefix, check, columns, claimed, index) }
        }
        ctx.info.fks
            .filter { fk -> !fk.consumed && fk.columns.all { it in index && it !in claimed } }
            .sortedBy { fk -> index.getValue(fk.columns.first()) }
            .forEach { fk ->
                step(index.getValue(fk.columns.first())) {
                    if (fk.consumed || fk.columns.any { it in claimed }) null
                    else reference(ctx, spec, cols, fk, claimed)
                }
            }
        for (c in cols) {
            if (c.name !in claimed || c.name in keyColumns) {
                step(index.getValue(c.name)) {
                    claimed += c.name
                    scalarSlot(ctx, spec, c, key = c.name in keyColumns)
                }
            }
        }
        notes.sortedBy { it.first }.forEach { sink += it.second }
        return slots.sortedBy { it.anchor }
    }

    // ---- unions ----

    /**
     * The union field whose kind column is [c], when [c] is `<f>_kind` with an `IN` check of the
     * members and at least one member has columns of its own; otherwise null, and [c] is an
     * ordinary column (an enum, when it is text).
     */
    private fun union(
        ctx: TableCtx,
        spec: RecordSpec,
        cols: List<Col>,
        prefix: String,
        c: Col,
        claimed: MutableSet<String>,
    ): Slot? {
        if (!c.local.endsWith("_kind") || c.local.length <= "_kind".length) return null
        val f = c.local.removeSuffix("_kind")
        val kindCheck =
            ctx.info.checks.firstOrNull { !it.consumed && inList(it.expr, c.name) != null }
                ?: return null
        val members = inList(kindCheck.expr, c.name)!!
        if (members.isEmpty() || members.toSet().size != members.size) return null
        val assigned = LinkedHashMap<String, MutableList<Col>>()
        members.forEach { assigned[it] = mutableListOf() }
        for (col in cols) {
            if (col === c || col.name in claimed) continue
            val m =
                members
                    .filter { col.local == "${f}_$it" || col.local.startsWith("${f}_${it}_") }
                    .maxByOrNull { it.length } ?: continue
            assigned.getValue(m) += col
        }
        if (assigned.values.all { it.isEmpty() }) return null
        kindCheck.consumed = true
        claimed += c.name
        assigned.values.flatten().forEach { claimed += it.name }
        val unionName = spec.claim(ImportNames.upperCamel(f), "Union")
        val at = spec.nested.size
        val inner = mutableListOf<RecordSpec>()
        val types =
            members.map { m ->
                member(ctx, spec, c, f, m, assigned.getValue(m), prefix)
                    .also { (_, record) -> record?.let(inner::add) }
                    .first
            }
        spec.nested.add(
            at,
            FixedDecl(UnitUnion(unionName, types.map { UnionMember(it) }, null, emptyList())),
        )
        val (name, column) = spec.fieldName(f)
        return Slot(
                name,
                UnitType.Ref(unionName),
                c.nullable,
                null,
                ctx.info.columnDocs[c.name],
                listOf(c.name) + assigned.values.flatten().map { it.name },
                scalar = false,
                inner = inner,
            )
            .also { it.column = column }
    }

    /**
     * One union member's type, and the record it lowers to when it is one: a builtin from its one
     * column, an enum from a text column's `IN` check, a reference through a foreign key over its
     * columns, or else a record of its columns with the prefix stripped, a field required when the
     * member's presence check names its column.
     */
    private fun member(
        ctx: TableCtx,
        spec: RecordSpec,
        kind: Col,
        f: String,
        m: String,
        mcols: List<Col>,
        prefix: String,
    ): Pair<UnitType, RecordSpec?> {
        val presence =
            ctx.info.checks.firstOrNull {
                !it.consumed && presenceOf(it.expr, kind.name, m) != null
            }
        presence?.consumed = true
        val required = presence?.let { presenceOf(it.expr, kind.name, m) }.orEmpty().toSet()
        if (mcols.isEmpty() && m in BUILTINS && m != "decimal") {
            return UnitType.Scalar(m, emptyList()) to null
        }
        val exact = mcols.singleOrNull()?.takeIf { it.local == "${f}_$m" }
        if (exact != null) {
            val inCheck =
                ctx.info.checks.firstOrNull { !it.consumed && inList(it.expr, exact.name) != null }
            if (m !in BUILTINS && exact.column.type == "text" && inCheck != null) {
                inCheck.consumed = true
                val values = enumValues(ctx, exact, inList(inCheck.expr, exact.name)!!)
                return enumNamed(spec, ImportNames.upperCamel(m), values) to null
            }
            return scalarType(ctx, spec, exact, ImportNames.upperCamel(m), overridable = false)
                .type to null
        }
        val memberPrefix = "$prefix${f}_${m}_"
        if (mcols.isNotEmpty()) {
            val names = mcols.map { it.name }.toSet()
            val fk = ctx.info.fks.firstOrNull { !it.consumed && it.columns.toSet() == names }
            if (fk != null) {
                val target = refTarget(ctx, fk, mcols.first().column)
                if (target != null) {
                    fk.consumed = true
                    actions(ctx, fk, mcols.first().column, nullable = null)
                    return refTo(ctx, target) to null
                }
            }
        }
        val base = ImportNames.upperCamel(m)
        val record = spec.child(base)
        record.slots +=
            lowerColumns(
                ctx,
                record,
                mcols.map {
                    Col(it.column, it.name.removePrefix(memberPrefix), it.name !in required)
                },
                memberPrefix,
            )
        val same =
            spec.nested.firstOrNull {
                it.name == base && it is RecordSpec && it.decl() == record.decl()
            }
        if (same != null) return UnitType.Ref(base) to (same as RecordSpec)
        record.name = spec.claim(base)
        if (record.name != base) {
            say(
                ctx,
                kind.column,
                ImportCodes.APPROXIMATED,
                "member '$m' imported as '${record.name}', another declaration here being named " +
                    "'$base'; the regenerated literal will be '${Names.snakeCase(record.name)}'",
            )
        }
        spec.nested += record
        return UnitType.Ref(record.name) to record
    }

    /** An enum of [values] nested in [spec] as [base], or the identical one already there. */
    private fun enumNamed(spec: RecordSpec, base: String, values: List<UnitEnumValue>): UnitType {
        val existing = spec.nested.firstOrNull { it.name == base }
        if ((existing as? FixedDecl)?.value.let { it is UnitEnum && it.values == values }) {
            return UnitType.Ref(base)
        }
        val name = spec.claim(base, "Enum")
        spec.nested += FixedDecl(UnitEnum(name, values, null, emptyList()))
        return UnitType.Ref(name)
    }

    // ---- embedded records ----

    /**
     * All-or-none checks over two or more of [cols] that could make an embedded record, widest
     * first so an embed's own check wins over one nested inside it. A check over exactly a foreign
     * key's columns belongs to that reference.
     */
    private fun embedCandidates(
        ctx: TableCtx,
        cols: List<Col>,
        claimed: Set<String>,
        index: Map<String, Int>,
    ): List<Pair<CheckInfo, List<String>>> {
        val fkSets = ctx.info.fks.map { it.columns.toSet() }
        return ctx.info.checks
            .filter { !it.consumed }
            .mapNotNull { check ->
                val columns = allOrNone(check.expr) ?: return@mapNotNull null
                if (columns.size < 2 || columns.any { it !in index || it in claimed })
                    return@mapNotNull null
                if (columns.toSet() in fkSets) return@mapNotNull null
                check to columns
            }
            .sortedByDescending { (_, columns) ->
                columns.maxOf { index.getValue(it) } - columns.minOf { index.getValue(it) }
            }
    }

    /**
     * The embedded record an all-or-none check makes: the columns from the check's first to its
     * last, all sharing one prefix, become a nested record named after the prefix, its fields
     * required when the check names them, and the field is nullable.
     */
    private fun embed(
        ctx: TableCtx,
        spec: RecordSpec,
        cols: List<Col>,
        prefix: String,
        check: CheckInfo,
        columns: List<String>,
        claimed: MutableSet<String>,
        index: Map<String, Int>,
    ): Slot? {
        if (check.consumed || columns.any { it in claimed }) return null
        val span =
            cols.subList(
                columns.minOf { index.getValue(it) },
                columns.maxOf { index.getValue(it) } + 1,
            )
        if (span.any { it.name in claimed }) return null
        val common = span.map { it.local }.reduce { a, b -> a.commonPrefixWith(b) }
        val p = common.substring(0, common.lastIndexOf('_') + 1)
        if (p.length < 2) return null
        check.consumed = true
        span.forEach { claimed += it.name }
        val field = p.dropLast(1)
        val record = spec.child(spec.claim(ImportNames.upperCamel(field), "Record"))
        val required = columns.toSet()
        record.slots +=
            lowerColumns(
                ctx,
                record,
                span.map { Col(it.column, it.local.removePrefix(p), it.name !in required) },
                prefix + p,
            )
        spec.nested += record
        val (name, column) = spec.fieldName(field)
        return Slot(
                name,
                UnitType.Ref(record.name),
                true,
                null,
                null,
                span.map { it.name },
                scalar = false,
                inner = listOf(record),
            )
            .also { it.column = column }
    }

    // ---- references ----

    /**
     * The table [fk] references when it is a record keyed by the referenced columns; otherwise
     * null, reported, and [fk] is done with.
     */
    private fun refTarget(ctx: TableCtx, fk: FkInfo, first: SqlColumn): TableInfo? {
        val target = catalog.resolve(fk.fk.refSchema, fk.fk.refTable)
        if (target == null) {
            fk.consumed = true
            say(
                ctx,
                first,
                ImportCodes.UNRESOLVED,
                "foreign key references '${fk.fk.refSchema ?: PUBLIC}.${fk.fk.refTable}', which is not in the inputs",
            )
            return null
        }
        val pk = target.pk
        val refColumns = fk.fk.refColumns.ifEmpty { pk.orEmpty() }
        val keyed =
            target.child == null &&
                pk != null &&
                refColumns.size == pk.size &&
                refColumns.toSet() == pk.toSet() &&
                fk.columns.size == pk.size
        if (!keyed) {
            fk.consumed = true
            say(
                ctx,
                first,
                ImportCodes.DROPPED,
                "foreign key to '${target.key}' dropped; it does not reference a model's primary key",
            )
            return null
        }
        return target
    }

    /** A reference to [target]'s record, qualified and imported when it is in another namespace. */
    private fun refTo(ctx: TableCtx, target: TableInfo): UnitType {
        val namespace = namespaceOf(target)
        val name = recordNames.getValue(target.key)
        if (namespace == ctx.run.namespace) return UnitType.Ref(name)
        ctx.run.imports += namespace
        return UnitType.Ref("$namespace.$name")
    }

    /**
     * The `ON DELETE` action of a reference over [fk] as `@relation(onDelete: …)` spells it, or
     * null for none. [nullable] is whether the reference may be null, or null when it cannot carry
     * an action at all (a union member's, a map value's). What the regenerated foreign key will not
     * do is reported: an `ON UPDATE`, a deferral, `SET DEFAULT`, an action where none can be
     * carried, or `SET NULL` on a reference that cannot be null. `RESTRICT` and `NO ACTION` both
     * refuse to delete a row still referenced, the language's default, so they read as no action.
     */
    private fun actions(ctx: TableCtx, fk: FkInfo, first: SqlColumn, nullable: Boolean?): String? {
        val action = fk.fk.onDelete
        val onDelete =
            when (action) {
                "CASCADE" -> "cascade".takeIf { nullable != null }
                "SET NULL" -> "set_null".takeIf { nullable == true }
                else -> null
            }
        if (onDelete == null && action != null && action != "NO ACTION" && action != "RESTRICT") {
            val why =
                if (action == "SET NULL" && nullable == false) "; the reference cannot be null"
                else ""
            say(ctx, first, ImportCodes.APPROXIMATED, "ON DELETE $action dropped$why")
        }
        fk.fk.onUpdate
            ?.takeIf { it != "NO ACTION" }
            ?.let { say(ctx, first, ImportCodes.APPROXIMATED, "ON UPDATE $it dropped") }
        fk.fk.extras.forEach { say(ctx, first, ImportCodes.APPROXIMATED, "$it dropped") }
        return onDelete
    }

    /**
     * A reference field over [fk]'s columns: named `f` when the columns are `<f>_<key column>`,
     * else after the first column, kept as its `@sql(column)`; nullable when its columns are, with
     * a composite reference's all-or-none check consumed.
     */
    private fun reference(
        ctx: TableCtx,
        spec: RecordSpec,
        cols: List<Col>,
        fk: FkInfo,
        claimed: MutableSet<String>,
    ): Slot? {
        val fkCols = fk.columns.map { n -> cols.first { it.name == n } }
        val first = fkCols.first()
        val target = refTarget(ctx, fk, first.column) ?: return null
        fk.consumed = true
        fkCols.forEach { claimed += it.name }
        val refColumns = fk.fk.refColumns.ifEmpty { target.pk!! }
        val suffix = "_${refColumns.first()}"
        val f =
            first.local.removeSuffix(suffix).takeIf {
                first.local.endsWith(suffix) && it.isNotEmpty()
            }
        val follows =
            f != null && fkCols.indices.all { fkCols[it].local == "${f}_${refColumns[it]}" }
        val (name, column) =
            if (follows) spec.fieldName(f!!)
            else {
                say(
                    ctx,
                    first.column,
                    ImportCodes.APPROXIMATED,
                    "foreign key column is not named after the field and key; kept as the field name",
                )
                spec.fieldName(first.local).first to first.local
            }
        val nullable = fkCols.all { it.nullable }
        val onDelete = actions(ctx, fk, first.column, nullable)
        ctx.info.checks
            .firstOrNull { !it.consumed && allOrNone(it.expr)?.toSet() == fk.columns.toSet() }
            ?.consumed = true
        return Slot(
                name,
                refTo(ctx, target),
                nullable,
                null,
                ctx.info.columnDocs[first.name],
                fk.columns,
                scalar = false,
            )
            .also {
                it.column = column
                it.onDelete = onDelete
            }
    }

    // ---- scalars ----

    private fun scalarSlot(ctx: TableCtx, spec: RecordSpec, c: Col, key: Boolean): Slot {
        val out =
            scalarType(ctx, spec, c, ImportNames.upperCamel(c.local), overridable = true, key = key)
        val (name, column) = spec.fieldName(c.local)
        val slot =
            Slot(
                name,
                out.type,
                c.nullable,
                out.default,
                ctx.info.columnDocs[c.name],
                listOf(c.name),
                scalar = out.keyable,
            )
        slot.column = column
        slot.sqlType = out.sqlType
        slot.strategy = out.strategy
        if (out.json) ctx.run.json += JsonUse(spec, slot, ctx, c.column)
        return slot
    }

    /**
     * The type of one column, from its spelling, its note, and the checks on it alone. An
     * `@sql(type)` keeps a spelling the SQL target would not write, when [overridable]; a spelling
     * outside the target's own is reported as widened unless a note says what the column holds. A
     * [key] column is read as a plain scalar whatever its spelling, an array or json one included.
     */
    private fun scalarType(
        ctx: TableCtx,
        spec: RecordSpec,
        c: Col,
        enumBase: String,
        overridable: Boolean,
        key: Boolean = false,
    ): ScalarOut {
        val column = c.column
        val note =
            column.note?.let { text ->
                NoteText.parse(text)
                    ?: null.also {
                        say(
                            ctx,
                            column,
                            ImportCodes.APPROXIMATED,
                            "note '$text' does not read as a type; ignored",
                        )
                    }
            }
        val written = column.type
        return when {
            key -> plain(ctx, spec, c, note, enumBase, overridable)
            written.endsWith("[]") && !written.endsWith("[][]") -> array(ctx, c, note)
            written == "jsonb" || written == "json" -> json(ctx, spec, c, note, overridable)
            else -> plain(ctx, spec, c, note, enumBase, overridable)
        }
    }

    private fun array(ctx: TableCtx, c: Col, note: NoteText.Parsed?): ScalarOut {
        val column = c.column
        val base = column.type.removeSuffix("[]")
        val mapped = mapType(base)
        val element =
            UnitType.Scalar(
                mapped.builtin,
                mapped.refinements.filter { it.first == "p" || it.first == "s" },
            )
        var type: UnitType = UnitType.ListOf(element, false, emptyList())
        val noted = note?.type
        if (noted is UnitType.ListOf && refs(noted).isEmpty()) {
            type = noted
        } else if (noted != null) {
            say(
                ctx,
                column,
                ImportCodes.APPROXIMATED,
                "note '${column.note}' does not fit a ${column.type} column; ignored",
            )
        }
        if (noted !is UnitType.ListOf && spell(element) != mapped.canonical) {
            say(ctx, column, ImportCodes.WIDENED, "${column.type} imported as ${mapped.builtin}[]")
        }
        dropDefault(ctx, column)
        return ScalarOut(type, null, null, null, json = false, keyable = false)
    }

    private fun json(
        ctx: TableCtx,
        spec: RecordSpec,
        c: Col,
        note: NoteText.Parsed?,
        overridable: Boolean,
    ): ScalarOut {
        val column = c.column
        val noted = note?.type
        if (noted == null || noted is UnitType.Scalar)
            return plain(ctx, spec, c, note, "", overridable)
        if (column.type == "json") say(ctx, column, ImportCodes.WIDENED, "json imported as jsonb")
        dropDefault(ctx, column)
        val strategy = if (noted is UnitType.MapOf) null else "json"
        return ScalarOut(noted, null, null, strategy, json = true, keyable = false)
    }

    private fun plain(
        ctx: TableCtx,
        spec: RecordSpec,
        c: Col,
        note: NoteText.Parsed?,
        enumBase: String,
        overridable: Boolean,
    ): ScalarOut {
        val column = c.column
        val written = column.type
        val mapped = mapType(written)
        val noted = note?.type
        val inCheck =
            ctx.info.checks.firstOrNull { !it.consumed && inList(it.expr, column.name) != null }
        val enumFromNote = noted is UnitType.Ref && inCheck != null
        if (inCheck != null && (enumFromNote || noted == null && written == "text")) {
            inCheck.consumed = true
            val literals = inList(inCheck.expr, column.name)!!
            val values = enumValues(ctx, c, literals)
            val name =
                if (enumFromNote) spec.claim((noted as UnitType.Ref).name)
                else spec.claim(enumBase, "Enum")
            spec.nested += FixedDecl(UnitEnum(name, values, null, emptyList()))
            val default = enumDefault(ctx, column, literals, values)
            val sqlType = written.takeIf { overridable && it != "text" }
            return ScalarOut(
                UnitType.Ref(name),
                default,
                sqlType,
                null,
                json = false,
                keyable = true,
            )
        }
        if (noted != null && noted !is UnitType.Scalar) {
            say(
                ctx,
                column,
                ImportCodes.APPROXIMATED,
                "note '${column.note}' does not fit a $written column; ignored",
            )
        }
        val fromNote = noted as? UnitType.Scalar
        val builtin = fromNote?.builtin ?: mapped.builtin
        val refinements = LinkedHashMap<String, String>()
        (fromNote?.refinements ?: mapped.refinements).forEach { (k, v) -> refinements[k] = v }
        checksOn(ctx, column, builtin).forEach { (k, v) ->
            if (fromNote == null || k !in refinements) refinements[k] = v
        }
        val ordered =
            listOf("p", "s", "min", "max", "pattern").mapNotNull { k ->
                refinements[k]?.let { k to it }
            } + refinements.filterKeys { it !in setOf("p", "s", "min", "max", "pattern") }.toList()
        val type = UnitType.Scalar(builtin, ordered)
        var sqlType: String? = null
        if (spell(type) != mapped.canonical) {
            if (overridable) sqlType = written
            if (note == null && mapped.foreign) {
                val with = if (overridable) " with @sql(type)" else ""
                say(ctx, column, ImportCodes.WIDENED, "$written imported as $builtin$with")
            }
        }
        mapped.approximated?.let { say(ctx, column, ImportCodes.APPROXIMATED, it) }
        if (mapped.generated)
            say(ctx, column, ImportCodes.APPROXIMATED, "$written generation dropped")
        if (column.identity)
            say(ctx, column, ImportCodes.APPROXIMATED, "identity generation dropped")
        val default = scalarDefault(ctx, column, builtin)
        return ScalarOut(type, default, sqlType, null, json = false, keyable = true)
    }

    /**
     * The refinements the unconsumed checks on [column] alone give a [builtin]; a check is consumed
     * only when every part of it applies.
     */
    private fun checksOn(
        ctx: TableCtx,
        column: SqlColumn,
        builtin: String,
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (check in ctx.info.checks) {
            if (check.consumed) continue
            val atoms = conjuncts(check.expr).map { atom(it) }
            if (atoms.any { it == null || it.column != column.name }) continue
            val each = atoms.map { refinement(it!!, builtin) }
            if (each.any { it == null }) continue
            val refinements = each.flatMap { it!! }
            check.consumed = true
            out += refinements
            atoms
                .filterIsInstance<Atom.Pattern>()
                .filter { it.insensitive }
                .forEach { _ ->
                    say(
                        ctx,
                        column,
                        ImportCodes.WIDENED,
                        "~* imported as pattern; case-insensitivity dropped",
                    )
                }
        }
        return out
    }

    private fun enumValues(ctx: TableCtx, c: Col, literals: List<String>): List<UnitEnumValue> {
        val taken = mutableSetOf<String>()
        return literals.map { literal ->
            val base =
                if (ImportNames.isLowerSnake(literal)) literal else ImportNames.lowerSnake(literal)
            val name =
                generateSequence(1) { it + 1 }
                    .map { if (it == 1) base else "${base}_$it" }
                    .first { it !in taken }
            taken += name
            if (name != literal) {
                say(
                    ctx,
                    c.column,
                    ImportCodes.APPROXIMATED,
                    "enum value '$literal' imported as '$name'; the regenerated check uses it",
                )
            }
            UnitEnumValue(name, null, emptyList())
        }
    }

    /** The enum value a default names, by the check's literal it matches. */
    private fun enumDefault(
        ctx: TableCtx,
        column: SqlColumn,
        literals: List<String>,
        values: List<UnitEnumValue>,
    ): String? {
        val d = column.default ?: return null
        if (d == SqlExpr.Null) return null
        val index = literals.indexOf((d as? SqlExpr.Str)?.value)
        if (index >= 0) return values[index].name
        say(ctx, column, ImportCodes.APPROXIMATED, "default ${exprText(d)} dropped")
        return null
    }

    private fun scalarDefault(ctx: TableCtx, column: SqlColumn, builtin: String): String? {
        val d = column.default ?: return null
        if (d == SqlExpr.Null) return null
        val number = (d as? SqlExpr.Num)?.text
        val literal =
            when {
                builtin == "bool" && d is SqlExpr.Bool -> d.value.toString()
                builtin in setOf("int32", "int64") && number != null && INTEGER.matches(number) ->
                    number
                builtin in setOf("float32", "float64", "decimal") &&
                    number != null &&
                    DECIMAL.matches(number) -> number
                builtin == "string" && d is SqlExpr.Str -> SchemataText.string(d.value)
                else -> null
            }
        if (literal == null)
            say(ctx, column, ImportCodes.APPROXIMATED, "default ${exprText(d)} dropped")
        return literal
    }

    private fun dropDefault(ctx: TableCtx, column: SqlColumn) {
        val d = column.default ?: return
        if (d != SqlExpr.Null)
            say(ctx, column, ImportCodes.APPROXIMATED, "default ${exprText(d)} dropped")
    }

    // ---- child tables ----

    private fun childSlots(ctx: TableCtx, spec: RecordSpec): List<Slot> =
        catalog.children[ctx.info.key].orEmpty().map { childSlot(ctx, spec, it) }

    /**
     * The list or map field a child table lowers to. Its element is the table's columns past the
     * parent key and `position` or `key`: one `value` column is a scalar or enum, `value_…` columns
     * under a foreign key a reference, a map's other `value_…` columns a record, and anything else
     * a record of the columns as they stand, with the child's own children as its fields. A list of
     * references with a unique over the parent key and the reference's columns is `{ unique }`.
     */
    private fun childSlot(ctx: TableCtx, spec: RecordSpec, child: ChildInfo): Slot {
        val t = child.info
        val run = ctx.run
        val (name, column) = spec.fieldName(child.field)
        val elementName = singular(ImportNames.upperCamel(child.field))
        val keyColumns = child.keyColumns.toSet()
        val pk = t.pk.orEmpty().toSet()
        val cols =
            t.table.columns
                .filter { it.name !in keyColumns }
                .map { Col(it, it.name, !it.notNull && it.name !in pk) }
        val hasChildren = catalog.children[t.key].orEmpty().isNotEmpty()
        val value = cols.singleOrNull()?.takeIf { it.name == "value" && !hasChildren }
        val valueCols =
            cols.takeIf { c ->
                c.isNotEmpty() && !hasChildren && c.all { it.name.startsWith("value_") }
            }
        val valueFk =
            valueCols?.let { vc ->
                t.fks.firstOrNull {
                    !it.consumed && it.columns.toSet() == vc.map { c -> c.name }.toSet()
                }
            }
        val tableCtx = TableCtx(t, t.name, run)
        reportDropped(tableCtx)
        var element: UnitType? = null
        var nullableElement = false
        var onDelete: String? = null
        var record: RecordSpec? = null
        var elementCtx = tableCtx
        var set = false
        if (value != null) {
            element = scalarType(tableCtx, spec, value, elementName, overridable = false).type
            nullableElement = value.nullable
        } else if (valueFk != null) {
            val target = refTarget(tableCtx, valueFk, valueCols.first().column)
            if (target != null) {
                valueFk.consumed = true
                val refColumns = valueFk.fk.refColumns.ifEmpty { target.pk!! }
                if (valueFk.columns != refColumns.map { "value_$it" }) {
                    say(
                        tableCtx,
                        valueCols.first().column,
                        ImportCodes.APPROXIMATED,
                        "foreign key column is not named after the field and key; kept as the field name",
                    )
                }
                nullableElement = valueCols.all { it.nullable }
                // Only a list's element reference can say what deleting its target does.
                onDelete =
                    actions(
                        tableCtx,
                        valueFk,
                        valueCols.first().column,
                        nullableElement.takeUnless { child.map },
                    )
                t.checks
                    .firstOrNull {
                        !it.consumed && allOrNone(it.expr)?.toSet() == valueFk.columns.toSet()
                    }
                    ?.consumed = true
                element = refTo(tableCtx, target)
                // a unique over the parent key and the copied key makes the list a set
                if (!child.map) {
                    val columns = (child.parentFk.columns + valueFk.columns).toSet()
                    t.uniques
                        .firstOrNull { it.toSet() == columns }
                        ?.let {
                            t.uniques.remove(it)
                            set = true
                        }
                }
            }
        }
        if (element == null) {
            val r = spec.child(spec.claim(elementName))
            r.doc = t.doc
            elementCtx = TableCtx(t, r.name, run)
            if (child.map && valueCols != null) {
                val present =
                    t.checks.firstOrNull { check ->
                        !check.consumed &&
                            allOrNone(check.expr)?.let { a ->
                                a.all { n -> valueCols.any { it.name == n } }
                            } == true
                    }
                present?.consumed = true
                val required = present?.let { allOrNone(it.expr) }.orEmpty().toSet()
                nullableElement = present != null
                r.slots +=
                    lowerColumns(
                        elementCtx,
                        r,
                        valueCols.map {
                            Col(
                                it.column,
                                it.name.removePrefix("value_"),
                                if (present != null) it.name !in required else it.nullable,
                            )
                        },
                        "value_",
                    )
            } else {
                r.slots += lowerColumns(elementCtx, r, cols, "")
                r.slots += childSlots(elementCtx, r)
            }
            spec.nested += r
            record = r
            element = UnitType.Ref(r.name)
        }
        constraints(elementCtx, record)
        leftovers(elementCtx)
        val keyType =
            if (child.map)
                t.column("key")?.let { k ->
                    mapType(k.type).let { UnitType.Scalar(it.builtin, it.refinements) }
                } ?: UnitType.Scalar("string", emptyList())
            else null
        val type =
            if (keyType != null) UnitType.MapOf(keyType, element, nullableElement, emptyList())
            else UnitType.ListOf(element, nullableElement, emptyList())
        val slot =
            Slot(
                name,
                type,
                false,
                null,
                if (record == null) t.doc else null,
                emptyList(),
                scalar = false,
            )
        slot.column = column
        slot.onDelete = onDelete
        slot.unique = set
        if (
            child.map || record == null && element !is UnitType.Ref || isEnumElement(spec, element)
        ) {
            slot.strategy = "table"
        }
        return slot
    }

    /**
     * Whether [element] names an enum nested in [spec], which a list keeps in an array by default.
     */
    private fun isEnumElement(spec: RecordSpec, element: UnitType): Boolean =
        element is UnitType.Ref && (spec.lookup(element.name) as? FixedDecl)?.value is UnitEnum

    // ---- keys, uniques, indexes, and what is left ----

    /**
     * The primary key on the fields its columns lowered to: `{ id }` on each when they are in key
     * order among the fields, else `@@id(a, b)`.
     */
    private fun keys(ctx: TableCtx, spec: RecordSpec) {
        val pk = ctx.info.pk
        if (pk == null) {
            sayTable(
                ctx.info,
                ImportCodes.APPROXIMATED,
                "no primary key; add { id } to a field before compiling to SQL",
            )
            return
        }
        val slots = pk.map { c -> spec.slots.firstOrNull { c in it.columns } }
        if (slots.any { it == null || !it.scalar }) {
            sayTable(
                ctx.info,
                ImportCodes.APPROXIMATED,
                "primary key names a column not in the table; add { id } to a field before compiling to SQL",
            )
            return
        }
        val positions = slots.map { spec.slots.indexOf(it) }
        if (positions.zipWithNext().all { (a, b) -> a < b }) {
            slots.forEach { it!!.key = true }
        } else {
            spec.key = slots.map { it!!.name }
        }
    }

    /**
     * Each unique and plain index on the fields whose columns it covers: `{ unique }` or `{ index
     * }` on the one field covering all of them, else `@@unique(a, b)` or `@@index(a, b)` over the
     * record's fields, named in the order of their first column in the constraint. One over the
     * primary key adds nothing and goes silently; one whose columns no set of fields covers
     * exactly, or that covers a list's or a map's (which have no columns of their own here), is
     * dropped.
     */
    private fun constraints(ctx: TableCtx, spec: RecordSpec?) {
        val pk = ctx.info.pk?.toSet()
        val all = spec?.let { allSlots(it) }.orEmpty().filter(::constrainable)
        fun match(columns: List<String>) =
            all.firstOrNull {
                it.columns.size == columns.size && it.columns.toSet() == columns.toSet()
            }
        /** The record's own fields that together hold exactly [columns], or null. */
        fun fields(columns: List<String>): List<String>? {
            val slots =
                columns
                    .map { c -> spec?.slots?.firstOrNull { c in it.columns } ?: return null }
                    .distinct()
            if (slots.size < 2 || !slots.all(::constrainable)) return null
            if (slots.flatMap { it.columns }.toSet() != columns.toSet()) return null
            return slots.map { it.name }
        }
        for (u in ctx.info.uniques) {
            if (u.toSet() == pk) continue
            match(u)?.let { it.unique = true }
                ?: fields(u)?.let { spec!!.uniques += it }
                ?: sayTable(
                    ctx.info,
                    ImportCodes.DROPPED,
                    "unique constraint over (${u.joinToString(", ")}) dropped; no fields hold exactly its columns",
                )
        }
        for (ix in ctx.info.indexes) {
            val listed = ix.columns.joinToString(", ")
            val unique = if (ix.unique) "unique " else ""
            when {
                ix.filtered ->
                    sayTable(
                        ctx.info,
                        ImportCodes.DROPPED,
                        "partial ${unique}index over ($listed) dropped",
                    )
                ix.using != null && ix.using != "btree" ->
                    sayTable(
                        ctx.info,
                        ImportCodes.DROPPED,
                        "${unique}index using ${ix.using} over ($listed) dropped",
                    )
                ix.columns.toSet() == pk -> {}
                else ->
                    match(ix.columns)?.let { if (ix.unique) it.unique = true else it.index = true }
                        ?: fields(ix.columns)?.let {
                            if (ix.unique) spec!!.uniques += it else spec!!.indexes += it
                        }
                        ?: sayTable(
                            ctx.info,
                            ImportCodes.DROPPED,
                            "${unique}index over ($listed) dropped; no fields hold exactly its columns",
                        )
            }
        }
    }

    /** Whether a unique or an index can name [slot]: it has columns of its own. */
    private fun constrainable(slot: Slot): Boolean =
        slot.columns.isNotEmpty() && slot.type !is UnitType.ListOf && slot.type !is UnitType.MapOf

    private fun allSlots(spec: RecordSpec): List<Slot> =
        spec.slots.flatMap { s -> listOf(s) + s.inner.flatMap { allSlots(it) } }

    private fun leftovers(ctx: TableCtx) {
        ctx.info.checks
            .filter { !it.consumed }
            .forEach {
                it.consumed = true
                sayTable(
                    ctx.info,
                    ImportCodes.DROPPED,
                    "check constraint dropped: (${it.check.text})",
                )
            }
        ctx.info.fks
            .filter { !it.consumed }
            .forEach {
                it.consumed = true
                sayTable(
                    ctx.info,
                    ImportCodes.DROPPED,
                    "foreign key over (${it.columns.joinToString(", ")}) dropped",
                )
            }
    }

    /**
     * Each type a json field's note names, found among the declarations nested where the field is
     * or in the namespace; one the DDL does not define becomes an empty record nested beside the
     * field, reported. A name qualified by another namespace is that namespace's to define; the
     * namespace is imported.
     */
    private fun resolveJson(run: Run) {
        for (use in run.json) {
            for (name in refs(use.slot.type)) {
                if ('.' in name) {
                    val namespace =
                        name.split('.').takeWhile { it.first().isLowerCase() }.joinToString(".")
                    if (namespace.isNotEmpty() && namespace != run.namespace) {
                        run.imports += namespace
                    }
                    continue
                }
                if (use.owner.lookup(name) != null || name in run.topNames) continue
                use.owner.claim(name)
                use.owner.nested += use.owner.child(name)
                say(
                    use.ctx,
                    use.column,
                    ImportCodes.APPROXIMATED,
                    "'$name' is stored as json; its fields are not in the DDL",
                )
            }
        }
        run.json.clear()
    }
}

// ---- types ----

private val INTEGER = Regex("^-?\\d+$")
private val DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")

/** The names a type refers to, in order. */
private fun refs(type: UnitType): List<String> =
    when (type) {
        is UnitType.Scalar -> emptyList()
        is UnitType.Ref -> listOf(type.name)
        is UnitType.ListOf -> refs(type.element)
        is UnitType.MapOf -> refs(type.key) + refs(type.value)
    }

/**
 * A column type read as a builtin. [canonical] is the spelling to compare with what the SQL target
 * writes for the result; [foreign] marks a spelling the target never writes, kept with `@sql(type)`
 * and reported as widened; [generated] marks a serial.
 */
private class Mapped(
    val builtin: String,
    val refinements: List<Pair<String, String>>,
    val canonical: String,
    val foreign: Boolean,
    val generated: Boolean = false,
    val approximated: String? = null,
)

private fun mapType(written: String): Mapped {
    val head = written.substringBefore('(').trim()
    val args =
        if ('(' in written)
            written.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        else emptyList()
    fun own(
        builtin: String,
        refinements: List<Pair<String, String>> = emptyList(),
        canonical: String = written,
    ) = Mapped(builtin, refinements, canonical, foreign = false)
    fun foreign(
        builtin: String,
        refinements: List<Pair<String, String>> = emptyList(),
        generated: Boolean = false,
    ) = Mapped(builtin, refinements, written, foreign = true, generated = generated)
    val number = args.singleOrNull()?.toIntOrNull()
    return when {
        written == "boolean" -> own("bool")
        written == "integer" -> own("int32")
        written == "bigint" -> own("int64")
        written == "real" -> own("float32")
        written == "double precision" -> own("float64")
        head == "numeric" && args.size == 2 && args.all { it.toIntOrNull() != null } ->
            own(
                "decimal",
                listOf("p" to args[0], "s" to args[1]),
                "numeric(${args[0]}, ${args[1]})",
            )
        head == "numeric" && number != null ->
            own("decimal", listOf("p" to "$number", "s" to "0"), "numeric($number, 0)")
        written == "numeric" ->
            Mapped(
                "decimal",
                listOf("p" to "38", "s" to "9"),
                "numeric(38, 9)",
                foreign = false,
                approximated = "numeric without precision imported as decimal(38, 9)",
            )
        written == "text" -> own("string")
        head == "varchar" && number != null -> own("string", listOf("max" to "$number"))
        written == "bytea" -> own("bytes")
        written == "uuid" -> own("uuid")
        written == "date" -> own("date")
        written == "time" -> own("time")
        written == "timestamptz" -> own("instant")
        written == "interval" -> own("duration")
        written in setOf("smallint", "smallserial", "serial2") ->
            foreign("int32", generated = written != "smallint")
        written in setOf("serial", "serial4") -> foreign("int32", generated = true)
        written in setOf("bigserial", "serial8") -> foreign("int64", generated = true)
        head == "char" ->
            (number ?: 1).let { n -> foreign("string", listOf("min" to "$n", "max" to "$n")) }
        head.startsWith("timestamp") -> foreign("instant")
        head.startsWith("time") -> foreign("time")
        head.startsWith("interval") -> foreign("duration")
        written == "money" -> foreign("decimal", listOf("p" to "19", "s" to "4"))
        else -> foreign("string")
    }
}

/** What the SQL target writes for a scalar field of [type] with no `@sql(type)`. */
private fun spell(type: UnitType.Scalar): String {
    val r = type.refinements.toMap()
    return when (type.builtin) {
        "bool" -> "boolean"
        "int32" -> "integer"
        "int64" -> "bigint"
        "float32" -> "real"
        "float64" -> "double precision"
        "decimal" -> "numeric(${r["p"]}, ${r["s"]})"
        "string" -> {
            val max = r["max"]?.toBigDecimalOrNull()
            val varchar =
                r.keys == setOf("max") &&
                    max != null &&
                    max >= BigDecimal.ONE &&
                    max <= BigDecimal(10_485_760) &&
                    max.stripTrailingZeros().scale() <= 0
            if (varchar) "varchar(${max!!.toBigInteger()})" else "text"
        }
        "bytes" -> "bytea"
        "instant" -> "timestamptz"
        "duration" -> "interval"
        else -> type.builtin
    }
}

/** `lines` → `Line`, `entries` → `Entry`, `addresses` → `Address`; a word not ending in s stays. */
private fun singular(name: String): String =
    when {
        name.endsWith("ies") -> name.dropLast(3) + "y"
        listOf("ses", "xes", "zes", "ches", "shes").any { name.endsWith(it) } -> name.dropLast(2)
        name.endsWith("ss") || name.endsWith("us") -> name
        name.endsWith("s") && name.length > 1 -> name.dropLast(1)
        else -> name
    }

// ---- expressions ----

private fun conjuncts(e: SqlExpr): List<SqlExpr> =
    if (e is SqlExpr.Bin && e.op == "and") conjuncts(e.left) + conjuncts(e.right) else listOf(e)

/** The string literals of `column IN (…)`, or null for any other expression. */
private fun inList(e: SqlExpr, column: String): List<String>? {
    if (e !is SqlExpr.In || e.expr != SqlExpr.Col(column)) return null
    return e.items.map { (it as? SqlExpr.Str)?.value ?: return null }
}

/**
 * The columns of `(a IS NULL AND b IS NULL …) OR (a IS NOT NULL AND b IS NOT NULL …)`, either way
 * round, or null for any other expression.
 */
private fun allOrNone(e: SqlExpr): List<String>? {
    if (e !is SqlExpr.Bin || e.op != "or") return null
    fun side(x: SqlExpr): Pair<Boolean, List<String>>? {
        val parts = conjuncts(x).map { it as? SqlExpr.IsNull ?: return null }
        if (parts.map { it.not }.toSet().size != 1) return null
        return parts.first().not to parts.map { (it.expr as? SqlExpr.Col)?.name ?: return null }
    }
    val a = side(e.left) ?: return null
    val b = side(e.right) ?: return null
    if (a.first == b.first || a.second.toSet() != b.second.toSet()) return null
    return if (!a.first) a.second else b.second
}

/** The columns of `(kind <> 'm') OR (a IS NOT NULL AND …)`, or null for any other expression. */
private fun presenceOf(e: SqlExpr, kind: String, m: String): List<String>? {
    if (e !is SqlExpr.Bin || e.op != "or") return null
    val test = e.left as? SqlExpr.Bin ?: return null
    if (test.op != "<>" && test.op != "!=") return null
    if (test.left != SqlExpr.Col(kind) || test.right != SqlExpr.Str(m)) return null
    return conjuncts(e.right).map { part ->
        val isNull = part as? SqlExpr.IsNull ?: return null
        if (!isNull.not) return null
        (isNull.expr as? SqlExpr.Col)?.name ?: return null
    }
}

/** One part of a check that a refinement can say, over [column]. */
private sealed interface Atom {
    val column: String

    /** `measure op n`: [measure] is `value`, `chars`, or `octets`. */
    data class Bound(
        override val column: String,
        val measure: String,
        val op: String,
        val n: String,
    ) : Atom

    data class Range(
        override val column: String,
        val measure: String,
        val low: String,
        val high: String,
    ) : Atom

    data class Pattern(override val column: String, val pattern: String, val insensitive: Boolean) :
        Atom
}

private fun measured(e: SqlExpr): Pair<String, String>? =
    when {
        e is SqlExpr.Col -> e.name to "value"
        e is SqlExpr.Call && e.args.size == 1 && e.args[0] is SqlExpr.Col -> {
            val column = (e.args[0] as SqlExpr.Col).name
            when (e.name) {
                "char_length",
                "character_length",
                "length" -> column to "chars"
                "octet_length" -> column to "octets"
                else -> null
            }
        }
        else -> null
    }

private val FLIPPED = mapOf(">=" to "<=", "<=" to ">=", ">" to "<", "<" to ">")

private fun atom(e: SqlExpr): Atom? =
    when (e) {
        is SqlExpr.Bin ->
            when (e.op) {
                ">=",
                "<=",
                ">",
                "<" -> {
                    val left = measured(e.left)
                    val right = measured(e.right)
                    when {
                        left != null && e.right is SqlExpr.Num ->
                            Atom.Bound(left.first, left.second, e.op, (e.right as SqlExpr.Num).text)
                        right != null && e.left is SqlExpr.Num ->
                            Atom.Bound(
                                right.first,
                                right.second,
                                FLIPPED.getValue(e.op),
                                (e.left as SqlExpr.Num).text,
                            )
                        else -> null
                    }
                }
                "~",
                "~*" -> {
                    val column = (e.left as? SqlExpr.Col)?.name
                    val pattern = (e.right as? SqlExpr.Str)?.value
                    if (column != null && pattern != null)
                        Atom.Pattern(column, pattern, e.op == "~*")
                    else null
                }
                else -> null
            }
        is SqlExpr.Between -> {
            val m = measured(e.expr)
            val low = (e.low as? SqlExpr.Num)?.text
            val high = (e.high as? SqlExpr.Num)?.text
            if (m != null && low != null && high != null) Atom.Range(m.first, m.second, low, high)
            else null
        }
        else -> null
    }

/** The refinements [atom] gives a [builtin], or null when it does not apply to one. */
private fun refinement(atom: Atom, builtin: String): List<Pair<String, String>>? {
    val measure =
        when (builtin) {
            "int32",
            "int64",
            "float32",
            "float64",
            "decimal" -> "value"
            "string" -> "chars"
            "bytes" -> "octets"
            else -> return null
        }
    val integral = builtin in setOf("int32", "int64") || measure != "value"
    fun number(n: String) =
        if (integral) n.takeIf { INTEGER.matches(it) } else n.takeIf { DECIMAL.matches(it) }
    return when (atom) {
        is Atom.Bound -> {
            if (atom.measure != measure) return null
            val n = number(atom.n) ?: return null
            when (atom.op) {
                ">=" -> listOf("min" to n)
                "<=" -> listOf("max" to n)
                ">" ->
                    if (integral) listOf("min" to (BigInteger(n) + BigInteger.ONE).toString())
                    else null
                "<" ->
                    if (integral) listOf("max" to (BigInteger(n) - BigInteger.ONE).toString())
                    else null
                else -> null
            }
        }
        is Atom.Range -> {
            if (atom.measure != measure) return null
            listOf(
                "min" to (number(atom.low) ?: return null),
                "max" to (number(atom.high) ?: return null),
            )
        }
        is Atom.Pattern ->
            if (builtin == "string") listOf("pattern" to SchemataText.pattern(atom.pattern))
            else null
    }
}

/** Functions SQL calls without parentheses, as the reader reads them. */
private val VALUE_FUNCTIONS =
    setOf(
        "current_timestamp",
        "current_date",
        "current_time",
        "localtime",
        "localtimestamp",
        "current_user",
        "session_user",
        "current_role",
        "current_catalog",
    )

/** An expression as a message shows it: `now()`, `'x'`, `nextval('s')`. */
private fun exprText(e: SqlExpr): String =
    when (e) {
        is SqlExpr.Col -> e.name
        is SqlExpr.Str -> "'" + e.value.replace("'", "''") + "'"
        is SqlExpr.Num -> e.text
        is SqlExpr.Bool -> e.value.toString()
        SqlExpr.Null -> "NULL"
        is SqlExpr.Call ->
            if (e.args.isEmpty() && e.name in VALUE_FUNCTIONS) e.name
            else "${e.name}(${e.args.joinToString(", ") { exprText(it) }})"
        is SqlExpr.Bin -> "${exprText(e.left)} ${e.op} ${exprText(e.right)}"
        is SqlExpr.Not -> "NOT ${exprText(e.expr)}"
        is SqlExpr.IsNull -> "${exprText(e.expr)} IS ${if (e.not) "NOT " else ""}NULL"
        is SqlExpr.In -> "${exprText(e.expr)} IN (${e.items.joinToString(", ") { exprText(it) }})"
        is SqlExpr.Between ->
            "${exprText(e.expr)} BETWEEN ${exprText(e.low)} AND ${exprText(e.high)}"
        is SqlExpr.Raw -> e.text
    }
