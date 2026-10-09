package io.schemata.core

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/**
 * Core catalog, `SCH1xxx`. `SCH1007` (record-typed fields), `SCH1008` (unsupported constructs), and
 * `SCH1037` (an unknown refinement key, now an unknown option's SCH1049) are retired and must not
 * be reused.
 */
object CoreCodes {
    private fun error(id: String, description: String) =
        DiagnosticCode(id, Severity.ERROR, Category.SEMANTIC, description)

    private fun warning(id: String, description: String) =
        DiagnosticCode(id, Severity.WARNING, Category.SEMANTIC, description)

    val NAMESPACE_SEGMENT_NAMING = error("SCH1001", "a schema name segment is not lower_snake")
    val TYPE_NAMING = error("SCH1002", "a type name is not UpperCamel")
    val FIELD_NAMING = error("SCH1003", "a member name is not lower_snake")
    val DUPLICATE_TYPE = error("SCH1004", "the same qualified name is declared twice")
    val DUPLICATE_FIELD = error("SCH1005", "a declaration repeats a member name")
    val UNKNOWN_TYPE = error("SCH1006", "a type name resolves to nothing")
    val AMBIGUOUS_TYPE = error("SCH1009", "a bare name matches several declarations")
    val BUILTIN_SHADOWED = warning("SCH1010", "a declaration shadows a builtin type")
    val UNKNOWN_IMPORT = error("SCH1011", "an import names no schema in the compilation")
    val UNUSED_IMPORT = warning("SCH1012", "an import resolves nothing")
    val MIXED_ORDINALS = error("SCH1013", "some elements have explicit ordinals and some do not")
    val IMPLICIT_ORDINAL_STRICT =
        error("SCH1014", "an element has no explicit ordinal under --strict")
    val DUPLICATE_ORDINAL = error("SCH1019", "an ordinal is used twice in one declaration")
    val RESERVED_CONFLICT = error("SCH1020", "a name or ordinal is reserved in its declaration")
    val NULLABLE_MAP_KEY = error("SCH1021", "a map key type is nullable")
    val MAP_KEY_TYPE = error("SCH1022", "a map key type is not string, int32, or int64")
    val ALIAS_CYCLE = error("SCH1023", "an alias refers to itself")
    val DOUBLE_NULLABLE = error("SCH1024", "a nullable alias is marked nullable again")
    val DUPLICATE_UNION_MEMBER = error("SCH1025", "a union repeats a member")
    val UNION_SELF_MEMBER = error("SCH1026", "a union contains itself")
    val UNION_MEMBER_KIND = error("SCH1027", "a union member is nullable or a collection")
    val ENUM_VALUE_NAMING = error("SCH1028", "an enum value is not lower_snake")
    val DUPLICATE_ENUM_VALUE = error("SCH1029", "an enum repeats a value")
    val EMPTY_ENUM = error("SCH1030", "an enum has no values")
    val GENERIC_ARITY = error("SCH1031", "a generic type has the wrong number of arguments")
    val NOT_GENERIC = error("SCH1032", "a non-generic type is given type arguments")
    val NESTED_TYPE_NOT_FOUND = error("SCH1033", "a nested type path names no nested type")
    val RESERVED_RANGE = error("SCH1034", "a reserved range is inverted")
    val INVALID_ORDINAL = error("SCH1035", "an ordinal is not positive")
    val UNKNOWN_ANNOTATION_TARGET = error("SCH1015", "an annotation name is unknown")
    val UNKNOWN_ANNOTATION_KEY = error("SCH1016", "a key is unknown for its annotation target")
    val ANNOTATION_ELEMENT =
        error("SCH1017", "an annotation key is on an element it does not apply to")
    val ANNOTATION_VALUE =
        error("SCH1018", "an annotation's arguments have the wrong shape or value")
    val DUPLICATE_ANNOTATION = error("SCH1036", "an annotation key is given twice")
    val INVALID_REFINEMENT = error("SCH1038", "a refinement value is invalid for the type")
    val REFINEMENT_NOT_ALLOWED = error("SCH1039", "refinements are written on a non-builtin type")
    val MISSING_REFINEMENT = error("SCH1040", "decimal is missing its precision and scale")
    val DUPLICATE_REFINEMENT = error("SCH1041", "a refinement key is given twice")
    val DEFAULT_TYPE = error("SCH1042", "a default does not fit the field's type")
    val DEFAULT_VIOLATES_REFINEMENT = error("SCH1043", "a default violates a refinement")
    val NULL_DEFAULT = error("SCH1044", "a default is null")
    val IMPORT_ALIAS_NAMING = error("SCH1045", "an import alias is not lower_snake")
    val REPEATED_IMPORT = error("SCH1046", "an import is repeated")
    val PAYLOAD_KIND =
        error("SCH1047", "an operation's request or response is not a model or a union")
    val BINDING = error("SCH1048", "an operation's HTTP binding does not fit its request")
    val OPTION_NOT_APPLICABLE = error("SCH1049", "an option on a type that cannot carry it")
    val BACK_REFERENCE_TARGET =
        error("SCH1050", "a back-reference names no forward reference to its model")
    val AMBIGUOUS_BACK_REFERENCE =
        error("SCH1051", "a back-reference could follow more than one forward reference")
    val SET_NULL_REQUIRED =
        error("SCH1052", "a required reference sets itself null when its target is deleted")
    val HOISTED_NAME_COLLISION =
        error("SCH1053", "an inline shape's or enum's name collides with a declaration")
    val TIMESTAMPS_UNPINNED =
        warning("SCH1054", "timestamps are unpinned in a model with explicit ordinals")

