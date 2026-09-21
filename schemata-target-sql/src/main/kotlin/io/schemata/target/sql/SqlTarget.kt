package io.schemata.target.sql

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object SqlTarget : Target<RelationalModel> {
    override val name = "sql"
    override val annotationSpecs = SqlAnnotations.specs

    override fun lower(schema: Schema): Lowered<RelationalModel> = SqlLowering.lower(schema)

    override fun render(model: RelationalModel): List<OutputFile> = SqlRenderer.render(model)
}
