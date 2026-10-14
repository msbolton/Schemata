package io.schemata.importer

/**
 * [units] with every bare reference that would be ambiguous written out in full: in field types,
 * union members, operation payloads, and error bodies. A bare name is looked up in the unit's own
 * namespace and in every namespace it imports at once, so a reference to a unit's own `Location` is
 * ambiguous when an imported namespace also declares a `Location`; the fully qualified
 * `<namespace>.Location` always resolves. A name a record nests, which the lookup finds first from
 * inside that record, is left alone.
 */
fun qualifyAmbiguousRefs(units: List<SchemataUnit>): List<SchemataUnit> {
    val topLevel = units.associate { u -> u.namespace to u.declarations.map { it.name }.toSet() }
    return units.map { unit ->
        val imported = unit.imports.flatMap { topLevel[it].orEmpty() }.toSet()
        val clashing = topLevel.getValue(unit.namespace) intersect imported
        if (clashing.isEmpty()) unit
        else {
            val qualifier = Qualifier(unit.namespace, clashing)
            unit.copy(
                declarations = unit.declarations.map { qualifier.decl(it, emptySet()) },
                services = unit.services.map(qualifier::service),
            )
        }
    }
}

private class Qualifier(val namespace: String, val clashing: Set<String>) {
    fun decl(decl: UnitDecl, shadowed: Set<String>): UnitDecl =
        when (decl) {
            is UnitRecord -> {
                val inner = shadowed + decl.nested.map { it.name }
                decl.copy(
                    fields = decl.fields.map { it.copy(type = type(it.type, inner)) },
                    nested = decl.nested.map { decl(it, inner) },
                )
            }
            is UnitUnion ->
                decl.copy(members = decl.members.map { it.copy(type = type(it.type, shadowed)) })
            is UnitEnum -> decl
        }

    fun service(s: UnitService): UnitService =
        s.copy(
            operations =
                s.operations.map { op ->
                    op.copy(
                        request = op.request?.let { it.copy(type = ref(it.type)) },
                        response = op.response?.let { it.copy(type = ref(it.type)) },
                        errors = op.errors.map(::error),
                    )
                },
            errors = s.errors.map(::error),
        )

    private fun error(e: UnitErrorResponse): UnitErrorResponse = e.copy(body = e.body?.let(::ref))

    /** A payload or an error body sits in a service, where no record's nested name shadows it. */
    private fun ref(r: UnitType.Ref): UnitType.Ref = type(r, emptySet()) as UnitType.Ref

    fun type(type: UnitType, shadowed: Set<String>): UnitType =
        when (type) {
            is UnitType.Scalar -> type
            is UnitType.Ref -> {
                val head = type.name.substringBefore('.')
                if (head in clashing && head !in shadowed) UnitType.Ref("$namespace.${type.name}")
                else type
            }
            is UnitType.ListOf -> type.copy(element = type(type.element, shadowed))
            is UnitType.MapOf ->
                type.copy(key = type(type.key, shadowed), value = type(type.value, shadowed))
        }
}
