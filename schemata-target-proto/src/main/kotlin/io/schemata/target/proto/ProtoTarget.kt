package io.schemata.target.proto

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object ProtoTarget : Target<ProtoModel> {
    override val name = "proto"
    override val annotationSpecs = ProtoAnnotations.specs

    override fun lower(schema: Schema): Lowered<ProtoModel> = ProtoLowering.lower(schema)

    override fun render(model: ProtoModel): List<OutputFile> = ProtoRenderer.render(model)
}
