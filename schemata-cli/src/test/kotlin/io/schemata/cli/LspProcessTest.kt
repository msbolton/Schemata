package io.schemata.cli

import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** `schemata lsp` from the shaded jar serves a whole editor session over its standard streams. */
class LspProcessTest {
    private val jar = System.getProperty("schemata.fatJar")?.let(::File)
    private val binary = System.getProperty("schemata.nativeBinary")?.let(::File)
    private val examples = File("../examples").canonicalFile

    @Test
    fun `the jar serves a session and exits cleanly`() {
        assumeTrue(jar != null && jar.isFile, "shadow jar not built")
        val java = File(System.getProperty("java.home"), "bin/java").path
        runLspSession(listOf(java, "-jar", jar!!.path), examples)
    }

    @Test
    fun `the jar renames a name in a folder of one file`() {
        assumeTrue(jar != null && jar.isFile, "shadow jar not built")
        val java = File(System.getProperty("java.home"), "bin/java").path
        runOneFileRename(listOf(java, "-jar", jar!!.path), examples)
    }

    @Test
    fun `the native binary renames a name in a folder of one file`() {
        assumeTrue(binary != null && binary.isFile, "no native binary given")
        runOneFileRename(listOf(binary!!.path), examples)
    }
}
