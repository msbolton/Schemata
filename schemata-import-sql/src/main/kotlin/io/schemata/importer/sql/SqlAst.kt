package io.schemata.importer.sql

/**
 * An expression from a `CHECK` or a `DEFAULT`, in the few shapes an importer recognises. Casts and
 * redundant parentheses are gone; anything outside these shapes is [Raw] with its text.
 */
sealed interface SqlExpr {
    /** A column; a qualified name keeps its last segment. */
    data class Col(val name: String) : SqlExpr

    data class Str(val value: String) : SqlExpr

    /** A number as written, a leading minus included. */
    data class Num(val text: String) : SqlExpr

    data class Bool(val value: Boolean) : SqlExpr

    data object Null : SqlExpr

    /** A function call; a qualified function name keeps its last segment. */
    data class Call(val name: String, val args: List<SqlExpr>) : SqlExpr

    /** A binary operator: `= <> != < > <= >= ~ ~*`, or `and`/`or` in lower case. */
    data class Bin(val op: String, val left: SqlExpr, val right: SqlExpr) : SqlExpr

    data class Not(val expr: SqlExpr) : SqlExpr

    /** `IS NULL`, or `IS NOT NULL` when [not]. */
    data class IsNull(val expr: SqlExpr, val not: Boolean) : SqlExpr

    /** `IN (…)`, and `= ANY (ARRAY[…])` as pg_dump writes it. */
    data class In(val expr: SqlExpr, val items: List<SqlExpr>) : SqlExpr

    data class Between(val expr: SqlExpr, val low: SqlExpr, val high: SqlExpr) : SqlExpr

    /** Anything else, as [SqlExprs.text] renders its tokens. */
    data class Raw(val text: String) : SqlExpr
}

/**
 * A column. [type] is the type as written in the target's canonical spelling (see [SqlReader]).
 * [notNull] holds when the definition itself rules out null: `NOT NULL`, an inline `PRIMARY KEY`,
 * an identity, or a serial type. [note] is the text after `schemata:` in a comment trailing the
 * column; [doc] is left null here, as docs come from `COMMENT ON`. [identity] marks `GENERATED … AS
 * IDENTITY`. [generated] is the expression of a `GENERATED ALWAYS AS (…) STORED` column, which has
 * no place in the result, so it is not in the table's `dropped` list.
 */
data class SqlColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val default: SqlExpr?,
    val note: String?,
    val doc: String?,
    val identity: Boolean,
    val pos: SqlPos,
    val generated: SqlExpr? = null,
)

/**
 * A table constraint. A constraint written inline on a column becomes one of these with that column
 * and, unless `CONSTRAINT name` precedes it, a null [name].
 */
sealed interface SqlConstraint {
    val name: String?

    data class PrimaryKey(override val name: String?, val columns: List<String>) : SqlConstraint

    data class Unique(override val name: String?, val columns: List<String>) : SqlConstraint

    /** [text] is the expression between the `CHECK` parentheses, as [SqlExprs.text] renders it. */
    data class Check(override val name: String?, val expr: SqlExpr, val text: String) :
        SqlConstraint

    /**
     * An empty [refColumns] refers to the target's primary key. [onDelete] and [onUpdate] are the
     * actions in upper case (`CASCADE`, `SET NULL`, …) or null when not written. [extras] are the
     * other clauses that change behaviour: `MATCH FULL`, `MATCH PARTIAL`, `DEFERRABLE`, `INITIALLY
     * DEFERRED`.
     */
    data class ForeignKey(
        override val name: String?,
        val columns: List<String>,
        val refSchema: String?,
        val refTable: String,
        val refColumns: List<String>,
        val onDelete: String?,
        val onUpdate: String?,
        val extras: List<String>,
    ) : SqlConstraint
}

/**
 * A `CREATE TABLE`. [constraints] holds the inline column constraints and the table constraints in
 * the order written. [dropped] names what the table carried that has no place here: `EXCLUDE`
 * constraints, `LIKE`, a deferral on a primary key, unique or check constraint, and trailing
 * clauses such as `INHERITS` or `PARTITION BY`. [doc] is left null here, as docs come from `COMMENT
 * ON`.
 */
data class SqlTable(
    val schema: String?,
    val name: String,
    val columns: List<SqlColumn>,
    val constraints: List<SqlConstraint>,
    val dropped: List<String>,
    val doc: String?,
    val pos: SqlPos,
)

sealed interface SqlStatement {
    data class CreateSchema(val name: String, val pos: SqlPos) : SqlStatement

    data class CreateTable(val table: SqlTable) : SqlStatement

    /** `ALTER TABLE … ADD [CONSTRAINT name] …`. */
    data class AlterAdd(
        val schema: String?,
        val table: String,
        val constraint: SqlConstraint,
        val pos: SqlPos,
    ) : SqlStatement

    /**
     * A `CREATE INDEX` over plain columns. [using] is the access method when written; [filtered]
     * marks a partial index (`WHERE …`).
     */
    data class CreateIndex(
        val name: String?,
        val unique: Boolean,
        val schema: String?,
        val table: String,
        val columns: List<String>,
        val using: String?,
        val filtered: Boolean,
        val pos: SqlPos,
    ) : SqlStatement

    /** `COMMENT ON TABLE` ([kind] `TABLE`, [column] null) or `COMMENT ON COLUMN` (`COLUMN`). */
    data class CommentOn(
        val kind: String,
        val schema: String?,
        val table: String,
        val column: String?,
        val text: String,
        val pos: SqlPos,
    ) : SqlStatement

    /** A statement that defines something an importer cannot keep: [kind] is `CREATE VIEW`, …. */
    data class Dropped(val kind: String, val pos: SqlPos) : SqlStatement

    /** A statement with no bearing on the schema's shape: [kind] is `SET`, `GRANT`, …. */
    data class Ignored(val kind: String) : SqlStatement
}

data class SqlParseError(val pos: SqlPos, val message: String)

data class SqlFile(
    val path: String,
    val statements: List<SqlStatement>,
    val errors: List<SqlParseError>,
)
