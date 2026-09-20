package io.schemata.lang

import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.internal.CollectingErrorListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class GrammarTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }
        """
            .trimIndent()

    private fun parse(source: String): Pair<SchemataParser.FileContext, List<Diagnostic>> {
        val listener = CollectingErrorListener("test.schemata")
        val lexer =
            SchemataLexer(CharStreams.fromString(source)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val parser =
            SchemataParser(CommonTokenStream(lexer)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        return parser.file() to listener.diagnostics
    }

    @Test
    fun `accepts the fixture with no diagnostics`() {
        val (tree, diagnostics) = parse(fixture)
        assertEquals(emptyList(), diagnostics)
        assertEquals("shop.orders", tree.namespaceDecl().qualifiedName().text)
        val record = tree.declaration().single().recordDecl()
        assertEquals("User", record.IDENT().text)
        assertEquals(listOf("id", "email", "name", "age"), record.field().map { it.IDENT().text })
    }

    @Test
    fun `ignores line and block comments`() {
        val (tree, diagnostics) = parse("namespace a // trailing\n/* block */ record R { x: bool }")
        assertEquals(emptyList(), diagnostics)
        assertEquals(1, tree.declaration().size)
    }

    @Test
    fun `reports a missing colon with its file and position`() {
        val (_, diagnostics) = parse("namespace a\nrecord User { id uuid }")
        val d = diagnostics.single()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(Category.SYNTAX, d.category)
        assertEquals("test.schemata", d.span.file)
        assertEquals(2, d.span.startLine)
        assertEquals(18, d.span.startColumn)
    }

    @Test
    fun `requires a namespace declaration`() {
        val (_, diagnostics) = parse("record User { id: uuid }")
        assertTrue(diagnostics.hasErrors)
        assertEquals(1, diagnostics.single().span.startLine)
    }

    @Test
    fun `rejects a keyword used as a record name`() {
        val (_, diagnostics) = parse("namespace a\nrecord record { x: bool }")
        assertTrue(diagnostics.hasErrors)
    }
}
