package io.schemata.target.sql

import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.OverrideNames
import io.schemata.target.collidingNamespaces

/**
 * Lowers records to tables. A record has a table exactly when it has a key; a keyless record is a
 * value type that only appears where a field uses it. Lowering runs in two passes: a [Catalog] of
 * every keyed record's table and key columns, then each field's [Contribution] to its table.
 * References to keyed records become key columns and a foreign key; a reference to a keyless record
 * embeds that record's own columns under `<field>_`, recursively. Scalars carry every builtin,
 * refinements as CHECK constraints, defaults, enums as constrained text, and `@sql` overrides. A
 * reference to a union becomes a `<field>_kind` discriminator column plus each member's own nested,
 * forced-nullable contribution, with a CHECK that a member's columns are present exactly when the
 * kind names it. `{ embed }` copies a referenced model's columns in place of its key, and
 * `@sql(strategy: …)` overrides a field's default shape with `table` or `json` wherever the matrix
 * allows it; a strategy a shape forbids, or any strategy at all on a scalar, is an error.
 * `reserved` ordinals and names have no relational meaning and are accepted without a diagnostic.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        // an empty `@sql(<key>)` override is reported and ignored, whichever key carries it
        val overrides =
            OverrideNames(
                "sql",
                SqlCodes.INVALID_OVERRIDE,
                diagnostics,
                { if (it.isEmpty()) "is empty" else null },
            ) {
                "give the name at least one character"
            }
        val schemaNames =
            schema.namespaces.associate {
                val override =
                    overrides.overrideName(
                        it.annotations,
                        "schema '${it.name}'",
                        it.span,
                        key = "schema",
                    )
                it.name to identifier(Naming.schemaOf(it, override), it.span, diagnostics)
            }
        schemaCollisions(schema.namespaces, schemaNames, diagnostics)
        val catalog =
            Catalog(
                schema,
                schemaNames,
                identifier = { name, span -> identifier(name, span, diagnostics) },
                override = { annotations, key, where, span ->
                    overrides.overrideName(annotations, where, span, key)
                },
            )
        val lowered =
            schema.namespaces.map {
                NamespaceLowering(
                        schema,
                        catalog,
                        it,
                        schemaNames.getValue(it.name),
                        overrides,
                        diagnostics,
                    )
                    .lower()
            }
        // A keyless model's fields lower once per field that embeds it, so a problem in one of them
        // (a bad override, an unsupported pattern) is found once per embedding; the reports are
        // identical, and the model has the problem once.
        return Lowered(RelationalModel(placeForeignKeys(schema, lowered)), diagnostics.distinct())
    }

    /**
     * A foreign key is emitted by the file that sorts later of the two it links, so every table it
     * names already exists when the files are applied in path order. Within a file, its own keys
     * come first in table and field order, then the keys moved in from other files.
     */
    private fun placeForeignKeys(
        schema: Schema,
        lowered: List<Pair<RelationalSchema, List<PendingForeignKey>>>,
    ): List<RelationalSchema> {
        val pending = lowered.flatMap { it.second }
        val paths = schema.namespaces.associate { it.name to pathOf(it) }
        return schema.namespaces.zip(lowered).map { (namespace, part) ->
            val relational = part.first
            val mine =
                pending.filter { fk ->
                    val source = paths.getValue(fk.sourceNamespace)
                    val target = paths.getValue(fk.targetNamespace)
                    maxOf(source, target) == relational.path
                }
            val (own, moved) = mine.partition { it.sourceNamespace == namespace.name }
            relational.copy(foreignKeys = (own + moved).map { it.fk })
        }
    }

    private fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".sql"

    private class NamespaceLowering(
        schema: Schema,
        catalog: Catalog,
        private val namespace: Namespace,
        private val schemaName: String,
        overrides: OverrideNames,
        diagnostics: MutableList<Diagnostic>,
    ) {
        private val context =
            LoweringContext(schema, catalog, namespace, schemaName, overrides, diagnostics).also {
                it.recordLowering = RecordLowering(it)
                it.unionLowering = UnionLowering(it)
                it.childTables = ChildTables(it)
            }

        /**
         * Every keyed record has a table, whether it is declared at the top level or nested inside
         * another record; a nested keyed record's table follows its parent's table and children.
         * Keyless records, top-level or nested, are value types, each reported if unused.
         */
        fun lower(): Pair<RelationalSchema, List<PendingForeignKey>> {
            tableCollisions()
            val tables = mutableListOf<Table>()
            val foreignKeys = mutableListOf<PendingForeignKey>()
            context.records.forEach { r ->
                if (context.catalog[r.qualifiedName] != null) {
                    val part = context.recordLowering.record(r)
                    tables += part.table
                    tables += part.children.map { it.table }
                    foreignKeys += part.foreignKeys
                    foreignKeys += part.children.flatMap { it.foreignKeys }
                } else if (r.qualifiedName !in context.catalog.used) {
                    context.error(
                        SqlCodes.MISSING_KEY,
                        "model '${r.name}' has no primary key and is not used by any field",
                        r.nameSpan,
                        help =
                            "mark its key fields with `{ id }`, or the model with `@@id(a, b)`; a keyless model only lowers when a field embeds it",
                    )
                }
            }
            relationCollisions()
            return RelationalSchema(
                pathOf(namespace),
                schemaName,
                tables,
                namespace = namespace.name,
            ) to foreignKeys
        }

        /**
         * Table collisions are reported over final (overridden) names, before any record lowers.
         * Only keyed records have tables, nested ones included; the second record in source order
         * is blamed.
         */
        private fun tableCollisions() {
            context.records
                .filter { context.catalog[it.qualifiedName] != null }
                .groupBy { context.catalog[it.qualifiedName]!!.tableNameRaw }
                .values
                .filter { it.size > 1 }
                .forEach { colliding ->
                    context.diagnostics +=
                        Diagnostic(
                            SqlCodes.TABLE_COLLISION,
                            "models ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to table '${context.catalog[colliding.first().qualifiedName]!!.tableNameRaw}'",
                            colliding[1].span,
                            help = "set `@sql(table: \"…\")` on one of them",
                        )
                }
        }

        /**
         * Tables, primary keys, uniques, and indexes share one Postgres namespace per schema, so a
         * derived name such as `uq_order_line_id` can be claimed by two tables. Records that lower
         * to the same table are already reported by [tableCollisions]; only the first of them
         * contributes names here.
         */
        private fun relationCollisions() {
            val holders = mutableMapOf<String, Relation>()
            context.relations.forEach { relation ->
                val previous = holders.putIfAbsent(relation.name, relation) ?: return@forEach
                context.error(
                    SqlCodes.NAME_COLLISION,
                    "relation name '${relation.name}' is already used by ${previous.kind} (${previous.span.file}:${previous.span.startLine})",
                    relation.span,
                    help = "rename one of them, or set `@sql(table: \"…\")` on one",
                )
            }
        }
    }

    /** Truncates to Postgres's limit, reporting once per identifier. */
    internal fun identifier(
        name: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
    ): String {
        val result = Naming.identifier(name)
        if (result != name) {
            diagnostics +=
                Diagnostic(
                    SqlCodes.IDENTIFIER_TRUNCATED,
                    "identifier '$name' exceeds 63 bytes; truncated to '$result'",
                    span,
                    help =
                        "shorten the name with `@sql(table: \"…\")` or `@sql(column: \"…\")` to choose it yourself",
                )
        }
        return result
    }

    private fun schemaCollisions(
        namespaces: List<Namespace>,
        names: Map<String, String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        collidingNamespaces(namespaces) { names.getValue(it.name) }
            .forEach { group ->
                diagnostics +=
                    Diagnostic(
                        SqlCodes.SCHEMA_COLLISION,
                        "schemas ${englishList(group.map { it.name })} ${if (group.size > 2) "all" else "both"} lower to Postgres schema '${names.getValue(group.first().name)}'",
                        group[1].span,
                        help = "set `@sql(schema: \"…\")` on one of them",
                    )
            }
    }

    internal fun englishList(names: List<String>): String =
        if (names.size <= 1) names.joinToString("")
        else names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * The state every concern of one namespace's lowering reads and writes: the schema and its
 * [catalog], the tables and relation names claimed so far, and the diagnostics. The three concerns
 * reach each other through [recordLowering], [unionLowering] and [childTables], since a record's
 * field may be a union, a union's member a record, and a child table's element either.
 */
