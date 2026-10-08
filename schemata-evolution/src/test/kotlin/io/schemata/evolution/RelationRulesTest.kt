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

    private val customerKey = "schema s\nmodel Customer { #1 id uuid { id } }\n"

    @Test
    fun `embed flipped on a keyed reference breaks the output shape and the sql strategy`() {
        val byKey =
            analysed(customerKey + "model Order { #1 id uuid { id }  #2 customer Customer }\n")
        val embedded =
            analysed(
                customerKey + "model Order { #1 id uuid { id }  #2 customer Customer { embed } }\n"
            )
        listOf(byKey to embedded, embedded to byKey).forEach { (old, new) ->
            val changes = Differ.diff(old, new)
            assertIs<FieldTypeChanged>(changes.first())
            val verdicts = judged(old, new)
            listOf("proto", "xsd", "jsonschema").forEach { target ->
                assertIs<Verdict.Breaking>(verdicts.getValue(target).first(), target)
            }
            assertIs<Verdict.Compatible>(verdicts.getValue("sql").first())
            val strategy = assertIs<AnnotationChanged>(changes.last())
            assertEquals("{ embed }", annotationLabel(strategy))
            val breaking = assertIs<Verdict.Breaking>(verdicts.getValue("sql").last())
            assertTrue(
                breaking.message.startsWith("s.Order.customer: { embed } "),
                breaking.message,
            )
        }
    }

    @Test
    fun `embed flipped on a list of keyed references breaks`() {
        val byKey =
            analysed(customerKey + "model Order { #1 id uuid { id }  #2 customers Customer[] }\n")
        val embedded =
            analysed(
                customerKey +
                    "model Order { #1 id uuid { id }  #2 customers Customer[] { embed } }\n"
            )
        val verdicts = judged(byKey, embedded)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            assertIs<Verdict.Breaking>(verdicts.getValue(target).first(), target)
        }
    }

    @Test
    fun `embed flipped on an unkeyed reference changes no output`() {
        val address = "model Address { #1 street string }\n"
        val old =
            analysed(customerKey + address + "model Order { #1 id uuid { id }  #2 a Address }\n")
        val new =
            analysed(
                customerKey +
                    address +
                    "model Order { #1 id uuid { id }  #2 a Address { embed } }\n"
            )
        val changes = Differ.diff(old, new)
        assertTrue(changes.none { it is FieldTypeChanged })
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema", "openapi").forEach { target ->
            assertTrue(verdicts.getValue(target).all { it is Verdict.Compatible }, target)
        }
    }

    private val membership =
        "schema s\nmodel Membership { #1 id uuid { id }  #2 a string  #3 b string %s }\n"

    @Test
    fun `a composite unique added breaks sql only and removed is compatible`() {
        val without = analysed(membership.format(""))
        val with = analysed(membership.format("@@unique(a, b)"))
        val added = judged(without, with)
        val change = assertIs<AnnotationChanged>(Differ.diff(without, with).single())
        assertEquals("@@unique(a, b)", annotationLabel(change))
        val breaking = assertIs<Verdict.Breaking>(added.getValue("sql").single())
        assertEquals(
            "s.Membership: @@unique(a, b) added breaks tables that already hold duplicate values",
            breaking.message,
        )
        added
            .filterKeys { it != "sql" }
            .forEach { (target, list) ->
                assertEquals(listOf<Verdict>(Verdict.Compatible), list, target)
            }
        judged(with, without).forEach { (target, list) ->
            assertEquals(listOf<Verdict>(Verdict.Compatible), list, target)
        }
    }

    @Test
    fun `a composite index added changed or removed is compatible everywhere`() {
        val without = analysed(membership.format(""))
        val one = analysed(membership.format("@@index(a, b)"))
        val other = analysed(membership.format("@@index(b, a)"))
        listOf(without to one, one to without, one to other).forEach { (old, new) ->
            assertTrue(Differ.diff(old, new).isNotEmpty())
            judged(old, new).forEach { (target, list) ->
                assertTrue(list.all { it is Verdict.Compatible }, target)
            }
        }
    }

    @Test
    fun `a key field retyped under referencing models is a note on the wire targets`() {
        val old =
            analysed(
                "schema s\nmodel Customer { #1 id int32 { id } }\n" +
                    "model Order { #1 id uuid { id }  #2 customer Customer }\n" +
                    "model Invoice { #1 id uuid { id }  #2 customer Customer }\n"
            )
        val new =
            analysed(
                "schema s\nmodel Customer { #1 id int64 { id } }\n" +
                    "model Order { #1 id uuid { id }  #2 customer Customer }\n" +
                    "model Invoice { #1 id uuid { id }  #2 customer Customer }\n"
            )
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            val note = assertIs<Verdict.Note>(verdicts.getValue(target).single(), target)
            assertEquals(
                "s.Customer.id: type changed from int32 to int64; referenced by 2 models; " +
                    "their emitted key fields change with it",
                note.message,
            )
        }
        assertIs<Verdict.Compatible>(verdicts.getValue("sql").single())
    }

    @Test
    fun `a key field retyped with no referencing model stays compatible`() {
        val old = analysed("schema s\nmodel Customer { #1 id int32 { id } }\n")
        val new = analysed("schema s\nmodel Customer { #1 id int64 { id } }\n")
        judged(old, new).forEach { (target, list) ->
            assertEquals(listOf<Verdict>(Verdict.Compatible), list, target)
        }
    }
}
