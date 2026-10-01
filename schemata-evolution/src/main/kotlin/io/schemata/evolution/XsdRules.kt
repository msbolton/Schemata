package io.schemata.evolution

import io.schemata.core.ir.RecordType

/**
 * What each kind of [Change] means for an XSD document validated under the old schema: a removed
 * field is always a surprise since XSD has no notion of an open or closed content model, and a
 * removed declaration only breaks validation when it was reachable as a root element.
 */
object XsdRules :
    Rulebook by InstanceRules(
        target = "xsd",
        removedFieldBreaks = { _, _ -> true },
        removedDeclarationBreaks = { ctx, decl ->
            decl is RecordType && ctx.isRoot(Side.OLD, decl.qualifiedName)
        },
    )