internal class LoweringContext(
    val schema: Schema,
    val catalog: Catalog,
    val namespace: Namespace,
    val schemaName: String,
    val overrides: OverrideNames,
    val diagnostics: MutableList<Diagnostic>,
) {
    lateinit var recordLowering: RecordLowering
    lateinit var unionLowering: UnionLowering
    lateinit var childTables: ChildTables

    /** Every name a table puts in the schema's relation namespace, with where it came from. */
    val relations = mutableListOf<Relation>()

    /** Every table name claimed so far, with the claim that took it first. */
    val claimedTables = mutableMapOf<String, TableClaim>()

    /**
     * Every record in the namespace, top-level or nested, each top-level declaration's tree in
     * order: a record first, then the records declared inside it.
     */
    val records: List<RecordType> =
        namespace.declarations.flatMap { it.selfAndNested() }.filterIsInstance<RecordType>()

    /**
     * Records a table's names for `relationCollisions`. Two records that lower to the same table
     * are a table collision, already reported by `tableCollisions`; any other second claim on a
     * table name (a child table against a record's table, or two child tables) is a name collision
     * reported here, at the later claimant. Either way only the first claimant contributes names.
     */
    internal fun claim(
        claimant: TableClaim,
        tableName: String,
        primaryKeyName: String?,
        uniques: List<Pair<Span, Unique>>,
        indexes: List<Pair<Span, Index>>,
    ) {
        val previous = claimedTables.putIfAbsent(tableName, claimant)
        if (previous != null) {
            val sameRecordTable =
                claimant.recordTable != null && claimant.recordTable == previous.recordTable
            if (!sameRecordTable) {
                error(
                    SqlCodes.NAME_COLLISION,
                    "relation name '$tableName' is already used by ${previous.kind} (${previous.span.file}:${previous.span.startLine})",
                    claimant.span,
                    help = "rename one of them, or set `@sql(table: \"…\")` on one",
                )
            }
            return
        }
        val span = claimant.span
        relations += Relation(tableName, claimant.kind, span)
        primaryKeyName?.let { relations += Relation(it, "primary key of '$tableName'", span) }
        uniques.forEach { (at, u) -> relations += Relation(u.name, "unique '${u.name}'", at) }
        indexes.forEach { (at, ix) -> relations += Relation(ix.name, "index '${ix.name}'", at) }
    }

    fun identifier(name: String, span: Span): String =
        SqlLowering.identifier(name, span, diagnostics)

    fun error(code: DiagnosticCode, message: String, span: Span, help: String? = null) {
        diagnostics += Diagnostic(code, message, span, help)
    }
}

/**
 * Who claims a table name: [kind] names it in messages, and [recordTable] is the record's own table
 * name before truncation, or null for a child table.
 */
internal class TableClaim(val kind: String, val span: Span, val recordTable: String?)

/** A name in the schema's relation namespace and the declaration that put it there. */
internal class Relation(val name: String, val kind: String, val span: Span)

/** The help for a `json` lowering the field could do without. */
internal const val DROP_JSON_HELP =
    "remove `strategy: json` to get the default mapping for this field"

/** The help for a `json` lowering that is the only mapping the shape has. */
internal const val JSONB_ONLY_HELP = "keep jsonb; Postgres has no typed mapping for this shape"
