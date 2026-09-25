package io.schemata.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A corpus case without an expected tree would be skipped by every conformance runner. */
class CorpusTest {
    private val corpus = File("src/test/resources/corpus")

    @Test
    fun `every corpus case has an expected proto or sql tree`() {
        val cases = corpus.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        val orphans =
            cases
                .filterNot {
                    File(it, "expected/proto").isDirectory || File(it, "expected/sql").isDirectory
                }
                .map { it.name }
        assertEquals(emptyList(), orphans)
    }
}
