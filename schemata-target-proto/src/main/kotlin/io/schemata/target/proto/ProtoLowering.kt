package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered

/**
 * Lowers flat records over `bool`, `int32`, `string`, and `uuid`. Every other IR shape is reported
 * at its span with the ticket that will lower it; the `when`s are exhaustive so a new IR shape
 * fails to compile here rather than being guessed at.
 */
object ProtoLowering {
    fun lower(schema: Schema): Lowered<ProtoModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val files = schema.namespaces.map { lower(it, diagnostics) }
        return Lowered(ProtoModel(files), diagnostics)
    }

    private fun lower(namespace: Namespace, diagnostics: MutableList<Diagnostic>): ProtoFile {
        if (!namespace.annotations.isEmpty) {
            unsupported(
                "annotations",
                "SCH-23",
                namespace.span,
                diagnostics,
                code = ProtoCodes.UNSUPPORTED_VALUE,
            )
        }
        return ProtoFile(
            path = namespace.name.replace('.', '/') + ".proto",
            packageName = namespace.name,
            imports = emptyList(),
            declarations = namespace.declarations.mapNotNull { lower(it, diagnostics) },
        )
    }

    private fun lower(decl: TypeDecl, diagnostics: MutableList<Diagnostic>): ProtoMessage? =
        when (decl) {
            is RecordType -> {
                if (decl.reserved != Reserved.NONE) {
                    unsupported("reserved ordinals and names", "SCH-24", decl.nameSpan, diagnostics)
                }
                if (!decl.annotations.isEmpty) {
                    unsupported(
                        "annotations",
                        "SCH-23",
                        decl.nameSpan,
                        diagnostics,
                        code = ProtoCodes.UNSUPPORTED_VALUE,
                    )
                }
                val fields = decl.fields.mapNotNull { lower(decl, it, diagnostics) }
                decl.nested.forEach {
                    unsupported("nested declarations", "SCH-24", it.nameSpan, diagnostics)
                }
                ProtoMessage(decl.name, null, fields, emptyList(), emptyList(), ProtoReserved.NONE)
            }
            is EnumType -> {
                unsupported("enums", "SCH-24", decl.nameSpan, diagnostics)
                null
            }
            is UnionType -> {
                unsupported("unions", "SCH-24", decl.nameSpan, diagnostics)
                null
            }
        }

    private fun lower(
        record: RecordType,
        field: Field,
        diagnostics: MutableList<Diagnostic>,
    ): ProtoField? {
        val where = "field '${record.name}.${field.name}'"
        if (field.default != null) {
            diagnostics +=
                Diagnostic(
                    ProtoCodes.UNSUPPORTED_VALUE,
                    "$where: target 'proto' cannot lower field defaults yet (SCH-25)",
                    field.span,
                )
            return null
        }
        if (field.type.hasRefinements()) {
            diagnostics +=
                Diagnostic(
                    ProtoCodes.UNSUPPORTED_VALUE,
                    "$where: target 'proto' cannot lower type refinements yet (SCH-23)",
                    field.span,
                )
            return null
        }
        if (!field.annotations.isEmpty) {
            unsupported(
                "annotations",
                "SCH-23",
                field.span,
                diagnostics,
                where,
                ProtoCodes.UNSUPPORTED_VALUE,
            )
            return null
        }
        val (scalar, loweredFrom) =
            when (val type = field.type) {
                is Scalar ->
                    when (type.builtin) {
                        Builtin.BOOL -> ProtoType.Scalar("bool") to null
                        Builtin.INT32 -> ProtoType.Scalar("int32") to null
                        Builtin.STRING -> ProtoType.Scalar("string") to null
                        Builtin.UUID -> {
                            diagnostics +=
                                Diagnostic(
                                    ProtoCodes.LOSSY,
                                    "$where: uuid has no Protobuf representation; lowered to string",
                                    field.span,
                                )
                            ProtoType.Scalar("string") to "uuid"
                        }
                        Builtin.INT64,
                        Builtin.FLOAT32,
                        Builtin.FLOAT64,
                        Builtin.DECIMAL,
                        Builtin.BYTES,
                        Builtin.DATE,
                        Builtin.TIME,
                        Builtin.INSTANT,
                        Builtin.DURATION -> {
                            unsupported(
                                type.builtin.typeName,
                                "SCH-23",
                                field.span,
                                diagnostics,
                                where,
                            )
                            return null
                        }
                    }
                is Ref -> {
                    unsupported("record references", "SCH-24", field.span, diagnostics, where)
                    return null
                }
                is ListOf -> {
                    unsupported("lists", "SCH-24", field.span, diagnostics, where)
                    return null
                }
                is MapOf -> {
                    unsupported("maps", "SCH-24", field.span, diagnostics, where)
                    return null
                }
            }
        return ProtoField(
            number = field.ordinal,
            name = field.name,
            type = scalar,
            label = if (field.nullable) Label.OPTIONAL else Label.NONE,
            notes = listOfNotNull(loweredFrom),
        )
    }

    private fun Type.hasRefinements(): Boolean =
        when (this) {
            is Scalar -> refinements.hasBounds
            is ListOf -> refinements.hasBounds || element.hasRefinements()
            is MapOf -> refinements.hasBounds || key.hasRefinements() || value.hasRefinements()
            is Ref -> false
        }

    private fun unsupported(
        what: String,
        ticket: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        where: String? = null,
        code: DiagnosticCode = ProtoCodes.UNSUPPORTED_SHAPE,
    ) {
        val prefix = where?.let { "$it: " } ?: ""
        diagnostics +=
            Diagnostic(code, "${prefix}target 'proto' cannot lower $what yet ($ticket)", span)
    }
}
