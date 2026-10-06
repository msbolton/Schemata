package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportRefsTest {
    private fun field(name: String, type: UnitType) =
        UnitField(name, type, false, null, null, emptyList())

    private fun record(
        name: String,
        vararg fields: UnitField,
        nested: List<UnitDecl> = emptyList(),
    ) = UnitRecord(name, fields.toList(), nested, null, emptyList())

    private fun unit(namespace: String, imports: List<String>, vararg decls: UnitDecl) =
        SchemataUnit(
            namespace,
            emptyList(),
            null,
            imports,
            decls.toList(),
            sourcePath = "$namespace.xsd",
        )

    private val basic = unit("basic", emptyList(), record("Location"), record("Code"))

    @Test
    fun `a bare reference an import also declares is written in full`() {
        val aggregate =
            unit(
                "aggregate",
                listOf("basic"),
                record("Location"),
                record(
                    "Party",
                    field("at", UnitType.Ref("Location")),
                    field("all", UnitType.ListOf(UnitType.Ref("Location"), false, emptyList())),
                    field("code", UnitType.Ref("basic.Code")),
                ),
                UnitUnion("Place", listOf(UnionMember(UnitType.Ref("Location"))), null, emptyList()),
            )
        val result = qualifyAmbiguousRefs(listOf(basic, aggregate))
        assertEquals(basic, result[0])
        val party = result[1].declarations[1] as UnitRecord
        assertEquals(
            listOf(
                UnitType.Ref("aggregate.Location"),
                UnitType.ListOf(UnitType.Ref("aggregate.Location"), false, emptyList()),
                UnitType.Ref("basic.Code"),
            ),
            party.fields.map { it.type },
        )
        val place = result[1].declarations[2] as UnitUnion
        assertEquals(UnitType.Ref("aggregate.Location"), place.members.single().type)
    }

    @Test
    fun `a nested declaration of the same name shadows the clash`() {
        val aggregate =
            unit(
                "aggregate",
                listOf("basic"),
                record("Location"),
                record(
                    "Party",
                    field("at", UnitType.Ref("Location")),
                    nested = listOf(record("Location")),
                ),
            )
        val result = qualifyAmbiguousRefs(listOf(basic, aggregate))
        assertEquals(aggregate, result[1])
    }

    @Test
    fun `a unit without the import keeps its bare references`() {
        val alone =
            unit(
                "alone",
                emptyList(),
                record("Location"),
                record("P", field("at", UnitType.Ref("Location"))),
            )
        assertEquals(listOf(basic, alone), qualifyAmbiguousRefs(listOf(basic, alone)))
    }
}
