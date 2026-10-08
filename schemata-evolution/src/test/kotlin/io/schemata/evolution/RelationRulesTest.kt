package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelationRulesTest {
    private val customer = "schema s\nmodel Customer { #1 id uuid { id } %s }\n"

    private fun order(relation: String = "") =
        "model Order { #1 id uuid { id }  #2 customer Customer? $relation }\n"

    private fun judged(old: Schema, new: Schema): Map<String, List<Verdict>> {
        val ctx = ChangeContext(old, new)
        val changes = Differ.diff(old, new)
        return Rulebooks.all.associate { rulebook ->
            rulebook.target to changes.map { rulebook.classify(it, ctx) }
        }
    }

    @Test
    fun `a back-reference added or removed is compatible on every target`() {
        val without = analysed(customer.format("") + order())
        val with = analysed(customer.format("#2 orders Order[] @relation(customer)") + order())
        val added = judged(without, with)
        val removed = judged(with, without)
        listOf(added, removed).forEach { verdicts ->
            verdicts.forEach { (target, list) ->
                assertEquals(listOf<Verdict>(Verdict.Compatible), list, target)
            }
        }
    }

    @Test
    fun `on delete changed is a note on sql and compatible elsewhere`() {
        val old = analysed(customer.format("") + order())
        val new = analysed(customer.format("") + order("@relation(onDelete: set_null)"))
        val changes = Differ.diff(old, new)
        val change = assertIs<AnnotationChanged>(changes.single())
        assertEquals("relation" to "onDelete", change.target to change.key)
        assertEquals(AnnotationValue.Name("set_null"), change.to)
        val verdicts = judged(old, new)
        val note = assertIs<Verdict.Note>(verdicts.getValue("sql").single())
        assertEquals(
            "s.Order.customer: on delete changed; existing rows are unaffected, future deletes " +
                "behave differently",
            note.message,
        )
        verdicts
            .filterKeys { it != "sql" }
            .forEach { (target, list) ->
                assertEquals(listOf<Verdict>(Verdict.Compatible), list, target)
            }
    }

    @Test
    fun `on delete restrict written out is no change`() {
        val old = analysed(customer.format("") + order())
        val new = analysed(customer.format("") + order("@relation(onDelete: restrict)"))
        assertTrue(Differ.diff(old, new).isEmpty())
    }

    @Test
    fun `embed then json is a strategy change`() {
        val address = "model Address { #1 street string }\n"
        val old = analysed(customer.format("#2 home Address { embed }") + address)
        val new = analysed(customer.format("#2 home Address @sql(strategy: json)") + address)
        val change = assertIs<AnnotationChanged>(Differ.diff(old, new).single())
        assertEquals("sql" to "strategy", change.target to change.key)
        assertEquals(
            AnnotationValue.Name("embed") to AnnotationValue.Name("json"),
            change.from to change.to,
        )
        assertIs<Verdict.Breaking>(SqlRules.classify(change, ChangeContext(old, new)))
    }
}
