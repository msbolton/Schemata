package io.schemata.target.jsonschema

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object JsonSchemaTarget : Target<JsonSchemaModel> {
    override val name = "jsonschema"
    override val annotationSpecs = JsonSchemaAnnotations.specs
    override val codes = JsonSchemaCodes.all

    override fun lower(schema: Schema): Lowered<JsonSchemaModel> = JsonSchemaLowering.lower(schema)

    override fun render(model: JsonSchemaModel): List<OutputFile> = JsonSchemaRenderer.render(model)
}
