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

    @Test
    fun `only a name declared in more than one visible namespace is qualified`() {
        val other = unit("other", emptyList(), record("Location"), record("Stamp"))
        val aggregate =
            unit(
                "aggregate",
                listOf("basic", "other"),
                record("Location"),
                record("Own"),
                record(
                    "Party",
                    field("at", UnitType.Ref("Location")),
                    field("code", UnitType.Ref("Code")),
                    field("stamp", UnitType.Ref("Stamp")),
                    field("own", UnitType.Ref("Own")),
                ),
            )
        val party =
            qualifyAmbiguousRefs(listOf(basic, other, aggregate))[2].declarations[2] as UnitRecord
        assertEquals(
            listOf(
                UnitType.Ref("aggregate.Location"),
                UnitType.Ref("Code"),
                UnitType.Ref("Stamp"),
                UnitType.Ref("Own"),
            ),
            party.fields.map { it.type },
        )
    }

    @Test
    fun `payloads and error bodies an import also declares are written in full`() {
        val location = UnitType.Ref("Location")
        val aggregate =
            unit("aggregate", listOf("basic"), record("Location"))
                .copy(
                    services =
                        listOf(
                            UnitService(
                                name = "Places",
                                operations =
                                    listOf(
                                        UnitOperation(
                                            "find",
                                            UnitPayload(location, false),
                                            UnitPayload(location, true),
                                            "get \"/places\"",
                                            null,
                                            emptyList(),
                                            errors =
                                                listOf(
                                                    UnitErrorResponse(404, location),
                                                    UnitErrorResponse(
                                                        409,
                                                        UnitType.Ref("basic.Code"),
                                                    ),
                                                    UnitErrorResponse(503, null),
                                                ),
                                        )
                                    ),
                                doc = null,
                                annotations = emptyList(),
                                errors = listOf(UnitErrorResponse(null, location)),
                            )
                        )
                )
        val service = qualifyAmbiguousRefs(listOf(basic, aggregate))[1].services.single()
        val qualified = UnitType.Ref("aggregate.Location")
        assertEquals(listOf(UnitErrorResponse(null, qualified)), service.errors)
        val find = service.operations.single()
        assertEquals(UnitPayload(qualified, false), find.request)
        assertEquals(UnitPayload(qualified, true), find.response)
        assertEquals(
            listOf(
                UnitErrorResponse(404, qualified),
                UnitErrorResponse(409, UnitType.Ref("basic.Code")),
                UnitErrorResponse(503, null),
            ),
            find.errors,
        )
    }
}
