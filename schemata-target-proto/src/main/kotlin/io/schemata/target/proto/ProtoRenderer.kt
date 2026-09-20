package io.schemata.target.proto

import io.schemata.target.OutputFile

/** Prints a [ProtoModel]. No decisions are made here; anything lossy was decided in lowering. */
object ProtoRenderer {
    fun render(model: ProtoModel): List<OutputFile> =
        model.files.map { OutputFile(it.path, text(it)) }

    private fun text(file: ProtoFile): String = buildString {
        appendLine("syntax = \"proto3\";")
        appendLine()
        appendLine("package ${file.packageName};")
        file.messages.forEach { message ->
            appendLine()
            appendLine("message ${message.name} {")
            message.fields.forEach { field ->
                append("  ")
                if (field.optional) append("optional ")
                append("${field.type.keyword} ${field.name} = ${field.number};")
                field.loweredFrom?.let { append("  // schemata: $it") }
                appendLine()
            }
            appendLine("}")
        }
    }
}
