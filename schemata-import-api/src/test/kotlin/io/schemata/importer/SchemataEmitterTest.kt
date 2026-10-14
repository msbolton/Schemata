package io.schemata.importer

import io.schemata.core.Analyzer
import io.schemata.core.ir.effectiveErrors
import io.schemata.core.ir.services
import io.schemata.core.ir.successStatus
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemataEmitterTest {
    private fun xsd(key: String, value: String? = null) = UnitAnnotation("xsd", key, value)

    private val unit =
        SchemataUnit(
            namespace = "gpx",
            annotations = listOf(xsd("namespace", "\"http://www.topografix.com/GPX/1/1\"")),
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
                    emptyList(),
                    null,
                    emptyList(),
                    listOf(UnitRecord("R", emptyList(), emptyList(), null, emptyList())),
                    sourcePath = "s.xsd",
                )
            )
        assertEquals(
            "schema s\n\nmodel R {}\n",
            (Formatter.format(text, "s.schemata") as FormatResult.Formatted).text,
        )
    }

    @Test
    fun `ordinals reserved deprecated and namespace annotations print and format`() {
        val unit =
            SchemataUnit(
                namespace = "corp.orders",
                annotations = listOf(UnitAnnotation("proto", "package", "\"corp.orders.v1\"")),
                doc = null,
                imports = emptyList(),
                declarations =
                    listOf(
                        UnitRecord(
                            name = "Order",
                            fields =
                                listOf(
                                    UnitField(
                                        "id",
                                        UnitType.Scalar("uuid", emptyList()),
                                        false,
                                        null,
                                        null,
                                        emptyList(),
                                        ordinal = 1,
                                    ),
                                    UnitField(
                                        "legacy",
                                        UnitType.Scalar("int32", emptyList()),
                                        true,
                                        null,
                                        null,
                                        emptyList(),
                                        ordinal = 3,
                                        deprecated = true,
                                    ),
                                ),
                            nested =
                                listOf(
                                    UnitEnum(
                                        "Status",
                                        listOf(
                                            UnitEnumValue(
                                                "pending",
                                                null,
                                                emptyList(),
                                                ordinal = 1,
                                            ),
                                            UnitEnumValue("paid", null, emptyList(), ordinal = 2),
                                        ),
                                        null,
                                        emptyList(),
                                        reserved = listOf(UnitReserved.Ordinals(5, 6)),
                                    )
                                ),
                            doc = "An order.",
                            annotations = emptyList(),
                            reserved =
                                listOf(
                                    UnitReserved.Ordinals(2, 2),
                                    UnitReserved.Ordinals(7, 9),
                                    UnitReserved.Name("old_ref"),
                                ),
                            deprecated = true,
                        ),
                        UnitUnion(
                            "Payment",
                            listOf(
                                UnionMember(UnitType.Ref("Order"), null, ordinal = 1),
                                UnionMember(
                                    UnitType.Scalar("string", emptyList()),
                                    null,
                                    ordinal = 2,
                                ),
                            ),
                            null,
                            emptyList(),
                        ),
                    ),
                sourcePath = "orders.proto",
            )
        val text = SchemataEmitter.emit(unit)
        val formatted = Formatter.format(text, "corp/orders.schemata")
        assertTrue(formatted is FormatResult.Formatted, formatted.toString())
        Golden.assertMatches("ordinals.schemata", (formatted as FormatResult.Formatted).text)
    }

    @Test
    fun `an emitter refuses ordinals on some members but not all`() {
        val record =
            UnitRecord(
                "R",
                listOf(
                    UnitField(
                        "a",
                        UnitType.Scalar("bool", emptyList()),
                        false,
                        null,
                        null,
                        emptyList(),
                        1,
                    ),
                    UnitField(
                        "b",
                        UnitType.Scalar("bool", emptyList()),
                        false,
                        null,
                        null,
                        emptyList(),
                    ),
                ),
                emptyList(),
                null,
                emptyList(),
            )
        val unit =
            SchemataUnit("x", emptyList(), null, emptyList(), listOf(record), sourcePath = "x")
        assertFailsWith<IllegalStateException> { SchemataEmitter.emit(unit) }
    }

    @Test
    fun `an emitter refuses ordinals on some enum values but not all`() {
        val enum =
            UnitEnum(
                "E",
                listOf(
                    UnitEnumValue("A", null, emptyList(), 1),
                    UnitEnumValue("B", null, emptyList()),
                ),
                null,
                emptyList(),
            )
        val unit = SchemataUnit("x", emptyList(), null, emptyList(), listOf(enum), sourcePath = "x")
        assertFailsWith<IllegalStateException> { SchemataEmitter.emit(unit) }
    }

    @Test
    fun `an emitter refuses ordinals on some union members but not all`() {
        val ref = UnitType.Scalar("bool", emptyList())
        val union =
            UnitUnion(
                "U",
                listOf(UnionMember(ref, ordinal = 1), UnionMember(ref)),
                null,
                emptyList(),
            )
        val unit =
            SchemataUnit("x", emptyList(), null, emptyList(), listOf(union), sourcePath = "x")
        assertFailsWith<IllegalStateException> { SchemataEmitter.emit(unit) }
    }

    @Test
    fun `reserved ordinals refuse a range that runs backwards`() {
        assertFailsWith<IllegalArgumentException> { UnitReserved.Ordinals(5, 3) }
        assertEquals(UnitReserved.Ordinals(3, 3), UnitReserved.Ordinals(3, 3))
    }

    @Test
    fun `services print after declarations in the formatter's form`() {
        val id = UnitType.Ref("Id")
        val unit =
            SchemataUnit(
                namespace = "t",
                doc = null,
                imports = emptyList(),
                declarations =
                    listOf(UnitRecord("Id", emptyList(), emptyList(), null, emptyList())),
                services =
                    listOf(
                        UnitService(
                            name = "Orders",
                            doc = "Place and read orders.",
                            annotations = listOf(UnitAnnotation("proto", "name", "\"OrderApi\"")),
                            operations =
                                listOf(
                                    UnitOperation(
                                        "get",
                                        UnitPayload(id, false),
                                        UnitPayload(id, false),
                                        "get \"/orders/{id}\"",
                                        "Fetch one order.",
                                        emptyList(),
                                        ordinal = 1,
                                    ),
                                    UnitOperation(
                                        "list",
                                        UnitPayload(id, false),
                                        UnitPayload(id, true),
                                        "get \"/orders\"",
                                        null,
                                        emptyList(),
                                        ordinal = 2,
                                    ),
                                    UnitOperation(
                                        "cancel",
                                        UnitPayload(id, false),
                                        null,
                                        "delete \"/orders/{id}\"",
                                        null,
                                        emptyList(),
                                        ordinal = 4,
                                    ),
                                    UnitOperation(
                                        "upload",
                                        UnitPayload(id, true),
                                        UnitPayload(id, false),
                                        null,
                                        null,
                                        emptyList(),
                                        ordinal = 5,
                                        deprecated = true,
                                    ),
                                    UnitOperation(
                                        "ping",
                                        null,
                                        null,
                                        null,
                                        null,
                                        emptyList(),
                                        ordinal = 6,
                                    ),
                                ),
                            reserved =
                                listOf(UnitReserved.Ordinals(3, 3), UnitReserved.Name("archive")),
                        )
                    ),
                sourcePath = "t.proto",
            )
        assertEquals(
            """
            |schema t
            |
            |model Id {
            |}
            |
            |/// Place and read orders.
            |@proto(name: "OrderApi")
            |service Orders {
            |  /// Fetch one order.
            |  #1 get(Id): Id  get "/orders/{id}"
            |  #2 list(Id): stream Id  get "/orders"
            |  #4 cancel(Id)  delete "/orders/{id}"
            |  @deprecated
            |  #5 upload(stream Id): Id
            |  #6 ping()
            |  reserved #3, "archive"
            |}
            |"""
                .trimMargin(),
            SchemataEmitter.emit(unit),
        )
    }

    private fun field(
        name: String,
        type: UnitType,
        nullable: Boolean = false,
        annotations: List<UnitAnnotation> = emptyList(),
        default: String? = null,
        options: List<Pair<String, String?>> = emptyList(),
        onDelete: String? = null,
    ) =
        UnitField(
            name,
            type,
            nullable,
            default,
            null,
            annotations,
            options = options,
            onDelete = onDelete,
        )

    private fun formatted(unit: SchemataUnit): String =
        (Formatter.format(SchemataEmitter.emit(unit), "s.schemata") as FormatResult.Formatted).text

    private fun unitOf(vararg declarations: UnitDecl) =
        SchemataUnit("s", emptyList(), null, emptyList(), declarations.toList(), sourcePath = "s")

    @Test
    fun `a field prints its options its attributes and its default on one line`() {
        val record =
            UnitRecord(
                "R",
                listOf(
                    field(
                        "code",
                        UnitType.Scalar(
                            "string",
                            listOf("min" to "2", "pattern" to "\"^[A-Z]+$\""),
                        ),
                        options = listOf("id" to null, "unique" to null),
                        annotations = listOf(UnitAnnotation("sql", "type", "\"char(3)\"")),
                        default = "\"AB\"",
                    )
                ),
                emptyList(),
                null,
                emptyList(),
            )
        assertEquals(
            """
            |schema s
            |
            |model R { code string { id, unique, min 2, match "^[A-Z]+$" } @sql(type: "char(3)") = "AB" }
            |"""
                .trimMargin(),
            formatted(unitOf(record)),
        )
    }

    @Test
    fun `lists print as brackets with their element's options after their own`() {
        val bounded = UnitType.Scalar("int32", listOf("min" to "0"))
        val record =
            UnitRecord(
                "R",
                listOf(
                    field(
                        "a",
                        UnitType.ListOf(bounded, true, listOf("max" to "4")),
                        nullable = true,
                    ),
                    field(
                        "b",
                        UnitType.ListOf(
                            UnitType.ListOf(bounded, false, listOf("min" to "1")),
                            false,
                            emptyList(),
                        ),
                    ),
                    field(
                        "c",
                        UnitType.MapOf(
                            UnitType.Scalar("string", listOf("max" to "5")),
                            UnitType.ListOf(bounded, false, emptyList()),
                            true,
                            listOf("min" to "1"),
                        ),
                    ),
                ),
                emptyList(),
                null,
                emptyList(),
            )
        assertEquals(
            """
            |schema s
            |
            |model R {
            |  a int32?[]?                                 { maxItems 4, min 0 }
            |  b list<int32[] { minItems 1, min 0 }>
            |  c map<string { max 5 }, int32[]? { min 0 }> { minItems 1 }
            |}
            |"""
                .trimMargin(),
            formatted(unitOf(record)),
        )
    }

    @Test
    fun `a record's key constraints and annotations close its body as block attributes`() {
        val uuid = UnitType.Scalar("uuid", emptyList())
        val record =
            UnitRecord(
                "Seat",
                listOf(
                    field("number", UnitType.Scalar("int32", emptyList())),
                    field("tenant", uuid),
                    field("row", UnitType.Scalar("string", emptyList())),
                    field(
                        "account",
                        UnitType.Ref("Account"),
                        nullable = true,
                        onDelete = "set_null",
                        annotations = listOf(UnitAnnotation("sql", "column", "\"acct\"")),
                    ),
                ),
                emptyList(),
                null,
                listOf(UnitAnnotation("sql", "table", "\"seats\"")),
                deprecated = true,
                key = listOf("tenant", "number"),
                uniques = listOf(listOf("account", "row")),
                indexes = listOf(listOf("row", "number")),
            )
        assertEquals(
            """
            |schema s
            |
            |model Seat {
            |  number  int32
            |  tenant  uuid
            |  row     string
            |  account Account? @relation(onDelete: set_null) @sql(column: "acct")
            |
            |  @@id(tenant, number)
            |  @@unique(account, row)
            |  @@index(row, number)
            |  @@deprecated
            |  @@sql(table: "seats")
            |}
            |"""
                .trimMargin(),
            formatted(unitOf(record)),
        )
    }

    @Test
    fun `a union member's bounds are its options`() {
        val union =
            UnitUnion(
                "U",
                listOf(
                    UnionMember(UnitType.Scalar("string", listOf("max" to "34"))),
                    UnionMember(UnitType.Ref("R")),
                ),
                null,
                emptyList(),
            )
        assertEquals("schema s\n\nunion U = string { max 34 } | R\n", formatted(unitOf(union)))
    }

    private val common =
        SchemataUnit(
            namespace = "shop.common",
            doc = null,
            imports = emptyList(),
            declarations =
                listOf(
                    UnitRecord(
                        "Problem",
                        listOf(
                            field("type", UnitType.Scalar("string", emptyList())),
                            field("title", UnitType.Scalar("string", emptyList())),
                        ),
                        emptyList(),
                        null,
                        emptyList(),
                    )
                ),
            sourcePath = "common.yaml",
        )

    private val problem = UnitType.Ref("Problem")

    private val orders =
        SchemataUnit(
            namespace = "shop.orders",
            doc = null,
            imports = listOf("shop.common"),
            declarations =
                listOf(
                    UnitRecord(
                        "OrderId",
                        listOf(field("id", UnitType.Scalar("uuid", emptyList()))),
                        emptyList(),
                        null,
                        emptyList(),
                    ),
                    UnitRecord(
                        "Order",
                        listOf(
                            field("id", UnitType.Scalar("uuid", emptyList())),
                            field("total", UnitType.Scalar("int64", emptyList())),
                        ),
                        emptyList(),
                        null,
                        emptyList(),
                    ),
                    UnitRecord(
                        "Conflict",
                        listOf(field("current", UnitType.Scalar("uuid", emptyList()))),
                        emptyList(),
                        null,
                        emptyList(),
                    ),
                ),
            services =
                listOf(
                    UnitService(
                        name = "Orders",
                        operations =
                            listOf(
                                UnitOperation(
                                    "get",
                                    UnitPayload(UnitType.Ref("OrderId"), false),
                                    UnitPayload(UnitType.Ref("Order"), false),
                                    "get \"/orders/{id}\"",
                                    "Fetch one order.",
                                    emptyList(),
                                    ordinal = 1,
                                    errors = listOf(UnitErrorResponse(404, problem)),
                                ),
                                UnitOperation(
                                    "place",
                                    UnitPayload(UnitType.Ref("Order"), false),
                                    UnitPayload(UnitType.Ref("Order"), false),
                                    "post \"/orders\" 201",
                                    null,
                                    emptyList(),
                                    ordinal = 2,
                                    errors =
                                        listOf(
                                            UnitErrorResponse(409, UnitType.Ref("Conflict")),
                                            UnitErrorResponse(422, null),
                                        ),
                                ),
                                UnitOperation(
                                    "cancel",
                                    UnitPayload(UnitType.Ref("OrderId"), false),
                                    null,
                                    "delete \"/orders/{id}\"",
                                    null,
                                    emptyList(),
                                    ordinal = 3,
                                ),
                                UnitOperation(
                                    "ping",
                                    null,
                                    null,
                                    null,
                                    null,
                                    emptyList(),
                                    ordinal = 4,
                                    errors = listOf(UnitErrorResponse(429, null)),
                                ),
                            ),
                        doc = "Place and read orders.",
                        annotations = emptyList(),
                        errors =
                            listOf(
                                UnitErrorResponse(400, problem),
                                UnitErrorResponse(503, null),
                                UnitErrorResponse(null, problem),
                            ),
                    )
                ),
            sourcePath = "orders.yaml",
        )

    @Test
    fun `error responses print first in the service and after each operation's binding`() {
        assertEquals(
            """
            |schema shop.orders
            |
            |import shop.common
            |
            |model OrderId {
            |  id uuid
            |}
            |
            |model Order {
            |  id uuid
            |  total int64
            |}
            |
            |model Conflict {
            |  current uuid
            |}
            |
            |/// Place and read orders.
            |service Orders {
            |  errors { 400 Problem  503  default Problem }
            |  /// Fetch one order.
            |  #1 get(OrderId): Order  get "/orders/{id}"  errors { 404 Problem }
            |  #2 place(Order): Order  post "/orders" 201  errors { 409 Conflict  422 }
            |  #3 cancel(OrderId)  delete "/orders/{id}"
            |  #4 ping()  errors { 429 }
            |}
            |"""
                .trimMargin(),
            SchemataEmitter.emit(orders),
        )
        Golden.assertMatches("services.schemata", formatted(orders))
    }

    @Test
    fun `an emitted unit with error responses formats and analyses with every clause in place`() {
        val files = emitUnits(listOf(common, orders))
        assertEquals(listOf("shop/common.schemata", "shop/orders.schemata"), files.map { it.path })
        val parsed = files.map { Parser.parse(it.content, it.path) }
        assertEquals(emptyList(), parsed.flatMap { it.diagnostics }.map { it.message })
        val analysis = Analyzer.analyze(parsed.map { it.file!! })
        assertEquals(
            emptyList(),
            analysis.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val service = analysis.schema!!.services().single()
        val byName = service.operations.associateBy { it.name }
        // the service's clause applies to every operation, and an operation's adds its own codes
        assertEquals(
            listOf(400, 404, 503, null),
            effectiveErrors(service, byName.getValue("get")).map { it.code },
        )
        assertEquals(
            listOf(400, 409, 422, 503, null),
            effectiveErrors(service, byName.getValue("place")).map { it.code },
        )
        assertEquals(
            listOf(400, 429, 503, null),
            effectiveErrors(service, byName.getValue("ping")).map { it.code },
        )
        assertEquals(201, byName.getValue("place").successStatus())
        assertEquals(204, byName.getValue("cancel").successStatus())
    }

    @Test
    fun `an error response code outside 400 to 599 is refused`() {
        assertFailsWith<IllegalArgumentException> { UnitErrorResponse(399, null) }
        assertFailsWith<IllegalArgumentException> { UnitErrorResponse(600, null) }
        assertEquals(null, UnitErrorResponse(null, null).code)
        assertEquals(599, UnitErrorResponse(599, UnitType.Ref("P")).code)
    }

    private fun emitUnits(units: List<SchemataUnit>): List<EmittedUnit> {
        return units.map { unit ->
            val path = unit.namespace.replace(".", "/") + ".schemata"
            EmittedUnit(path, SchemataEmitter.emit(unit))
        }
    }

    private data class EmittedUnit(val path: String, val content: String)
}
