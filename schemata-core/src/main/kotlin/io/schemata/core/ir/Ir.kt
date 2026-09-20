package io.schemata.core.ir

import io.schemata.lang.Span
import io.schemata.lang.ast.Literal

/**
 * The whole compilation after analysis. An acyclic tree of data classes: types refer to other
 * declarations by [QualifiedName] and are resolved through [lookup], never by object reference, so
 * recursive schemas stay structurally comparable. Namespaces are sorted by name.
 */
data class Schema(val namespaces: List<Namespace>) {
    private val index: Map<QualifiedName, TypeDecl> by lazy {
        namespaces
            .flatMap { ns -> ns.declarations.flatMap { it.selfAndNested() } }
            .associateBy { it.qualifiedName }
    }

    fun lookupOrNull(name: QualifiedName): TypeDecl? = index[name]

    fun lookup(name: QualifiedName): TypeDecl =
        lookupOrNull(name) ?: error("no declaration named $name in the schema")
}

/** `shop.orders` + `[Order, Address]` is `shop.orders.Order.Address`. */
data class QualifiedName(val namespace: String, val path: List<String>) {
    val simpleName: String
        get() = path.last()

    override fun toString(): String = (listOf(namespace) + path).joinToString(".")
}

/** [span] is the `namespace` declaration of the first file (in sorted-path order) declaring it. */
data class Namespace(val name: String, val declarations: List<TypeDecl>, val span: Span)

sealed interface TypeDecl {
    val qualifiedName: QualifiedName
    val name: String
    val nested: List<TypeDecl>
    val doc: String?
    val span: Span
    val nameSpan: Span
}

fun TypeDecl.selfAndNested(): List<TypeDecl> = listOf(this) + nested.flatMap { it.selfAndNested() }

/** [recursive] is true when this record can reach itself through [Ref]s. */
data class RecordType(
    override val qualifiedName: QualifiedName,
    override val name: String,
    val fields: List<Field>,
    val reserved: Reserved,
    val recursive: Boolean,
    override val nested: List<TypeDecl>,
    override val doc: String?,
    override val span: Span,
    override val nameSpan: Span,
) : TypeDecl

data class EnumType(
    override val qualifiedName: QualifiedName,
    override val name: String,
    val values: List<EnumValue>,
    val reserved: Reserved,
    override val nested: List<TypeDecl>,
    override val doc: String?,
    override val span: Span,
    override val nameSpan: Span,
) : TypeDecl

data class UnionType(
    override val qualifiedName: QualifiedName,
    override val name: String,
    val members: List<UnionMember>,
    override val nested: List<TypeDecl>,
    override val doc: String?,
    override val span: Span,
    override val nameSpan: Span,
) : TypeDecl

/**
 * [ordinal] is the field's stable identity: the explicit `#n`, else declaration order. [default] is
 * carried unvalidated until B2 (SCH-20); [aliasName] records a transparent alias.
 */
data class Field(
    val ordinal: Int,
    val name: String,
    val type: Type,
    val nullable: Boolean,
    val default: Literal?,
    val aliasName: String?,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
)

data class EnumValue(
    val ordinal: Int,
    val name: String,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
)

data class UnionMember(val ordinal: Int, val type: Type, val doc: String?, val span: Span)

sealed interface Type

data class Scalar(val builtin: Builtin, val refinements: Refinements = Refinements()) : Type

data class ListOf(
    val element: Type,
    val nullableElement: Boolean,
    val refinements: Refinements = Refinements(),
) : Type

data class MapOf(
    val key: Type,
    val value: Type,
    val nullableValue: Boolean,
    val refinements: Refinements = Refinements(),
) : Type

/** A reference to a record, enum, or union by qualified name; resolve with [Schema.lookup]. */
data class Ref(val target: QualifiedName) : Type

/** Always empty in B1; B2 (SCH-20) fills it from `string(max = 5)` and friends. */
data class Refinements(
    val min: Long? = null,
    val max: Long? = null,
    val pattern: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
)

data class Reserved(val ordinals: Set<Int>, val names: Set<String>) {
    companion object {
        val NONE = Reserved(emptySet(), emptySet())
    }
}

enum class Builtin(val typeName: String) {
    BOOL("bool"),
    INT32("int32"),
    INT64("int64"),
    FLOAT32("float32"),
    FLOAT64("float64"),
    DECIMAL("decimal"),
    STRING("string"),
    BYTES("bytes"),
    UUID("uuid"),
    DATE("date"),
    TIME("time"),
    INSTANT("instant"),
    DURATION("duration");

    companion object {
        fun byName(name: String): Builtin? = entries.firstOrNull { it.typeName == name }
    }
}
