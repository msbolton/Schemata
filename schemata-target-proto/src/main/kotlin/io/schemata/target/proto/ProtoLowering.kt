package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
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

    private fun lower(namespace: Namespace, diagnostics: MutableList<Diagnostic>): ProtoFile =
        ProtoFile(
            path = namespace.name.replace('.', '/') + ".proto",
            packageName = namespace.name,
            messages = namespace.declarations.mapNotNull { lower(it, diagnostics) },
        )

    private fun lower(decl: TypeDecl, diagnostics: MutableList<Diagnostic>): ProtoMessage? =
        when (decl) {
            is RecordType -> {
                if (decl.reserved != Reserved.NONE) {
                    unsupported("reserved ordinals and names", "SCH-24", decl.nameSpan, diagnostics)
                }
                val fields = decl.fields.mapNotNull { lower(decl, it, diagnostics) }
                decl.nested.forEach {
                    unsupported("nested declarations", "SCH-24", it.nameSpan, diagnostics)
                }
                ProtoMessage(decl.name, fields)
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
        val (scalar, loweredFrom) =
            when (val type = field.type) {
                is Scalar ->
                    when (type.builtin) {
                        Builtin.BOOL -> ProtoScalar.BOOL to null
                        Builtin.INT32 -> ProtoScalar.INT32 to null
                        Builtin.STRING -> ProtoScalar.STRING to null
                        Builtin.UUID -> {
                            diagnostics +=
                                Diagnostic(
                                    ProtoCodes.LOSSY_UUID,
                                    "$where: uuid has no Protobuf representation; lowered to string",
                                    field.span,
                                )
                            ProtoScalar.STRING to "uuid"
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
            optional = field.nullable,
            loweredFrom = loweredFrom,
        )
    }

    private fun Type.hasRefinements(): Boolean =
        when (this) {
            is Scalar -> refinements != Refinements()
            is ListOf -> refinements != Refinements() || element.hasRefinements()
            is MapOf ->
                refinements != Refinements() || key.hasRefinements() || value.hasRefinements()
            is Ref -> false
        }

    private fun unsupported(
        what: String,
        ticket: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        where: String? = null,
    ) {
        val prefix = where?.let { "$it: " } ?: ""
        diagnostics +=
            Diagnostic(
                ProtoCodes.UNSUPPORTED_SHAPE,
                "${prefix}target 'proto' cannot lower $what yet ($ticket)",
                span,
            )
    }
}
