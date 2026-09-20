package io.schemata.lang.internal

import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.SourceFile

internal class AstBuilder(private val file: String) {
    fun build(ctx: SchemataParser.FileContext): SourceFile =
        throw UnsupportedOperationException("AST builder is replaced in SCH-16 Task 7")
}
