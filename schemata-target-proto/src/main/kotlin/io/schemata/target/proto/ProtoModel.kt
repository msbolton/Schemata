package io.schemata.target.proto

import io.schemata.target.TargetModel

/** One `.proto` file, legal by construction. The renderer prints this without making decisions. */
data class ProtoFile(val path: String, val packageName: String, val messages: List<ProtoMessage>) :
    TargetModel

data class ProtoMessage(val name: String, val fields: List<ProtoField>)

/**
 * [loweredFrom] names the Schemata type when the mapping lost information, for an inline comment.
 */
data class ProtoField(
    val number: Int,
    val name: String,
    val type: ProtoScalar,
    val optional: Boolean,
    val loweredFrom: String?,
)

enum class ProtoScalar(val keyword: String) {
    BOOL("bool"),
    INT32("int32"),
    STRING("string"),
}
