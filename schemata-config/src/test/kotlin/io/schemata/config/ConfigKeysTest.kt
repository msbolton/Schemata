package io.schemata.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigKeysTest {
    private val targets = listOf("proto", "sql")

    @Test
    fun `the run tables come in the order config shows them`() {
        assertEquals(
            listOf("compile", "import", "migrate", "report", "sources"),
            ConfigKeys.runTables(targets).keys.toList(),
        )
        assertEquals(
            listOf("targets", "out", "strict", "proto_layout"),
            ConfigKeys.runTables(targets).getValue("compile").map { it.name },
        )
    }

    @Test
    fun `the defaults are the built-in ones and import has none for from and namespace`() {
        val keys = ConfigKeys.runTables(targets).values.flatten().associateBy { it.name }
        assertEquals(targets, keys.getValue("targets").default)
        assertEquals("out", keys.getValue("out").default)
        assertEquals(false, keys.getValue("strict").default)
        assertEquals("schema", keys.getValue("proto_layout").default)
        assertNull(keys.getValue("from").default)
        assertNull(keys.getValue("namespace").default)
        assertEquals(emptyList<String>(), keys.getValue("include").default)
        assertEquals("human", keys.getValue("format").default)
        assertEquals("auto", keys.getValue("color").default)
    }

    @Test
    fun `every key has a note`() {
        ConfigKeys.runTables(targets).values.flatten().forEach {
            assertTrue(it.note.isNotBlank(), it.name)
            assertTrue(it.note == it.note.trim() && !it.note.endsWith("."), it.note)
        }
    }
}
