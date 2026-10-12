package io.schemata.target.proto

/** How the Protobuf target lays out files: one per schema or reference cycle, or one per type. */
enum class ProtoLayout(val flag: String) {
    SCHEMA("schema"),
    TYPE("type");

    companion object {
        val flags: List<String>
            get() = entries.map { it.flag }

        fun of(flag: String): ProtoLayout? = entries.firstOrNull { it.flag == flag }
    }
}
