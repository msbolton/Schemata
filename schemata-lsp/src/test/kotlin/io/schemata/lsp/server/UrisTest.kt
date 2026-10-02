package io.schemata.lsp.server

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrisTest {
    @Test
    fun `a file uri with an encoded space becomes the plain path`() {
        val expected = Paths.get("/tmp/my shop/a.schemata").toAbsolutePath().normalize().toString()
        assertEquals(expected, Uris.toPath(Paths.get("/tmp/my shop/a.schemata").toUri().toString()))
    }

    @Test
    fun `a path round-trips through its uri`() {
        val path = Paths.get("/tmp/shop/ü/a.schemata").toAbsolutePath().normalize().toString()
        assertEquals(path, Uris.toPath(Uris.toUri(path)))
    }

    @Test
    fun `a uri that is not a file, or not a uri at all, is no path`() {
        assertNull(Uris.toPath("untitled:Untitled-1"))
        assertNull(Uris.toPath("git:/repo/a.schemata?ref=HEAD"))
        assertNull(Uris.toPath("not a uri"))
        assertNull(Uris.toPath(""))
    }
}
