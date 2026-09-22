package io.schemata.target.proto

import io.schemata.target.TargetModel

/** Every `.proto` file the compilation produces, one per namespace, in namespace order. */
data class ProtoModel(val files: List<ProtoFile>) : TargetModel

/**
 * One `.proto` file, legal by construction: names are final, references are spelled as proto reads
 * them, imports are sorted and unique. The renderer prints this without making decisions.
 */
data class ProtoFile(
    val path: String,
    val packageName: String,
    val imports: List<String>,
    val declarations: List<ProtoDecl>,
)

sealed interface ProtoDecl {
    val name: String
    val doc: String?
    val deprecated: Boolean
}

data class ProtoMessage(
    override val name: String,
    override val doc: String?,
    val fields: List<ProtoField>,
    val oneofs: List<ProtoOneof>,
    val nested: List<ProtoDecl>,
    val reserved: ProtoReserved,
    override val deprecated: Boolean = false,
) : ProtoDecl

data class ProtoEnum(
    override val name: String,
    override val doc: String?,
    val values: List<ProtoEnumValue>,
    val reserved: ProtoReserved,
    override val deprecated: Boolean = false,
) : ProtoDecl

/** [notes] is what the mapping lost, printed as a trailing `// schemata: …` comment. */
data class ProtoField(
    val number: Int,
    val name: String,
    val type: ProtoType,
    val label: Label = Label.NONE,
    val doc: String? = null,
    val notes: List<String> = emptyList(),
    val deprecated: Boolean = false,
)

enum class Label {
    NONE,
    OPTIONAL,
    REPEATED,
}

data class ProtoOneof(val name: String, val doc: String?, val fields: List<ProtoField>)

sealed interface ProtoType {
    data class Scalar(val keyword: String) : ProtoType

    /** A message or enum, spelled exactly as proto resolves it from where it is used. */
    data class Named(val reference: String) : ProtoType

    data class MapOf(val key: Scalar, val value: ProtoType) : ProtoType
}

data class ProtoEnumValue(
    val name: String,
    val number: Int,
    val doc: String? = null,
    val deprecated: Boolean = false,
)

data class ProtoReserved(val numbers: List<IntRange>, val names: List<String>) {
    val isEmpty: Boolean
        get() = numbers.isEmpty() && names.isEmpty()

    companion object {
        val NONE = ProtoReserved(emptyList(), emptyList())
    }
}
