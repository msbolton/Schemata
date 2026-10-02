package io.schemata.evolution

/**
 * What each kind of [Change] means for a JSON document validated under the old schema: a removed
 * field only breaks validation on a closed record (one without `@jsonschema(open)`), and every
 * declaration is addressable, so a removed one always breaks whatever still references it.
 */
object JsonSchemaRules :
    Rulebook by InstanceRules(
        target = "jsonschema",
        removedFieldBreaks = { ctx, record -> !ctx.isOpen(Side.NEW, record) },
        removedDeclarationBreaks = { _, _ -> true },
    )
