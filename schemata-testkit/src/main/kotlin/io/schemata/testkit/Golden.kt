package io.schemata.testkit

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden-file assertion for rendered output.
 *
 * Files live under `src/test/resources/golden/` of the module whose tests call this (Gradle runs
 * tests with the module directory as the working directory). Set `SCHEMATA_GOLDEN_UPDATE=1` to
 * rewrite the file from `actual` and pass; review the resulting diff before committing.
 */
object Golden {
    private const val UPDATE_ENV = "SCHEMATA_GOLDEN_UPDATE"

    fun assertMatches(name: String, actual: String) {
        val file = File("src/test/resources/golden/$name")
        if (System.getenv(UPDATE_ENV) == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
            println("golden: rewrote ${file.path}")
            return
        }
        if (!file.exists()) {
            fail("golden file ${file.path} does not exist; run with $UPDATE_ENV=1 to create it")
        }
        assertEquals(file.readText(), actual, "output differs from ${file.path}; run with $UPDATE_ENV=1 to accept")
    }
}
