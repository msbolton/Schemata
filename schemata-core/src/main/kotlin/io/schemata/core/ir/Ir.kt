package io.schemata.core.ir

import io.schemata.lang.Span
import java.math.BigDecimal

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
data class Namespace(
    val name: String,
    val declarations: List<TypeDecl>,
    val span: Span,
    val annotations: Annotations = Annotations.NONE,
)

sealed interface TypeDecl {
    val qualifiedName: QualifiedName
    val name: String
    val nested: List<TypeDecl>
    val doc: String?
    val span: Span
    val nameSpan: Span
    val annotations: Annotations
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
    override val annotations: Annotations = Annotations.NONE,
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
    override val annotations: Annotations = Annotations.NONE,
) : TypeDecl

data class UnionType(
    override val qualifiedName: QualifiedName,
    override val name: String,
    val members: List<UnionMember>,
    override val nested: List<TypeDecl>,
    override val doc: String?,
    override val span: Span,
    override val nameSpan: Span,
    override val annotations: Annotations = Annotations.NONE,
) : TypeDecl

/**
 * [ordinal] is the field's stable identity: the explicit `#n`, else declaration order. [default]
 * has been checked against the type and its refinements; [aliasName] records a transparent alias.
 */
data class Field(
    val ordinal: Int,
    val name: String,
    val type: Type,
    val nullable: Boolean,
    val default: Value?,
    val aliasName: String?,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
    val annotations: Annotations = Annotations.NONE,
)

data class EnumValue(
    val ordinal: Int,
    val name: String,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
    val annotations: Annotations = Annotations.NONE,
)

data class UnionMember(val ordinal: Int, val type: Type, val doc: String?, val span: Span)

/** A checked default. Numeric literals keep the scale they were written with. */
sealed interface Value

data class IntValue(val value: Long) : Value

data class RealValue(val value: BigDecimal) : Value

data class StringValue(val value: String) : Value

data class BoolValue(val value: Boolean) : Value

data class EnumRef(val enum: QualifiedName, val value: String) : Value

/** Validated annotations, keyed by target (`""` for target-agnostic keys) then key. */
data class Annotations(val entries: Map<String, Map<String, AnnotationValue>>) {
    val isEmpty: Boolean
        get() = entries.isEmpty()

    operator fun get(target: String): Map<String, AnnotationValue> = entries[target] ?: emptyMap()

    companion object {
        val NONE = Annotations(emptyMap())
    }
}

sealed interface AnnotationValue {
    data object Flag : AnnotationValue

    data class Str(val value: String) : AnnotationValue

    data class Num(val value: Long) : AnnotationValue

    data class Bool(val value: Boolean) : AnnotationValue

    data class Name(val value: String) : AnnotationValue

    data class Names(val values: List<String>) : AnnotationValue
}

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

/** Bounds are exact so integer, float, decimal, length and count limits share one shape. */
data class Refinements(
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val pattern: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
) {
    val isEmpty: Boolean
        get() = this == NONE

    /**
     * True when a bound the user wrote is present; `decimal`'s precision and scale are part of the
     * type, not a bound.
     */
    val hasBounds: Boolean
        get() = min != null || max != null || pattern != null

    companion object {
        val NONE = Refinements()
    }
}

/** Reserved ordinal ranges and names; ranges are kept as written so a wide range costs nothing. */
data class Reserved(val ordinals: List<IntRange>, val names: Set<String>) {
    operator fun contains(ordinal: Int): Boolean = ordinals.any { ordinal in it }

    companion object {
        val NONE = Reserved(emptyList(), emptySet())
    }
}

/** [refinementKeys] is the closed set of named refinements the type accepts. */
enum class Builtin(val typeName: String, val refinementKeys: Set<String>) {
    BOOL("bool", emptySet()),
    INT32("int32", setOf("min", "max")),
    INT64("int64", setOf("min", "max")),
    FLOAT32("float32", setOf("min", "max")),
    FLOAT64("float64", setOf("min", "max")),
    DECIMAL("decimal", setOf("min", "max")),
    STRING("string", setOf("min", "max", "pattern")),
    BYTES("bytes", setOf("min", "max")),
    UUID("uuid", emptySet()),
    DATE("date", emptySet()),
    TIME("time", emptySet()),
    INSTANT("instant", emptySet()),
    DURATION("duration", emptySet());

    companion object {
        fun byName(name: String): Builtin? = entries.firstOrNull { it.typeName == name }
    }
}
