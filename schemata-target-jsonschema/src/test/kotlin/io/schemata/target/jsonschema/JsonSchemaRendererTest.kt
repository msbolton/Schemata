package io.schemata.target.jsonschema

import io.schemata.target.json.JsonBool
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonString
import io.schemata.testkit.Golden
import io.schemata.testkit.JsonSchema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertNull

class JsonSchemaRendererTest {
    private val customers =
        JsonSchemaDocument(
            path = "shop/customers.schema.json",
            id = "urn:schemata:shop.customers",
            title = "shop.customers",
            defs =
                listOf(
                    JsonDef(
                        "Customer",
                        ObjectSchema(
                            listOf(
                                Property(
                                    "id",
                                    ScalarSchema(
                                        "string",
                                        format = "uuid",
                                        pattern = JsonSchemaTypes.UUID_PATTERN,
                                    ),
                                    true,
                                )
                            ),
                            closed = true,
                        ),
                    )
                ),
        )

    private val orders =
        JsonSchemaDocument(
            path = "shop/orders.schema.json",
            id = "urn:schemata:shop.orders",
            title = "shop.orders",
            defs =
                listOf(
                    JsonDef(
                        "Status",
                        EnumSchema(
                            listOf(EnumEntry("pending", "Not paid yet."), EnumEntry("paid", null)),
                            Common(description = "Lifecycle."),
                        ),
                    ),
                    JsonDef(
                        "Plain",
                        EnumSchema(listOf(EnumEntry("a", null), EnumEntry("b", null))),
                    ),
                    JsonDef("Cash", ObjectSchema(emptyList(), closed = true)),
                    JsonDef(
                        "Payment",
                        TaggedUnionSchema(
                            listOf(
                                Member("card", RefSchema("#/\$defs/Card"), "By card."),
                                Member("cash", RefSchema("#/\$defs/Cash"), null),
                                Member(
                                    "int64",
                                    ScalarSchema(
                                        "integer",
                                        minimum = BigDecimal("-9223372036854775808"),
                                        maximum = BigDecimal("9223372036854775807"),
                                    ),
                                    null,
                                ),
                            )
                        ),
                    ),
                    JsonDef(
                        "Card",
                        ObjectSchema(
                            listOf(Property("last4", ScalarSchema("string", maxLength = 4), true)),
                            closed = true,
                        ),
                    ),
                    JsonDef(
                        "Order",
                        ObjectSchema(
                            listOf(
                                Property(
                                    "id",
                                    ScalarSchema(
                                        "string",
                                        format = "uuid",
                                        pattern = JsonSchemaTypes.UUID_PATTERN,
                                    ),
                                    true,
                                ),
                                Property(
                                    "customer",
                                    RefSchema("urn:schemata:shop.customers#/\$defs/Customer"),
                                    true,
                                ),
                                Property(
                                    "status",
                                    RefSchema(
                                        "#/\$defs/Status",
                                        Common(default = JsonString("pending")),
                                    ),
                                    false,
                                ),
                                Property(
                                    "note",
                                    ScalarSchema(
                                        "string",
                                        maxLength = 500,
                                        common = Common(nullable = true, description = "Free text."),
                                    ),
                                    false,
                                ),
                                Property(
                                    "payment",
                                    RefSchema("#/\$defs/Payment", Common(nullable = true)),
                                    false,
                                ),
                                Property(
                                    "tags",
                                    ArraySchema(
                                        ScalarSchema("string", common = Common(nullable = true)),
                                        maxItems = 10,
                                    ),
                                    true,
                                ),
                                Property(
                                    "attributes",
                                    MapSchema(
                                        ScalarSchema("string"),
                                        keys =
                                            ScalarSchema("string", pattern = "^(0|-?[1-9][0-9]*)$"),
                                        minProperties = 1,
                                    ),
                                    true,
                                ),
                                Property(
                                    "total",
                                    ScalarSchema(
                                        "string",
                                        pattern = "^-?[0-9]{1,15}(\\.[0-9]{1,4})?$",
                                        common = Common(default = JsonString("0.0000")),
                                    ),
                                    false,
                                ),
                                Property(
                                    "retries",
                                    ScalarSchema(
                                        "integer",
                                        minimum = BigDecimal.ZERO,
                                        maximum = BigDecimal("2147483647"),
                                        common = Common(default = JsonNumber("3")),
                                    ),
                                    false,
                                ),
                                Property(
                                    "active",
                                    ScalarSchema(
                                        "boolean",
                                        common = Common(default = JsonBool(true), deprecated = true),
                                    ),
                                    false,
                                ),
                                Property(
                                    "blob",
                                    ScalarSchema(
                                        "string",
                                        contentEncoding = "base64",
                                        minLength = 4,
                                        maxLength = 8,
                                    ),
                                    true,
                                ),
                                Property(
                                    "placed_at",
                                    ScalarSchema("string", format = "date-time"),
                                    true,
                                ),
                            ),
                            closed = false,
                            common = Common(description = "A customer's order.", deprecated = true),
                        ),
                    ),
                ),
        )

    private fun files() =
        JsonSchemaRenderer.render(JsonSchemaModel(listOf(customers, orders))).associate {
            it.path to it.content
        }

    @Test
    fun `renders the kitchen sink golden`() {
        Golden.assertMatches("kitchen.schema.json", files().getValue("shop/orders.schema.json"))
    }

    @Test
    fun `the kitchen sink is valid draft 2020-12 with every ref resolved`() {
        assertNull(JsonSchema.validate(files()))
    }
}
