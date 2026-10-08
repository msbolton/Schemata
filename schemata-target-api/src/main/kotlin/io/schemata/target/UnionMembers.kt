package io.schemata.target

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember

/**
 * The name a union member contributes: a scalar's builtin name, or the referenced declaration's
 * [override] when it has one, else the lower snake of its simple name.
 */
fun unionMemberStem(type: Type, schema: Schema, override: (TypeDecl) -> String?): String =
    when (type) {
        is Scalar -> type.builtin.typeName
        is Ref -> schema.lookup(type.target).let { override(it) ?: Names.snakeCase(it.name) }
        is ListOf,
        is MapOf ->
            error("union members are named types or scalars; the analyzer rejects collections")
    }

/**
 * The type a member is named from: the keyed model it stands for when it carries that model's key,
 * else its own type.
 */
val UnionMember.named: Type
    get() = byKey?.let { Ref(it) } ?: type
