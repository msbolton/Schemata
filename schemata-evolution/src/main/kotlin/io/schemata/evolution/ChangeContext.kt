package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.selfAndNested
import io.schemata.target.Names
import io.schemata.target.bool
import io.schemata.target.deprecated
import io.schemata.target.flag
import io.schemata.target.string

/** Whether a removed member's ordinal, name, both, or neither are still reserved in NEW. */
enum class ReservedStatus {
    BOTH,
    ORDINAL_ONLY,
    NAME_ONLY,
    NEITHER,
}

/**
 * Everything a [Rulebook] needs beyond the bare [Change]: what NEW still reserves, what OLD
 * deprecated, the name a target actually emits for a member, and the roles (`keyed`, `root`,
 * `open`, `hasTable`) a target's own lowering would assign a record.
 */
class ChangeContext(val old: Schema, val new: Schema) {
    /** Both the ordinal and the name of a removed member are reserved in NEW's declaration. */
    fun reservedInNew(declPath: QualifiedName, ordinal: Int, name: String): ReservedStatus {
        val reserved = Differ.reservedOf(new.lookup(declPath))
        val ordinalReserved = reserved != null && ordinal in reserved
        val nameReserved = reserved != null && name in reserved.names
        return when {
            ordinalReserved && nameReserved -> ReservedStatus.BOTH
            ordinalReserved -> ReservedStatus.ORDINAL_ONLY
            nameReserved -> ReservedStatus.NAME_ONLY
            else -> ReservedStatus.NEITHER
        }
    }

    /**
     * Whether the thing [change] removes, renames away from, or recasts was `@deprecated` on OLD's
     * side; false for a change with no single OLD-side element, such as an addition.
     */
    fun deprecatedInOld(change: Change): Boolean =
        when (change) {
            is FieldRemoved -> change.field.annotations.deprecated
            is FieldRenamed -> change.from.annotations.deprecated
            is FieldTypeChanged -> change.from.annotations.deprecated
            is FieldNullabilityChanged -> change.from.annotations.deprecated
            is FieldDefaultChanged -> change.from.annotations.deprecated
            is FieldRefinementChanged -> change.from.annotations.deprecated
            is EnumValueRemoved -> change.value.annotations.deprecated
            is EnumValueRenamed -> change.from.annotations.deprecated
            is DeclarationRemoved -> change.decl.annotations.deprecated
            is DeclarationKindChanged -> change.from.annotations.deprecated
            is DeprecationChanged -> !change.deprecated
            else -> false
        }

    /** The name [target] emits for [field]: its override on that side, else its declared name. */
    fun emittedFieldName(target: String, field: Field): String =
        field.annotations.string(target, overrideKey(target)) ?: field.name

    /**
     * The name [target] emits for [value]: its override, else the declared name, decorated with
     * [enum]'s emitted name the way Protobuf's generated enum constants are (`<ENUM>_<VALUE>`).
     */
    fun emittedValueName(target: String, enum: EnumType, value: EnumValue): String {
        val override = value.annotations.string(target, overrideKey(target))
        if (override != null) return override
        if (target != "proto") return value.name
        val enumName = enum.annotations.string(target, "name") ?: enum.name
        return "${Names.snakeCase(enumName).uppercase()}_${value.name.uppercase()}"
    }

    /** `@sql(key)` on a field or the record itself, Catalog's rule for a table-backed record. */
    fun isKeyed(side: Side, record: QualifiedName): Boolean {
        val decl = schema(side).lookupOrNull(record) as? RecordType ?: return false
        return decl.annotations["sql"]["key"] is AnnotationValue.Names ||
            decl.fields.any { it.annotations.flag("sql", "key") }
    }

    /** A top-level record (not nested in another declaration) not opted out with `@xsd(root)`. */
    fun isRoot(side: Side, record: QualifiedName): Boolean {
        val decl = schema(side).lookupOrNull(record) as? RecordType ?: return false
        return record.path.size == 1 && decl.annotations.bool("xsd", "root") != false
    }

    fun isOpen(side: Side, record: QualifiedName): Boolean {
        val decl = schema(side).lookupOrNull(record) as? RecordType ?: return false
        return decl.annotations.flag("jsonschema", "open")
    }

    /**
     * [record] is backed by its own Postgres table: it is keyed directly, or it is the element type
     * of some `list<Record>` field elsewhere on [side] whose `@sql(strategy)` is absent or `table`,
     * which gives that field's elements a child table of their own.
     */
    fun hasTable(side: Side, record: QualifiedName): Boolean =
        isKeyed(side, record) || isChildTable(side, record)

    private fun isChildTable(side: Side, record: QualifiedName): Boolean =
        schema(side)
            .namespaces
            .flatMap { it.declarations.flatMap { d -> d.selfAndNested() } }
            .filterIsInstance<RecordType>()
            .any { r -> r.fields.any { f -> listsTableOf(f, record) } }

    private fun listsTableOf(field: Field, record: QualifiedName): Boolean {
        val element = (field.type as? ListOf)?.element as? Ref ?: return false
        if (element.target != record) return false
        val strategy = (field.annotations["sql"]["strategy"] as? AnnotationValue.Name)?.value
        return strategy == null || strategy == "table"
    }

    private fun schema(side: Side): Schema = if (side == Side.OLD) old else new

    private fun overrideKey(target: String) = if (target == "sql") "column" else "name"
}
