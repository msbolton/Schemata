package io.schemata.target.jsonschema

import io.schemata.target.TargetModel
import io.schemata.target.json.JsonValue
import java.math.BigDecimal

/** Every `.schema.json` the compilation produces, one per namespace, in namespace order. */
data class JsonSchemaModel(val documents: List<JsonSchemaDocument>) : TargetModel

/** One document, legal by construction: keys final, every `$ref` absolute or local. */
data class JsonSchemaDocument(
    val path: String,
    val id: String,
    val title: String,
    val defs: List<JsonDef>,
)

data class JsonDef(val key: String, val schema: JsonSchema)

/**
 * What every schema node can carry. [nullable] admits `null` at this use: a scalar widens its
 * `type`, anything else is wrapped in `anyOf` with `{"type": "null"}`.
 */
data class Common(
    val description: String? = null,
    val default: JsonValue? = null,
    val deprecated: Boolean = false,
    val nullable: Boolean = false,
)

sealed interface JsonSchema {
    val common: Common
}

/** `type: object` with [properties] in order; [closed] prints `additionalProperties: false`. */
data class ObjectSchema(
    val properties: List<Property>,
    val closed: Boolean,
    override val common: Common = Common(),
) : JsonSchema

data class Property(val name: String, val schema: JsonSchema, val required: Boolean)

data class ArraySchema(
    val items: JsonSchema,
    val minItems: Long? = null,
    val maxItems: Long? = null,
    override val common: Common = Common(),
) : JsonSchema

/**
 * `type: object` keyed by [keys] (printed as `propertyNames`) with [values] as
 * `additionalProperties`.
 */
data class MapSchema(
    val values: JsonSchema,
    val keys: ScalarSchema? = null,
    val minProperties: Long? = null,
    val maxProperties: Long? = null,
    override val common: Common = Common(),
) : JsonSchema

/** [type] is `boolean`, `integer`, `number`, or `string`. */
data class ScalarSchema(
    val type: String,
    val format: String? = null,
    val pattern: String? = null,
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val minLength: Long? = null,
    val maxLength: Long? = null,
    val contentEncoding: String? = null,
    override val common: Common = Common(),
) : JsonSchema

/** Printed as `enum` unless any entry has a description, then as `oneOf` of `const` entries. */
data class EnumSchema(val values: List<EnumEntry>, override val common: Common = Common()) :
    JsonSchema

data class EnumEntry(val value: String, val description: String?)

/** An externally tagged union: `oneOf` of single-property closed objects. */
data class TaggedUnionSchema(val members: List<Member>, override val common: Common = Common()) :
    JsonSchema

/** One member: its tag and its schema, which carries the member's doc as its description. */
data class Member(val tag: String, val schema: JsonSchema)

/** `#/$defs/<key>` in this document or `<id>#/$defs/<key>` in another. */
data class RefSchema(val uri: String, override val common: Common = Common()) : JsonSchema
