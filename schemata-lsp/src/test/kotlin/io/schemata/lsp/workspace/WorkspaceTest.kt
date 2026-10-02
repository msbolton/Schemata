package io.schemata.lsp.workspace

import io.schemata.core.annotations.AnnotationRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class WorkspaceTest {
    @TempDir lateinit var dir: Path

    private val customers = "namespace shop.customers\nrecord Customer { #1 id: uuid }\n"
    private val orders =
        "namespace shop.orders\nimport shop.customers\nrecord Order { #1 who: Customer }\n"

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
        val analysis = ws.analysis(ws.change(o, "namespace shop.orders\nrecord Order {"))
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
        val analysis = ws.analysis(ws.change(c, "namespace shop.customers\nrecord Customer {"))
        assertEquals(emptyList(), codes(analysis, o))
        assertTrue(codes(analysis, c).isNotEmpty())
    }

    @Test
    fun `a file that has never parsed contributes nothing and its siblings say so`() {
        val c = file("shop/customers.schemata", "namespace shop.customers\nrecord Customer {")
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        val analysis = ws.analysis(ws.open(o, orders))
        assertTrue(codes(analysis, c).isNotEmpty())
        assertTrue("SCH1011" in codes(analysis, o), codes(analysis, o).toString())
    }

    @Test
    fun `fixing the text clears the diagnostics`() {
        val o = file("solo/a.schemata", "namespace a\nrecord R { #1 x: Missing }\n")
        val ws = workspace()
        val bad = ws.analysis(ws.open(o, "namespace a\nrecord R { #1 x: Missing }\n"))
        assertEquals(1, codes(bad, o).size)
        val good = ws.analysis(ws.change(o, "namespace a\nrecord R { #1 x: int32 }\n"))
        assertEquals(emptyList(), codes(good, o))
    }

    @Test
    fun `files in another directory are a different set`() {
        val a = file("one/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
        file("two/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
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
        val a = file("s/a.schemata", "namespace a\nrecord R { x: int32 }\n")
        val ws = workspace()
        ws.configure(emptyList(), strict = true)
        val analysis = ws.analysis(ws.open(a, "namespace a\nrecord R { x: int32 }\n"))
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
        val o = file("solo/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
        val ws = workspace()
        ws.open(o, "namespace a\nrecord R { #1 x: Missing }\n")
        val analysis = ws.analysis(ws.close(o))
        assertEquals(emptyList(), codes(analysis, o))
    }

    @Test
    fun `a closed sibling edited on disk with no watcher event is read again`() {
        val c = file("shop/customers.schemata", customers)
        val o = file("shop/orders.schemata", orders)
        val ws = workspace()
        assertEquals(emptyList(), codes(ws.analysis(ws.open(o, orders)), o))
        file("shop/customers.schemata", "namespace shop.customers\nrecord Client { #1 id: uuid }\n")
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
        file("shop/customers.schemata", "namespace shop.customers\nrecord Client { #1 id: uuid }\n")
        ws.refresh(key)
        assertTrue("SCH1006" in codes(ws.analysis(key), o))
    }

    @Test
    fun `a directory that cannot be read is skipped and the rest of the root analyses`() {
        val a = file("model/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
        file("model/locked/b.schemata", "namespace b\nrecord S { #1 x: int32 }\n")
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
        val a = file("model/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
        file("model/.cache/a.schemata", "namespace a\nrecord R { #1 x: int32 }\n")
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
}