    val all: List<DiagnosticCode> =
        listOf(
            NAMESPACE_SEGMENT_NAMING,
            TYPE_NAMING,
            FIELD_NAMING,
            DUPLICATE_TYPE,
            DUPLICATE_FIELD,
            UNKNOWN_TYPE,
            AMBIGUOUS_TYPE,
            BUILTIN_SHADOWED,
            UNKNOWN_IMPORT,
            UNUSED_IMPORT,
            MIXED_ORDINALS,
            IMPLICIT_ORDINAL_STRICT,
            DUPLICATE_ORDINAL,
            RESERVED_CONFLICT,
            NULLABLE_MAP_KEY,
            MAP_KEY_TYPE,
            ALIAS_CYCLE,
            DOUBLE_NULLABLE,
            DUPLICATE_UNION_MEMBER,
            UNION_SELF_MEMBER,
            UNION_MEMBER_KIND,
            ENUM_VALUE_NAMING,
            DUPLICATE_ENUM_VALUE,
            EMPTY_ENUM,
            GENERIC_ARITY,
            NOT_GENERIC,
            NESTED_TYPE_NOT_FOUND,
            RESERVED_RANGE,
            INVALID_ORDINAL,
            UNKNOWN_ANNOTATION_TARGET,
            UNKNOWN_ANNOTATION_KEY,
            ANNOTATION_ELEMENT,
            ANNOTATION_VALUE,
            DUPLICATE_ANNOTATION,
            INVALID_REFINEMENT,
            REFINEMENT_NOT_ALLOWED,
            MISSING_REFINEMENT,
            DUPLICATE_REFINEMENT,
            DEFAULT_TYPE,
            DEFAULT_VIOLATES_REFINEMENT,
            NULL_DEFAULT,
            IMPORT_ALIAS_NAMING,
            REPEATED_IMPORT,
            PAYLOAD_KIND,
            BINDING,
            OPTION_NOT_APPLICABLE,
            BACK_REFERENCE_TARGET,
            AMBIGUOUS_BACK_REFERENCE,
            SET_NULL_REQUIRED,
            HOISTED_NAME_COLLISION,
            TIMESTAMPS_UNPINNED,
        )
}
