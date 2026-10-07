package io.schemata.migrate

import io.schemata.lang.Span
import io.schemata.target.sql.Check
import io.schemata.target.sql.Column
import io.schemata.target.sql.ColumnType
import io.schemata.target.sql.ForeignKey
import io.schemata.target.sql.Index
import io.schemata.target.sql.Table
import io.schemata.target.sql.Unique

enum class Risk {
    CLEAN,
    MAY_FAIL,
    DESTRUCTIVE,
}

/** The IR path a step is about (`shop.orders.Order.note`) and the span its diagnostic points at. */
data class Subject(val path: String, val span: Span)

/** A table, by schema and name, as the step's SQL will address it. */
data class At(val schema: String, val table: String)

sealed interface Constraint {
    val name: String

    data class PrimaryKey(override val name: String, val columns: List<String>) : Constraint

    data class UniqueKey(val unique: Unique) : Constraint {
        override val name
            get() = unique.name
    }

    data class CheckConstraint(val check: Check) : Constraint {
        override val name
            get() = check.name
    }

    data class Foreign(val fk: ForeignKey) : Constraint {
        override val name
            get() = fk.name
    }
}

/**
 * One DDL statement of a migration. [risk] says what applying it can cost; [reason] is the message
 * clause (`every value the column holds` for a destructive step, `a row holds NULL` for one that
 * may fail) and [help] the fix, both null when the step is clean.
 */
sealed interface Step {
    val subject: Subject
    val risk: Risk
        get() = Risk.CLEAN

    val reason: String?
        get() = null

    val help: String?
        get() = null
}

data class CreateSchema(val schema: String, override val subject: Subject) : Step

data class DropSchema(val schema: String, override val subject: Subject) : Step

data class CreateTable(val schema: String, val table: Table, override val subject: Subject) : Step

data class DropTable(
    val at: At,
    override val subject: Subject,
    override val reason: String,
    override val help: String,
) : Step {
    override val risk
        get() = Risk.DESTRUCTIVE
}

data class RenameTable(val at: At, val to: String, override val subject: Subject) : Step

data class SetSchema(val at: At, val to: String, override val subject: Subject) : Step

data class AddColumn(val at: At, val column: Column, override val subject: Subject) : Step

data class DropColumn(
    val at: At,
    val column: String,
    override val subject: Subject,
    override val reason: String,
    override val help: String,
) : Step {
    override val risk
        get() = Risk.DESTRUCTIVE
}

data class RenameColumn(
    val at: At,
    val from: String,
    val to: String,
    override val subject: Subject,
) : Step

data class AlterColumnType(
    val at: At,
    val column: String,
    val type: ColumnType,
    override val subject: Subject,
    override val risk: Risk,
    override val reason: String?,
    override val help: String?,
) : Step

data class SetNotNull(
    val at: At,
    val column: String,
    override val subject: Subject,
    override val risk: Risk,
    override val reason: String?,
    override val help: String?,
) : Step

data class DropNotNull(val at: At, val column: String, override val subject: Subject) : Step

data class SetDefault(
    val at: At,
    val column: String,
    val default: String,
    override val subject: Subject,
) : Step

data class DropDefault(val at: At, val column: String, override val subject: Subject) : Step

/**
 * `UPDATE … SET col = default WHERE col IS NULL`, before a `SetNotNull` that has a default to fill
 * with.
 */
data class Backfill(
    val at: At,
    val column: String,
    val default: String,
    override val subject: Subject,
) : Step

/**
 * One `UPDATE` rewriting every enum value of a column renamed under its ordinal, [renames] old name
 * to new, all at once so a swap or a chain of renames never collapses two values into one; [array]
 * rewrites the values inside an array column instead.
 */
data class RenameValue(
    val at: At,
    val column: String,
    val renames: List<Pair<String, String>>,
    val array: Boolean,
    override val subject: Subject,
) : Step

/**
 * [cascade] for a primary key or unique, so a foreign key another namespace's file still holds on
 * it never blocks the drop; that file drops its own foreign key `IF EXISTS` and re-adds it.
 */
data class DropConstraint(
    val at: At,
    val name: String,
    val ifExists: Boolean,
    val cascade: Boolean,
    override val subject: Subject,
) : Step

data class AddConstraint(
    val at: At,
    val constraint: Constraint,
    override val subject: Subject,
    override val risk: Risk,
    override val reason: String?,
    override val help: String?,
) : Step

data class RenameConstraint(
    val at: At,
    val from: String,
    val to: String,
    override val subject: Subject,
) : Step

data class DropIndex(val schema: String, val name: String, override val subject: Subject) : Step

data class CreateIndex(val at: At, val index: Index, override val subject: Subject) : Step

data class RenameIndex(
    val schema: String,
    val from: String,
    val to: String,
    override val subject: Subject,
) : Step

data class Comment(
    val at: At,
    val column: String?,
    val text: String?,
    override val subject: Subject,
) : Step

/**
 * The steps for one namespace, written to `migrate/<path>`; [path] is the SQL target's own file
 * path for the namespace.
 */
data class NamespaceMigration(val path: String, val schemaName: String, val steps: List<Step>)

data class Migration(val namespaces: List<NamespaceMigration>) {
    val steps: List<Step>
        get() = namespaces.flatMap { it.steps }

    val isEmpty: Boolean
        get() = steps.isEmpty()
}
