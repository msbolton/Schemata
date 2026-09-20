package io.schemata.target.proto

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object ProtoTarget : Target<ProtoFile> {
    override val name = "proto"

    override fun lower(schema: Schema): Lowered<ProtoFile> = ProtoLowering.lower(schema)

    override fun render(model: ProtoFile): List<OutputFile> = ProtoRenderer.render(model)
}
