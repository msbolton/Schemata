package io.schemata.cli.guide

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Every relative link in the README and the guide points at a path that exists. */
class GuideLinksTest {
    private val files =
        listOf(File(Guide.root, "README.md")) +
            Guide.dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    @TestFactory
    fun `relative links resolve`(): List<DynamicTest> =
        files.flatMap { file ->
            Guide.relativeLinks(file.readText()).map { link ->
                DynamicTest.dynamicTest("${file.name} -> $link") {
                    val target = File(file.parentFile, link).canonicalFile
                    assertTrue(target.exists(), "${file.name} links to missing $link")
                }
            }
        }
}
