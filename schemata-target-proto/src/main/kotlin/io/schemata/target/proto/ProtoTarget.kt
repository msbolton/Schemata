package io.schemata.target.proto

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

/** The Protobuf target, writing its files in [layout]. */
class ProtoTarget(val layout: ProtoLayout = ProtoLayout.SCHEMA) : Target<ProtoModel> {
    override val name = "proto"
    override val annotationSpecs = ProtoAnnotations.specs
    override val codes = ProtoCodes.all

    override fun lower(schema: Schema): Lowered<ProtoModel> = ProtoLowering.lower(schema, layout)

    override fun render(model: ProtoModel): List<OutputFile> = ProtoRenderer.render(model)

    /**
     * The schema layout: what the registry, the importer round trip and tests use as `ProtoTarget`.
     */
    companion object : Target<ProtoModel> by ProtoTarget()
}
