package io.schemata.importer

import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemataEmitterTest {
    private fun xsd(key: String, value: String? = null) = UnitAnnotation("xsd", key, value)

    private val unit =
        SchemataUnit(
            namespace = "gpx",
            xsdNamespace = "http://www.topografix.com/GPX/1/1",
            doc = "GPX schema version 1.1.",
            imports = listOf("shop.customers"),
            declarations =
                listOf(
                    UnitEnum(
                        "Fix",
                        listOf(
                            UnitEnumValue("none", "No fix.", emptyList()),
                            UnitEnumValue("_2d", null, listOf(xsd("name", "\"2d\""))),
                        ),
                        null,
                        emptyList(),
                    ),
                    UnitUnion(
                        "Payment",
                        listOf(
                            UnionMember(UnitType.Ref("Card")),
                            UnionMember(UnitType.Scalar("int64", emptyList())),
                        ),
                        "How it was paid.",
                        emptyList(),
                    ),
                    UnitRecord(
                        "Gpx",
                        listOf(
                            UnitField(
                                "version",
                                UnitType.Scalar("string", emptyList()),
                                false,
                                "\"1.1\"",
                                null,
                                listOf(xsd("attribute")),
                            ),
                            UnitField(
                                "creator",
                                UnitType.Scalar("string", listOf("max" to "100")),
                                true,
                                null,
                                "Who wrote it.",
                                listOf(xsd("attribute")),
                            ),
                            UnitField(
                                "full_name",
                                UnitType.Scalar("string", emptyList()),
                                false,
                                null,
                                null,
                                listOf(xsd("name", "\"full-name\"")),
                            ),
                            UnitField(
                                "customer",
                                UnitType.Ref("shop.customers.Customer"),
                                false,
                                null,
                                null,
                                emptyList(),
                            ),
                            UnitField(
                                "wpt",
                                UnitType.ListOf(UnitType.Ref("Wpt"), false, emptyList()),
                                false,
                                null,
                                "Waypoints.",
                                emptyList(),
                            ),
                            UnitField(
                                "tags",
                                UnitType.ListOf(
                                    UnitType.Scalar("string", listOf("max" to "3")),
                                    true,
                                    listOf("max" to "2"),
                                ),
                                true,
                                null,
                                null,
                                emptyList(),
                            ),
                            UnitField(
                                "counts",
                                UnitType.MapOf(
                                    UnitType.Scalar("int64", emptyList()),
                                    UnitType.Ref("Wpt"),
                                    true,
                                    listOf("min" to "1"),
                                ),
                                false,
                                null,
                                null,
                                emptyList(),
                            ),
                            UnitField(
                                "total",
                                UnitType.Scalar(
                                    "decimal",
                                    listOf("p" to "19", "s" to "4", "min" to "0"),
                                ),
                                false,
                                "0.0000",
                                null,
                                emptyList(),
                            ),
                            UnitField("fix", UnitType.Ref("Fix"), false, "none", null, emptyList()),
                        ),
                        listOf(
                            UnitRecord(
                                "Wpt",
                                listOf(
                                    UnitField(
                                        "lat",
                                        UnitType.Scalar(
                                            "float64",
                                            listOf("min" to "-90.0", "max" to "90.0"),
                                        ),
                                        false,
                                        null,
                                        null,
                                        emptyList(),
                                    )
                                ),
                                emptyList(),
                                "A point.",
                                listOf(xsd("root", "false")),
                            )
                        ),
                        "GPX is the root element.",
                        listOf(xsd("name", "\"gpxType\"")),
                    ),
                ),
            sourcePath = "gpx.xsd",
        )

    @Test
    fun `emits the kitchen sink and the formatter accepts it`() {
        val text = SchemataEmitter.emit(unit)
        val formatted = Formatter.format(text, "gpx.schemata")
        assertTrue(formatted is FormatResult.Formatted, formatted.toString())
        Golden.assertMatches("kitchen.schemata", (formatted as FormatResult.Formatted).text)
    }

    @Test
    fun `a unit with no doc annotations or imports emits only the namespace and declarations`() {
        val text =
            SchemataEmitter.emit(
                SchemataUnit(
                    "s",
                    null,
                    null,
                    emptyList(),
                    listOf(UnitRecord("R", emptyList(), emptyList(), null, emptyList())),
                    "s.xsd",
                )
            )
        assertEquals(
            "namespace s\n\nrecord R {}\n",
            (Formatter.format(text, "s.schemata") as FormatResult.Formatted).text,
        )
    }
}
