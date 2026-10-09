package io.schemata.lsp.workspace

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class WorkspaceTest {
    @TempDir lateinit var dir: Path

    private val customers = "schema shop.customers\nmodel Customer { #1 id uuid }\n"
    private val orders =
        "schema shop.orders\nimport shop.customers\nmodel Order { #1 who Customer }\n"

    private fun file(relative: String, text: String): String {
        val path = dir.resolve(relative)
        path.parent.createDirectories()
        path.writeText(text)
        return path.toAbsolutePath().normalize().toString()
    }

    private fun workspace() = Workspace(AnnotationRegistry.CORE)

    private fun codes(analysis: SetAnalysis, path: String) =
        analysis.diagnostics.getValue(path).map { it.code.id }

    @Test
    fun `opening one file analyses its directory's files from disk`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val analysis = ws.analysis(ws.open(o, orders))
        assertEquals(setOf(c, o), analysis.diagnostics.keys)
        assertEquals(emptyList(), codes(analysis, o))
        assertEquals(emptyList(), codes(analysis, c))
    }

    @Test
    fun `an edit that breaks the syntax shows only syntax errors on that file`() {
        file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        ws.open(o, orders)
        val analysis = ws.analysis(ws.change(o, "schema shop.orders\nmodel Order {"))
        assertTrue(ws.document(o)!!.broken)
        assertTrue(codes(analysis, o).all { it.startsWith("SCH0") }, codes(analysis, o).toString())
        assertTrue(codes(analysis, o).isNotEmpty())
    }

    @Test
    fun `a broken sibling keeps standing in with its last clean parse`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        ws.open(o, orders)
        ws.open(c, customers)
        val analysis = ws.analysis(ws.change(c, "schema shop.customers\nmodel Customer {"))
        assertEquals(emptyList(), codes(analysis, o))
        assertTrue(codes(analysis, c).isNotEmpty())
    }

    @Test
    fun `a file that has never parsed contributes nothing and its siblings say so`() {
        val c = file("shop/customers.schemata", "schema shop.customers\nmodel Customer {")
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val analysis = ws.analysis(ws.open(o, orders))
        assertTrue(codes(analysis, c).isNotEmpty())
        assertTrue("SCH1011" in codes(analysis, o), codes(analysis, o).toString())
    }

    @Test
    fun `fixing the text clears the diagnostics`() {
        val o = file("solo/a.schemata", "schema a\nmodel R { #1 x Missing }\n")
        val ws = workspace()
        val bad = ws.analysis(ws.open(o, "schema a\nmodel R { #1 x Missing }\n"))
        assertEquals(1, codes(bad, o).size)
        val good = ws.analysis(ws.change(o, "schema a\nmodel R { #1 x int32 }\n"))
        assertEquals(emptyList(), codes(good, o))
    }

    @Test
    fun `files in another directory are a different set`() {
        val a = file("one/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        file("two/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val ws = workspace()
        val analysis = ws.analysis(ws.open(a, Files.readString(Path.of(a))))
        assertEquals(setOf(a), analysis.diagnostics.keys)
        assertEquals(emptyList(), codes(analysis, a))
    }

    @Test
    fun `a configured root gathers its subtree into one set`() {
        val c = file("model/customers/c.schemata", customers)
        val o = file("model/orders/o.schemata", orders)
        val ws = workspace()
        ws.configure(listOf(dir.resolve("model")), strict = false)
        val analysis = ws.analysis(ws.open(o, orders))
        assertEquals(setOf(c, o), analysis.diagnostics.keys)
        assertEquals(emptyList(), codes(analysis, o))
    }

    @Test
    fun `strict turns implicit ordinals into errors`() {
        val a = file("s/a.schemata", "schema a\nmodel R { x int32 }\n")
        val ws = workspace()
        ws.configure(emptyList(), strict = true)
        val analysis = ws.analysis(ws.open(a, "schema a\nmodel R { x int32 }\n"))
        assertTrue(codes(analysis, a).isNotEmpty())
    }

    @Test
    fun `a deleted file is reported gone exactly once`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val key = ws.open(o, orders)
        ws.analysis(key)
        Path.of(c).deleteExisting()
        val after = ws.analysis(ws.diskChanged(c))
        assertEquals(setOf(c), after.gone)
        assertFalse(c in after.diagnostics.keys)
        assertEquals(emptySet(), ws.analysis(ws.change(o, orders)).gone)
    }

    @Test
    fun `a closed file falls back to what is on disk`() {
        val o = file("solo/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val ws = workspace()
        ws.open(o, "schema a\nmodel R { #1 x Missing }\n")
        val analysis = ws.analysis(ws.close(o))
        assertEquals(emptyList(), codes(analysis, o))
    }

    @Test
    fun `a closed sibling edited on disk with no watcher event is read again`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        assertEquals(emptyList(), codes(ws.analysis(ws.open(o, orders)), o))
        file("shop/customers.schemata", "schema shop.customers\nmodel Client { #1 id uuid }\n")
        val analysis = ws.analysis(ws.change(o, orders))
        assertEquals(listOf("SCH1006", "SCH1012"), codes(analysis, o).sorted())
        assertEquals(emptyList(), codes(analysis, c))
    }

    @Test
    fun `refresh makes the next analysis look at the disk again`() {
        file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val key = ws.open(o, orders)
        assertEquals(emptyList(), codes(ws.analysis(key), o))
        file("shop/customers.schemata", "schema shop.customers\nmodel Client { #1 id uuid }\n")
        ws.refresh(key)
        assertTrue("SCH1006" in codes(ws.analysis(key), o))
    }

    @Test
    fun `a directory that cannot be read is skipped and the rest of the root analyses`() {
        val a = file("model/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        file("model/locked/b.schemata", "schema b\nmodel S { #1 x int32 }\n")
        val locked = dir.resolve("model/locked")
        assumeTrue(
            Files.getFileStore(locked).supportsFileAttributeView("posix"),
            "permissions cannot be removed here",
        )
        val permissions = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assumeTrue(!Files.isReadable(locked), "this user reads every directory")
            val ws = workspace()
            ws.configure(listOf(dir.resolve("model")), strict = false)
            val analysis = ws.analysis(ws.open(a, Files.readString(Path.of(a))))
            assertEquals(setOf(a), analysis.diagnostics.keys)
            assertEquals(emptyList(), codes(analysis, a))
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
        }
    }

    @Test
    fun `a hidden directory under a root is not part of the set`() {
        val a = file("model/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        file("model/.cache/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val ws = workspace()
        ws.configure(listOf(dir.resolve("model")), strict = false)
        val analysis = ws.analysis(ws.open(a, Files.readString(Path.of(a))))
        assertEquals(setOf(a), analysis.diagnostics.keys)
        assertEquals(emptyList(), codes(analysis, a))
    }

    @Test
    fun `an empty file is a syntax error and not a crash`() {
        val o = file("solo/a.schemata", "")
        val ws = workspace()
        val analysis = ws.analysis(ws.open(o, ""))
        assertTrue(codes(analysis, o).isNotEmpty())
    }

    @Test
    fun `a deleted file loses its symbols and its diagnostics, and its dependants see it go`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val key = ws.open(o, orders)
        val customer = QualifiedName("shop.customers", listOf("Customer"))
        assertTrue(customer in ws.analysis(key).index.declarations)
        Path.of(c).deleteExisting()
        val after = ws.analysis(ws.diskChanged(c))
        assertFalse(customer in after.index.declarations)
        assertNull(ws.document(c))
        assertNull(after.snapshot(c))
        assertFalse(c in after.diagnostics.keys)
        assertTrue("SCH1006" in codes(after, o), codes(after, o).toString())
    }

    @Test
    fun `a dependant is analysed against the new disk text of a file that changed under it`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val key = ws.open(o, orders)
        assertEquals(emptyList(), codes(ws.analysis(key), o))
        file("shop/customers.schemata", "schema shop.customers\nmodel Client { #1 id uuid }\n")
        val after = ws.analysis(ws.diskChanged(c))
        assertTrue("SCH1006" in codes(after, o), codes(after, o).toString())
    }

    @Test
    fun `a file whose parse throws is reported as a diagnostic and stays a member`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws =
            Workspace(
                AnnotationRegistry.CORE,
                parse = { text, path ->
                    if ("Customer" in text && path == c) error("parser exploded")
                    else Parser.parse(text, path)
                },
            )
        val analysis = ws.analysis(ws.open(o, orders))
        assertEquals(setOf(c, o), analysis.diagnostics.keys)
        val reported = analysis.diagnostics.getValue(c).single()
        assertTrue("parser exploded" in reported.message, reported.message)
        assertTrue(ws.document(c)!!.broken)
        // The file that parses is judged without the one that does not.
        assertTrue("SCH1006" in codes(analysis, o), codes(analysis, o).toString())
    }

    @Test
    fun `an open file whose parse throws is reported and recovers with the next edit`() {
        val o = file("solo/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val ws =
            Workspace(
                AnnotationRegistry.CORE,
                parse = { text, path ->
                    if ("boom" in text) throw IllegalStateException("boom")
                    else Parser.parse(text, path)
                },
            )
        val broken = ws.analysis(ws.open(o, "schema a\nmodel R { #1 boom int32 }\n"))
        assertTrue(ws.document(o)!!.broken)
        assertTrue("boom" in broken.diagnostics.getValue(o).single().message)
        val fixed = ws.analysis(ws.change(o, "schema a\nmodel R { #1 x int32 }\n"))
        assertFalse(ws.document(o)!!.broken)
        assertEquals(emptyList(), codes(fixed, o))
    }

    @Test
    fun `a root given relative to the working directory gathers its subtree`() {
        val c = file("model/customers/c.schemata", customers)
        val o = file("model/orders/o.schemata", orders)
        val relative = Path.of("").toAbsolutePath().relativize(dir.resolve("model"))
        assertFalse(relative.isAbsolute)
        val ws = workspace()
        ws.configure(listOf(relative), strict = false)
        val analysis = ws.analysis(ws.open(o, orders))
        assertEquals(setOf(c, o), analysis.diagnostics.keys)
        assertEquals(emptyList(), codes(analysis, o))
    }

    @Test
    fun `change on a file that was never opened opens it and a later change parses again`() {
        val o = file("solo/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val ws = workspace()
        ws.analysis(ws.change(o, "schema a\nmodel R { #1 x Missing }\n"))
        assertTrue(ws.document(o)!!.open)
        val key = ws.change(o, "schema a\nmodel R { #1 y int32 }\n")
        assertEquals("schema a\nmodel R { #1 y int32 }\n", ws.document(o)!!.snapshot!!.text)
        assertEquals(emptyList(), codes(ws.analysis(key), o))
    }

    @Test
    fun `a file that cannot be read is reported and not dropped`() {
        val a = file("model/a.schemata", "schema a\nmodel R { #1 x int32 }\n")
        val b = file("model/b.schemata", "schema b\nmodel S { #1 x int32 }\n")
        val locked = Path.of(b)
        assumeTrue(
            Files.getFileStore(locked).supportsFileAttributeView("posix"),
            "permissions cannot be removed here",
        )
        val permissions = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assumeTrue(!Files.isReadable(locked), "this user reads every file")
            val ws = workspace()
            val analysis = ws.analysis(ws.open(a, Files.readString(Path.of(a))))
            assertEquals(setOf(a, b), analysis.diagnostics.keys)
            assertEquals(emptyList(), codes(analysis, a))
            assertEquals(1, analysis.diagnostics.getValue(b).size)
            assertTrue(ws.document(b)!!.broken)
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
        }
    }

    @Test
    fun `a closed file with the same size and a newer modification time is read again`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        assertEquals(emptyList(), codes(ws.analysis(ws.open(o, orders)), o))
        val renamed = customers.replace("Customer", "Consumer")
        assertEquals(customers.length, renamed.length)
        val stamp = Files.getLastModifiedTime(Path.of(c))
        file("shop/customers.schemata", renamed)
        Files.setLastModifiedTime(Path.of(c), FileTime.fromMillis(stamp.toMillis() + 5000))
        assertTrue("SCH1006" in codes(ws.analysis(ws.change(o, orders)), o))
    }

    @Test
    fun `the file list is read once between two lookups and again after a change on disk`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        var listed = 0
        val ws =
            Workspace(
                AnnotationRegistry.CORE,
                walk = { directory, recursive ->
                    listed++
                    walkSchemata(directory, recursive)
                },
            )
        val key = ws.open(o, orders)
        ws.analysis(key)
        assertEquals(1, listed)
        // An edit of a file already in the list cannot change what the directory holds.
        val extra = file("shop/extra.schemata", "schema shop.extra\nmodel E { #1 x int32 }\n")
        val edited = ws.analysis(ws.change(o, "$orders\n"))
        assertEquals(1, listed)
        assertFalse(extra in edited.diagnostics.keys)
        assertTrue(extra in ws.analysis(ws.diskChanged(extra)).diagnostics.keys)
        assertEquals(2, listed)
        ws.analysis(ws.close(c))
        assertEquals(3, listed)
        ws.refresh(key)
        ws.analysis(key)
        assertEquals(4, listed)
        ws.analysis(ws.open(file("shop/new.schemata", "schema shop.n\n"), "schema shop.n\n"))
        assertEquals(5, listed)
    }
}
