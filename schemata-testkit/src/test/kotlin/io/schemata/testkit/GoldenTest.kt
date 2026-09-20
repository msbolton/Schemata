package io.schemata.testkit

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenTest {
    @Test
    fun `matches an existing golden file`() {
        Golden.assertMatches("example.txt", "hello\n")
    }

    @Test
    fun `fails clearly when the golden file is missing`() {
        val error = assertFailsWith<AssertionError> { Golden.assertMatches("missing.txt", "x") }
        assertTrue(error.message!!.contains("SCHEMATA_GOLDEN_UPDATE=1"), error.message)
    }

    @Test
    fun `fails when content differs`() {
        assertFailsWith<AssertionError> { Golden.assertMatches("example.txt", "goodbye\n") }
    }
}
