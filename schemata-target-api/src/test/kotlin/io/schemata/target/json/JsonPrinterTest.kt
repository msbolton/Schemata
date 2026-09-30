package io.schemata.target.json

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPrinterTest {
    @Test
    fun `prints nested values with two-space indentation and a final newline`() {
        val value =
            obj(
                "\$schema" to JsonString("https://json-schema.org/draft/2020-12/schema"),
                "n" to JsonNumber("9223372036854775807"),
                "ok" to JsonBool(true),
                "none" to JsonNull,
                "list" to JsonArray(listOf(JsonString("a"), JsonNumber("1.50"))),
                "empty" to JsonObject(emptyList()),
                "nothing" to JsonArray(emptyList()),
                "inner" to obj("k" to JsonString("v")),
            )
        assertEquals(
            """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "n": 9223372036854775807,
              "ok": true,
              "none": null,
              "list": [
                "a",
                1.50
              ],
              "empty": {},
              "nothing": [],
              "inner": {
                "k": "v"
              }
            }
            """
                .trimIndent() + "\n",
            JsonPrinter.print(value),
        )
    }

    @Test
    fun `escapes quotes backslashes and control characters and keeps unicode as is`() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001é\"", JsonPrinter.quote("a\"b\\c\n\t\u0001é"))
    }

    @Test
    fun `members keep insertion order`() {
        val printed = JsonPrinter.print(obj("z" to JsonNull, "a" to JsonNull))
        assertEquals("{\n  \"z\": null,\n  \"a\": null\n}\n", printed)
    }
}
