package io.schemata.lang

import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import kotlin.test.Test
import kotlin.test.assertEquals
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class GrammarSmokeTest {
    @Test
    fun `parses a namespace declaration`() {
        val lexer = SchemataLexer(CharStreams.fromString("namespace shop.orders"))
        val parser = SchemataParser(CommonTokenStream(lexer))
        val file = parser.file()
        assertEquals(0, parser.numberOfSyntaxErrors)
        assertEquals("shop.orders", file.namespaceDecl().qualifiedName().text)
    }
}
