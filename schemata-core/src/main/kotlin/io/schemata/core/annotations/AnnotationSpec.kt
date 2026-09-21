package io.schemata.core.annotations

/** What an annotation may decorate. */
enum class Element(val displayName: String) {
    NAMESPACE("namespace"),
    RECORD("record"),
    ENUM("enum"),
    UNION("union"),
    ALIAS("alias"),
    FIELD("field"),
    ENUM_VALUE("enum value"),
}

enum class ValueKind {
    FLAG,
    STRING,
    INT,
    BOOL,
    NAME,
    NAME_TUPLE,
}

/**
 * The tune-only rule as a type: a key may change a name, a storage representation, or a mapping
 * strategy of something the IR already contains, and nothing else.
 */
enum class Role {
    NAME,
    REPRESENTATION,
    STRATEGY,
}

/**
 * One key a target accepts. [target] is `""` for target-agnostic keys owned by core. [choices]
 * restricts a [ValueKind.NAME]; [optional] lets a valued key be written bare, as `@deprecated`.
 */
data class AnnotationSpec(
    val target: String,
    val key: String,
    val elements: Set<Element>,
    val valueKind: ValueKind,
    val role: Role,
    val choices: Set<String>? = null,
    val optional: Boolean = false,
)
