package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportCodesTest {
    @Test
    fun `a derived name is described for files and directories alike`() {
        assertEquals(
            "a name was derived from a file or directory name or changed on import",
            ImportCodes.RENAMED.description,
        )
    }

    @Test
    fun `the rename help names a route that works for a directory import`() {
        val help = ImportCodes.helpFor(ImportCodes.RENAMED)
        assertTrue("on its own with --namespace" in help, help)
    }
}
