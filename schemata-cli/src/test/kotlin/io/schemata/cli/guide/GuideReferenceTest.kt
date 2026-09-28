package io.schemata.cli.guide

import io.schemata.cli.Pipeline
import io.schemata.cli.SourceInput
import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every fenced `schemata` block in the reference compiles through both targets without an error;
 * every block fenced `schemata error` must report at least one error. A block may hold several
 * files separated by a line `--- <name>.schemata`; otherwise it is `example.schemata`.
 */
class GuideReferenceTest {
    private val file = File(Guide.dir, "reference.md")

    @TestFactory
    fun `reference blocks compile or fail as marked`(): List<DynamicTest> =
        Guide.blocks(file.readText(), "schemata").map { block ->
            DynamicTest.dynamicTest("reference.md:${block.line}") {
                val result = Pipeline.check(files(block.body), Pipeline.targets, strict = false)
                val errors = result.diagnostics.filter { it.severity.name == "ERROR" }
                val mustFail = block.info.split(' ').contains("error")
                val report =
                    errors.joinToString("\n") {
                        "${it.code.id} ${it.span.file}:${it.span.startLine} ${it.message}"
                    }
                if (mustFail)
                    assertTrue(
                        errors.isNotEmpty(),
                        "block at line ${block.line} is marked error but compiled",
                    )
                else
                    assertTrue(
                        errors.isEmpty(),
                        "block at line ${block.line} does not compile:\n$report",
                    )
            }
        }

    private fun files(body: String): List<SourceInput> {
        val parts = mutableListOf<Pair<String, StringBuilder>>()
        for (line in body.lines()) {
            val m = Regex("^--- (\\S+\\.schemata)$").find(line)
            if (m != null) parts += m.groupValues[1] to StringBuilder()
            else {
                if (parts.isEmpty()) parts += "example.schemata" to StringBuilder()
                parts.last().second.appendLine(line)
            }
        }
        return parts.map { SourceInput(it.first, it.second.toString()) }
    }
}
