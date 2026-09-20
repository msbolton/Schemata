package io.schemata.target.sql

import io.schemata.core.ir.Schema
import io.schemata.target.Lowered
import io.schemata.target.OutputFile
import io.schemata.target.Target

object SqlTarget : Target<RelationalSchema> {
    override val name = "sql"

    override fun lower(schema: Schema): Lowered<RelationalSchema> = SqlLowering.lower(schema)

    override fun render(model: RelationalSchema): List<OutputFile> = SqlRenderer.render(model)
}
