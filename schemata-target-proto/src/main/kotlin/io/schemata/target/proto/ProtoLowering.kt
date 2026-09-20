package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.target.Lowered

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
            messages = namespace.records.map { lower(it, diagnostics) },
        )

    private fun lower(record: RecordType, diagnostics: MutableList<Diagnostic>): ProtoMessage =
        ProtoMessage(record.name, record.fields.map { field -> lower(record, field, diagnostics) })

    private fun lower(
        record: RecordType,
        field: Field,
        diagnostics: MutableList<Diagnostic>,
    ): ProtoField {
        val (scalar, loweredFrom) =
            when (val type = field.type) {
                is Builtin ->
                    when (type) {
                        Builtin.BOOL -> ProtoScalar.BOOL to null
                        Builtin.INT32 -> ProtoScalar.INT32 to null
                        Builtin.STRING -> ProtoScalar.STRING to null
                        Builtin.UUID -> {
                            diagnostics +=
                                Diagnostic(
                                    ProtoCodes.LOSSY_UUID,
                                    "field '${record.name}.${field.name}': uuid has no Protobuf representation; lowered to string",
                                    field.span,
                                )
                            ProtoScalar.STRING to "uuid"
                        }
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
}
