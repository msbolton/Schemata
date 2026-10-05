package io.schemata.target.openapi

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object OpenApiTarget : Target<OpenApiModel> {
    override val name = "openapi"
    override val annotationSpecs = OpenApiAnnotations.specs
    override val codes = OpenApiCodes.all

    override fun lower(schema: Schema): Lowered<OpenApiModel> = OpenApiLowering.lower(schema)

    override fun render(model: OpenApiModel): List<OutputFile> = OpenApiRenderer.render(model)
}
