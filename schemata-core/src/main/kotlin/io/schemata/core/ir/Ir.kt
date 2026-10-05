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

/** Every namespace's services, in namespace then source order. */
fun Schema.services(): List<Service> = namespaces.flatMap { it.services }

fun Schema.service(name: QualifiedName): Service? =
    services().firstOrNull { it.qualifiedName == name }

/**
 * [span] is the `namespace` declaration of the first file (in sorted-path order) declaring it.
 * [services] are in sorted-path then source order; a service is not a [TypeDecl], so nothing that
 * walks [declarations] sees one.
 */
data class Namespace(
    val name: String,
    val declarations: List<TypeDecl>,
    val span: Span,
    val annotations: Annotations = Annotations.NONE,
    val services: List<Service> = emptyList(),
)

/** A `service` block: operations in source order; [reserved] holds retired operations. */
data class Service(
    val qualifiedName: QualifiedName,
    val name: String,
    val operations: List<Operation>,
    val reserved: Reserved,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
    val annotations: Annotations = Annotations.NONE,
)

/**
 * [ordinal] is the operation's stable identity, as a field's. [request] is null for `()`,
 * [response] when the operation returns nothing, [binding] when no HTTP route is written.
 */
data class Operation(
    val ordinal: Int,
    val name: String,
    val request: Payload?,
    val response: Payload?,
    val binding: HttpBinding?,
    val doc: String?,
    val span: Span,
    val nameSpan: Span,
    val annotations: Annotations = Annotations.NONE,
)

/** [target] is a record or a union; [stream] means many messages instead of one. */
data class Payload(val target: QualifiedName, val stream: Boolean)

/** [path] as written; [parameters] are its `{name}` segments in path order. */
data class HttpBinding(val verb: Verb, val path: String, val parameters: List<String>)

enum class Verb {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
    OPTIONS;

    /** The verb as written in a binding. */
    val lower: String
        get() = name.lowercase()

    /** Verbs whose request fields become parameters rather than a body. */
    val parameterised: Boolean
        get() = this == GET || this == DELETE || this == HEAD || this == OPTIONS
}

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

/** The word a diagnostic uses for a declaration: `record`, `enum`, or `union`. */
val TypeDecl.kindWord: String
    get() =
        when (this) {
            is RecordType -> "record"
            is EnumType -> "enum"
            is UnionType -> "union"
        }

/** The declarations on the way to [qn], outermost first: `Order`, then `Order.Line`. */
fun Schema.declarationPath(qn: QualifiedName): List<TypeDecl> =
    qn.path.indices.map { i -> lookup(QualifiedName(qn.namespace, qn.path.take(i + 1))) }

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
