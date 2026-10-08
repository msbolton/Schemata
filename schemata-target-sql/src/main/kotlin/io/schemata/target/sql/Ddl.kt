package io.schemata.target.sql

import io.schemata.core.ir.OnDelete
import io.schemata.target.sql.Naming.literal
import io.schemata.target.sql.Naming.quote

/**
 * Postgres DDL text for the relational model, one statement per function. Names are final and
 * quoted here; nothing in this object decides a name, a constraint, or a type, so the renderer and
 * `schemata migrate` print identical statements for the same table or column.
 */
object Ddl {
    /**
     * `CREATE SCHEMA IF NOT EXISTS`, so a file may be applied to a database that has the schema.
     */
    fun createSchema(schema: String): String = "CREATE SCHEMA IF NOT EXISTS ${quote(schema)};"

    /**
     * `CREATE TABLE` with every column, then the primary key, uniques, and checks. A column's
     * [Column.notes] trail its line as a `-- schemata:` comment.
     */
    fun createTable(schema: String, table: Table): String = buildString {
        val s = quote(schema)
        appendLine("CREATE TABLE $s.${quote(table.name)} (")
        val lines = bodyLines(table)
        lines.forEachIndexed { i, (text, note) ->
            append("  $text")
            if (i < lines.lastIndex) append(",")
            note?.let { append("  -- schemata: $it") }
            appendLine()
        }
        appendLine(");")
    }

    /** A plain, non-unique index; uniques are constraints in the table itself. */
    fun createIndex(schema: String, table: String, index: Index): String =
        "CREATE INDEX ${quote(index.name)} ON ${quote(schema)}.${quote(table)} (${columns(index.columns)});"

    /** `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY`, printed after every table exists. */
    fun addForeignKey(fk: ForeignKey): String = buildString {
        append(
            "ALTER TABLE ${quote(fk.schema)}.${quote(fk.table)} ADD CONSTRAINT ${quote(fk.name)} "
        )
        append("FOREIGN KEY (${columns(fk.columns)}) ")
        append(
            "REFERENCES ${quote(fk.targetSchema)}.${quote(fk.targetTable)} (${columns(fk.targetColumns)})"
        )
        // RESTRICT is spelled by saying nothing: Postgres's default refuses the delete too.
        when (fk.onDelete) {
            OnDelete.RESTRICT -> {}
            OnDelete.CASCADE -> append(" ON DELETE CASCADE")
            OnDelete.SET_NULL -> append(" ON DELETE SET NULL")
        }
        append(";")
    }

    /** `COMMENT ON TABLE`; a null [text] removes the comment. */
    fun commentOnTable(schema: String, table: String, text: String?): String =
        if (text == null) {
            "COMMENT ON TABLE ${quote(schema)}.${quote(table)} IS NULL;"
        } else {
            "COMMENT ON TABLE ${quote(schema)}.${quote(table)} IS ${literal(text)};"
        }

    /** `COMMENT ON COLUMN`; a null [text] removes the comment. */
    fun commentOnColumn(schema: String, table: String, column: String, text: String?): String =
        if (text == null) {
            "COMMENT ON COLUMN ${quote(schema)}.${quote(table)}.${quote(column)} IS NULL;"
        } else {
            "COMMENT ON COLUMN ${quote(schema)}.${quote(table)}.${quote(column)} IS ${literal(text)};"
        }

    /** A column's type, `NOT NULL` unless nullable, and its default; the name is not included. */
    fun columnDefinition(column: Column): String = buildString {
        append(spell(column.type))
        if (!column.nullable) append(" NOT NULL")
        column.default?.let { append(" DEFAULT $it") }
    }

    /** The table-level `CONSTRAINT … PRIMARY KEY` clause. */
    fun primaryKey(name: String, columns: List<String>): String =
        "CONSTRAINT ${quote(name)} PRIMARY KEY (${columns(columns)})"

    /** The table-level `CONSTRAINT … UNIQUE` clause. */
    fun unique(unique: Unique): String =
        "CONSTRAINT ${quote(unique.name)} UNIQUE (${columns(unique.columns)})"

    /** The table-level `CONSTRAINT … CHECK` clause. */
    fun check(check: Check): String = "CONSTRAINT ${quote(check.name)} CHECK (${check.expression})"

    /** The Postgres spelling of [type]; an `@sql(type)` override is printed verbatim. */
    fun spell(type: ColumnType): String =
        when (type) {
            ColumnType.BOOLEAN -> "boolean"
            ColumnType.INTEGER -> "integer"
            ColumnType.BIGINT -> "bigint"
            ColumnType.REAL -> "real"
            ColumnType.DOUBLE -> "double precision"
            is ColumnType.NUMERIC -> "numeric(${type.precision}, ${type.scale})"
            ColumnType.TEXT -> "text"
            is ColumnType.VARCHAR -> "varchar(${type.length})"
            ColumnType.BYTEA -> "bytea"
            ColumnType.UUID -> "uuid"
            ColumnType.DATE -> "date"
            ColumnType.TIME -> "time"
            ColumnType.TIMESTAMPTZ -> "timestamptz"
            ColumnType.INTERVAL -> "interval"
            ColumnType.JSONB -> "jsonb"
            is ColumnType.ARRAY -> "${spell(type.element)}[]"
            is ColumnType.RAW -> type.spelling
        }

    /** [names] quoted and comma-separated, for a column list. */
    fun columns(names: List<String>): String = names.joinToString(", ") { quote(it) }

    /** Every line of a table's body, with the note that trails it: columns, then constraints. */
    private fun bodyLines(table: Table): List<Pair<String, String?>> {
        val definitions = table.columns.map { columnLine(it) }
        val key =
            if (table.primaryKey.isEmpty()) emptyList()
            else {
                val name =
                    checkNotNull(table.primaryKeyName) {
                        "table '${table.name}' has a primary key but no constraint name"
                    }
                listOf(primaryKey(name, table.primaryKey) to null)
            }
        val constraints =
            table.uniques.map { unique(it) to null } + table.checks.map { check(it) to null }
        return definitions + key + constraints
    }

    /** One column's line and its note. */
    private fun columnLine(column: Column): Pair<String, String?> {
        val text = "${quote(column.name)} ${columnDefinition(column)}"
        val note = column.notes.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return text to note
    }
}
