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
        if (file.imports.isNotEmpty()) {
            appendLine()
            file.imports.forEach { appendLine("import \"$it\";") }
        }
        file.declarations.forEach {
            appendLine()
            append(block(it, indent = ""))
        }
    }

    /** A message or enum block; sections inside are separated by one blank line. */
    private fun block(decl: ProtoDecl, indent: String): String {
        val inner = "$indent  "
        val keyword =
            when (decl) {
                is ProtoMessage -> "message"
                is ProtoEnum -> "enum"
            }
        val sections =
            when (decl) {
                is ProtoMessage -> messageSections(decl, inner)
                is ProtoEnum -> enumSections(decl, inner)
            }
        return buildString {
            doc(decl.doc, indent)
            if (sections.isEmpty()) {
                appendLine("$indent$keyword ${decl.name} {}")
                return@buildString
            }
            appendLine("$indent$keyword ${decl.name} {")
            sections.forEachIndexed { i, section ->
                if (i > 0) appendLine()
                append(section)
            }
            appendLine("$indent}")
        }
    }

    private fun messageSections(message: ProtoMessage, indent: String): List<String> {
        val sections = mutableListOf<String>()
        if (message.deprecated) sections += "${indent}option deprecated = true;\n"
        val body = buildString {
            message.fields.forEach { append(field(it, indent)) }
            message.oneofs.forEach { oneof ->
                doc(oneof.doc, indent)
                appendLine("${indent}oneof ${oneof.name} {")
                oneof.fields.forEach { append(field(it, "$indent  ")) }
                appendLine("$indent}")
            }
        }
        if (body.isNotEmpty()) sections += body
        message.nested.forEach { sections += block(it, indent) }
        reserved(message.reserved, indent)?.let { sections += it }
        return sections
    }

    private fun enumSections(enum: ProtoEnum, indent: String): List<String> {
        val sections = mutableListOf<String>()
        if (enum.deprecated) sections += "${indent}option deprecated = true;\n"
        val values = buildString {
            enum.values.forEach { value ->
                doc(value.doc, indent)
                append("$indent${value.name} = ${value.number}")
                if (value.deprecated) append(" [deprecated = true]")
                appendLine(";")
            }
        }
        if (values.isNotEmpty()) sections += values
        reserved(enum.reserved, indent)?.let { sections += it }
        return sections
    }

    private fun field(field: ProtoField, indent: String): String = buildString {
        doc(field.doc, indent)
        append(indent)
        when (field.label) {
            Label.OPTIONAL -> append("optional ")
            Label.REPEATED -> append("repeated ")
            Label.NONE -> {}
        }
        append("${type(field.type)} ${field.name} = ${field.number}")
        if (field.deprecated) append(" [deprecated = true]")
        append(";")
        if (field.notes.isNotEmpty()) append("  // schemata: ${field.notes.joinToString("; ")}")
        appendLine()
    }

    private fun type(type: ProtoType): String =
        when (type) {
            is ProtoType.Scalar -> type.keyword
            is ProtoType.Named -> type.reference
            is ProtoType.MapOf -> "map<${type.key.keyword}, ${type(type.value)}>"
        }

    private fun reserved(reserved: ProtoReserved, indent: String): String? {
        if (reserved.isEmpty) return null
        return buildString {
            if (reserved.numbers.isNotEmpty()) {
                val ranges =
                    reserved.numbers.joinToString(", ") {
                        if (it.first == it.last) "${it.first}" else "${it.first} to ${it.last}"
                    }
                appendLine("${indent}reserved $ranges;")
            }
            if (reserved.names.isNotEmpty()) {
                appendLine("${indent}reserved ${reserved.names.joinToString(", ") { "\"$it\"" }};")
            }
        }
    }

    private fun StringBuilder.doc(doc: String?, indent: String) {
        // An empty doc line prints as a bare `//`: no trailing space survives.
        doc?.lines()?.forEach { appendLine("$indent// $it".trimEnd()) }
    }
}
