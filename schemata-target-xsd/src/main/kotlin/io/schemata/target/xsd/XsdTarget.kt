package io.schemata.target.xsd

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object XsdTarget : Target<XsdModel> {
    override val name = "xsd"
    override val annotationSpecs = XsdAnnotations.specs
    override val codes = XsdCodes.all

    override fun lower(schema: Schema): Lowered<XsdModel> = XsdLowering.lower(schema)

    override fun render(model: XsdModel): List<OutputFile> = XsdRenderer.render(model)
}
