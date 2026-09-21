package io.schemata.core

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** Core catalog, `SCH1xxx`. `SCH1007` (record-typed fields) is retired and must not be reused. */
object CoreCodes {
    private fun error(id: String) = DiagnosticCode(id, Severity.ERROR, Category.SEMANTIC)

    private fun warning(id: String) = DiagnosticCode(id, Severity.WARNING, Category.SEMANTIC)

    val NAMESPACE_SEGMENT_NAMING = error("SCH1001")
    val TYPE_NAMING = error("SCH1002")
    val FIELD_NAMING = error("SCH1003")
    val DUPLICATE_TYPE = error("SCH1004")
    val DUPLICATE_FIELD = error("SCH1005")
    val UNKNOWN_TYPE = error("SCH1006")
    val UNSUPPORTED_CONSTRUCT = error("SCH1008")
    val AMBIGUOUS_TYPE = error("SCH1009")
    val BUILTIN_SHADOWED = warning("SCH1010")
    val UNKNOWN_IMPORT = error("SCH1011")
    val UNUSED_IMPORT = warning("SCH1012")
    val MIXED_ORDINALS = error("SCH1013")
    val IMPLICIT_ORDINAL_STRICT = error("SCH1014")
    val DUPLICATE_ORDINAL = error("SCH1019")
    val RESERVED_CONFLICT = error("SCH1020")
    val NULLABLE_MAP_KEY = error("SCH1021")
    val MAP_KEY_TYPE = error("SCH1022")
    val ALIAS_CYCLE = error("SCH1023")
    val DOUBLE_NULLABLE = error("SCH1024")
    val DUPLICATE_UNION_MEMBER = error("SCH1025")
    val UNION_SELF_MEMBER = error("SCH1026")
    val UNION_MEMBER_KIND = error("SCH1027")
    val ENUM_VALUE_NAMING = error("SCH1028")
    val DUPLICATE_ENUM_VALUE = error("SCH1029")
    val EMPTY_ENUM = error("SCH1030")
    val GENERIC_ARITY = error("SCH1031")
    val NOT_GENERIC = error("SCH1032")
    val NESTED_TYPE_NOT_FOUND = error("SCH1033")
    val RESERVED_RANGE = error("SCH1034")
    val INVALID_ORDINAL = error("SCH1035")
    val UNKNOWN_ANNOTATION_TARGET = error("SCH1015")
    val UNKNOWN_ANNOTATION_KEY = error("SCH1016")
    val ANNOTATION_ELEMENT = error("SCH1017")
    val ANNOTATION_VALUE = error("SCH1018")
    val DUPLICATE_ANNOTATION = error("SCH1036")
    val UNKNOWN_REFINEMENT = error("SCH1037")
    val INVALID_REFINEMENT = error("SCH1038")
    val REFINEMENT_NOT_ALLOWED = error("SCH1039")
    val MISSING_REFINEMENT = error("SCH1040")
    val DUPLICATE_REFINEMENT = error("SCH1041")
    val DEFAULT_TYPE = error("SCH1042")
    val DEFAULT_VIOLATES_REFINEMENT = error("SCH1043")
    val NULL_DEFAULT = error("SCH1044")

    val all: List<DiagnosticCode> =
        listOf(
            NAMESPACE_SEGMENT_NAMING,
            TYPE_NAMING,
            FIELD_NAMING,
            DUPLICATE_TYPE,
            DUPLICATE_FIELD,
            UNKNOWN_TYPE,
            UNSUPPORTED_CONSTRUCT,
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
            UNKNOWN_REFINEMENT,
            INVALID_REFINEMENT,
            REFINEMENT_NOT_ALLOWED,
            MISSING_REFINEMENT,
            DUPLICATE_REFINEMENT,
            DEFAULT_TYPE,
            DEFAULT_VIOLATES_REFINEMENT,
            NULL_DEFAULT,
        )
}
