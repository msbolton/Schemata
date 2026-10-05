package io.schemata.importer.proto

/**
 * An option as written: [name] keeps parentheses on extension names; [value] is the constant's
 * text.
 */
data class ProtoOption(val name: String, val value: String)

/**
 * One `reserved` statement: inclusive numeric ranges and names. `max` reads as
 * [ProtoReader.MAX_FIELD_NUMBER] in a message and as the largest 32-bit integer in an enum.
 */
data class ProtoReserved(val ranges: List<Pair<Int, Int>>, val names: List<String>, val pos: Pos)

enum class Label {
    NONE,
    OPTIONAL,
    REQUIRED,
    REPEATED,
}

/**
 * A field. [type] is the type as written (`int32`, `.google.protobuf.Timestamp`, `Order.Line`); a
 * map field has [type] `map` with [mapKey] and [mapValue] set. [note] is the text after `schemata:`
 * in a trailing `//` comment that starts with it; [trailing] is any other trailing comment; [doc]
 * is the leading comment block with its comment markers removed. [oneof] names the enclosing oneof.
 */
data class ProtoField(
    val label: Label,
    val type: String,
    val mapKey: String?,
    val mapValue: String?,
    val name: String,
    val number: Int,
    val options: List<ProtoOption>,
    val doc: String?,
    val note: String?,
    val trailing: String?,
    val oneof: String?,
    val pos: Pos,
)

data class ProtoEnumValue(
    val name: String,
    val number: Int,
    val options: List<ProtoOption>,
    val doc: String?,
    val trailing: String?,
    val pos: Pos,
)

data class ProtoEnum(
    val name: String,
    val values: List<ProtoEnumValue>,
    val reserved: List<ProtoReserved>,
    val options: List<ProtoOption>,
    val doc: String?,
    val pos: Pos,
)

/**
 * A message. [dropped] records constructs that are read past but not kept (`extensions`, `extend`,
 * `group`), each with its position, so the caller can report them.
 */
data class ProtoMessage(
    val name: String,
    val fields: List<ProtoField>,
    val oneofs: List<String>,
    val messages: List<ProtoMessage>,
    val enums: List<ProtoEnum>,
    val reserved: List<ProtoReserved>,
    val options: List<ProtoOption>,
    val doc: String?,
    val pos: Pos,
    val dropped: List<Pair<String, Pos>>,
)

data class ProtoImport(val path: String, val public: Boolean, val weak: Boolean, val pos: Pos)

/** An rpc's request or response: the type as written, and whether it is marked `stream`. */
data class ProtoRpcType(val name: String, val stream: Boolean)

/**
 * An rpc. [options] are the `option` statements in its body; [doc] is the leading comment block
 * with its comment markers removed; [note] is the text after `schemata:` in a `//` comment trailing
 * the rpc's `;` or its body's `{`.
 */
data class ProtoRpc(
    val name: String,
    val request: ProtoRpcType,
    val response: ProtoRpcType,
    val options: List<ProtoOption>,
    val doc: String?,
    val note: String?,
    val pos: Pos,
)

/**
 * A service. [options] are its own `option` statements; [doc] is its leading comment block.
 * [reservedNotes] holds the `//` comments in its body that stand on their own line and start with
 * `schemata:` (such as `// schemata: reserved #6`), each as the trimmed text after `schemata:`.
 */
data class ProtoService(
    val name: String,
    val rpcs: List<ProtoRpc>,
    val options: List<ProtoOption>,
    val doc: String?,
    val reservedNotes: List<String>,
    val pos: Pos,
)

/**
 * A whole file. [syntax] is `proto2`, `proto3`, or `editions` (then [edition] is set); a file with
 * no syntax line is `proto2`. [dropped] records top-level `extend` blocks.
 */
data class ProtoFile(
    val path: String,
    val syntax: String,
    val edition: String?,
    val pkg: String?,
    val imports: List<ProtoImport>,
    val options: List<ProtoOption>,
    val messages: List<ProtoMessage>,
    val enums: List<ProtoEnum>,
    val services: List<ProtoService>,
    val dropped: List<Pair<String, Pos>>,
)
