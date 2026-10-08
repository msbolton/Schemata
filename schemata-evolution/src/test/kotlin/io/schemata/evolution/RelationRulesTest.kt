package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
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
    fun `embed then json on a keyless model is a strategy change from the default`() {
        val address = "model Address { #1 street string }\n"
        val old = analysed(customer.format("#2 home Address { embed }") + address)
        val new = analysed(customer.format("#2 home Address @sql(strategy: json)") + address)
        val change = assertIs<AnnotationChanged>(Differ.diff(old, new).single())
        assertEquals("sql" to "strategy", change.target to change.key)
        assertEquals(null to AnnotationValue.Name("json"), change.from to change.to)
        assertIs<Verdict.Breaking>(SqlRules.classify(change, ChangeContext(old, new)))
    }

    @Test
    fun `embed then json on a keyed model is a strategy change from embed`() {
        val old =
            analysed(customerKey + "model Order { #1 id uuid { id }  #2 c Customer { embed } }\n")
        val new =
            analysed(
                customerKey +
                    "model Order { #1 id uuid { id }  #2 c Customer @sql(strategy: json) }\n"
            )
        val change = Differ.diff(old, new).filterIsInstance<AnnotationChanged>().single()
        assertEquals(
            AnnotationValue.Name("embed") to AnnotationValue.Name("json"),
            change.from to change.to,
        )
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
        assertTrue(Differ.diff(old, new).isEmpty())
        judged(old, new).forEach { (target, list) ->
            assertTrue(list.all { it is Verdict.Compatible }, target)
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
                "s.Customer.id: type changed from int32 to int64; the reference fields of " +
                    "s.Order and s.Invoice carry this key and change type with it",
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

    private val referencedBy = "model Order { #1 id uuid { id }  #2 customer Customer }\n"

    private fun customerWith(body: String) = "schema s\nmodel Customer { $body }\n"

    @Test
    fun `a key added to a referenced keyless model breaks the document targets`() {
        val old = analysed(customerWith("#1 id uuid  #2 name string") + referencedBy)
        val new = analysed(customerWith("#1 id uuid { id }  #2 name string") + referencedBy)
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            val breaking = assertIs<Verdict.Breaking>(verdicts.getValue(target).single(), target)
            assertTrue(
                breaking.message.startsWith(
                    "s.Customer.id: { id } added breaks the reference fields of s.Order"
                ),
                breaking.message,
            )
        }
        assertIs<Verdict.Compatible>(verdicts.getValue("openapi").single())
        assertIs<Verdict.Breaking>(verdicts.getValue("sql").single())
    }

    @Test
    fun `a key removed from a referenced model breaks the document targets`() {
        val old = analysed(customerWith("#1 id uuid { id }  #2 name string") + referencedBy)
        val new = analysed(customerWith("#1 id uuid  #2 name string") + referencedBy)
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            assertIs<Verdict.Breaking>(verdicts.getValue(target).single(), target)
        }
    }

    @Test
    fun `a key moved to another field breaks the document targets twice`() {
        val old = analysed(customerWith("#1 id uuid { id }  #2 code string") + referencedBy)
        val new = analysed(customerWith("#1 id uuid  #2 code string { id }") + referencedBy)
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            assertEquals(2, verdicts.getValue(target).count { it is Verdict.Breaking }, target)
        }
    }

    @Test
    fun `a key made composite breaks every document target`() {
        val old = analysed(customerWith("#1 a uuid { id }  #2 b string") + referencedBy)
        val new = analysed(customerWith("#1 a uuid  #2 b string  @@id(a, b)") + referencedBy)
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            val list = verdicts.getValue(target)
            assertEquals(2, list.size, target)
            assertTrue(list.all { it is Verdict.Breaking }, target)
        }
    }

    @Test
    fun `a key field renamed is a note on proto and breaks the other document targets`() {
        val old = analysed(customerWith("#1 id uuid { id }") + referencedBy)
        val new = analysed(customerWith("#1 code uuid { id }") + referencedBy)
        val verdicts = judged(old, new)
        val note = assertIs<Verdict.Note>(verdicts.getValue("proto").single())
        assertTrue(
            note.message.contains("the reference fields of s.Order are named after it"),
            note.message,
        )
        listOf("xsd", "jsonschema").forEach { target ->
            val breaking = assertIs<Verdict.Breaking>(verdicts.getValue(target).single(), target)
            assertTrue(
                breaking.message.endsWith(
                    "; it also breaks the reference fields of s.Order, which are named after it"
                ),
                breaking.message,
            )
        }
    }

    @Test
    fun `a pinned key field rename still breaks the referencing documents`() {
        val pin = Annotations(mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str("id"))))
        val order =
            record(
                "s",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), key = true),
                field(2, "customer", Ref(qn("s", "Customer"))),
            )
        val old = record("s", "Customer", field(1, "id", Scalar(Builtin.UUID), key = true))
        val new =
            record(
                "s",
                "Customer",
                field(1, "code", Scalar(Builtin.UUID), annotations = pin, key = true),
            )
        val breaking =
            verdicts(
                    JsonSchemaRules,
                    listOf(namespace("s", listOf(old, order))),
                    listOf(namespace("s", listOf(new, order))),
                )
                .filterIsInstance<Verdict.Breaking>()
                .single()
        assertTrue(
            breaking.message.startsWith("s.Customer.code: the key field was renamed breaks"),
            breaking.message,
        )
    }

    @Test
    fun `a key change counts union members and map values and skips embedded copies`() {
        val rest =
            "union Party = #1 Customer | #2 Vendor\nmodel Vendor { #1 id uuid { id } }\n" +
                "model Book { #1 id uuid { id }  #2 by_name map<string, Customer> }\n" +
                "model Copy { #1 id uuid { id }  #2 customer Customer { embed } }\n"
        val old = analysed(customerWith("#1 id uuid { id }  #2 code string") + rest)
        val new = analysed(customerWith("#1 id uuid  #2 code string { id }") + rest)
        val breaking = assertIs<Verdict.Breaking>(judged(old, new).getValue("xsd").first())
        assertTrue(
            breaking.message.contains("the reference fields of s.Party and s.Book,"),
            breaking.message,
        )
    }

    @Test
    fun `a key change on an unreferenced model is compatible on the document targets`() {
        val old = analysed(customerWith("#1 id uuid { id }  #2 code string"))
        val new = analysed(customerWith("#1 id uuid  #2 code string { id }"))
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema", "openapi").forEach { target ->
            assertTrue(verdicts.getValue(target).all { it is Verdict.Compatible }, target)
        }
    }

    @Test
    fun `on openapi a key change breaks only when a service reaches a referencing model`() {
        val service = "service Orders { #1 get(Order): Order }\n"
        val old =
            analysed(customerWith("#1 id uuid { id }  #2 code string") + referencedBy + service)
        val new =
            analysed(customerWith("#1 id uuid  #2 code string { id }") + referencedBy + service)
        val verdicts = judged(old, new).getValue("openapi")
        assertEquals(2, verdicts.count { it is Verdict.Breaking })
    }

    @Test
    fun `a redundant block id written over the same key changes no reference`() {
        val old = analysed(customerWith("#1 a uuid { id }  #2 b string { id }") + referencedBy)
        val new =
            analysed(
                customerWith("#1 a uuid { id }  #2 b string { id }  @@id(a, b)") + referencedBy
            )
        judged(old, new)
            .filterKeys { it != "sql" }
            .forEach { (target, list) -> assertTrue(list.all { it is Verdict.Compatible }, target) }
    }

    @Test
    fun `a new key field on a referenced keyless model breaks the document targets`() {
        val old = analysed(customerWith("#1 id uuid  #2 name string") + referencedBy)
        val new =
            analysed(
                customerWith("#1 id uuid  #2 name string  #3 code string { id }") + referencedBy
            )
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            val breaking = assertIs<Verdict.Breaking>(verdicts.getValue(target).single(), target)
            assertTrue(
                breaking.message.contains("breaks the reference fields of s.Order"),
                breaking.message,
            )
        }
        assertEquals(
            "s.Customer.code: key field added breaks the reference fields of s.Order, which carry " +
                "this model's key: the wire shape changes",
            (verdicts.getValue("proto").single() as Verdict.Breaking).message,
        )
    }

    @Test
    fun `removing the only key field of a referenced model breaks the document targets`() {
        val old = analysed(customerWith("#1 id uuid { id }  #2 name string") + referencedBy)
        val new = analysed(customerWith("#2 name string  reserved #1, \"id\"") + referencedBy)
        val verdicts = judged(old, new)
        listOf("proto", "xsd", "jsonschema").forEach { target ->
            val breaking = verdicts.getValue(target).filterIsInstance<Verdict.Breaking>().single()
            assertTrue(
                breaking.message.contains("breaks the reference fields of s.Order"),
                breaking.message,
            )
        }
    }

    @Test
    fun `renaming a field of a composite key is a note on proto`() {
        val old = analysed(customerWith("#1 a uuid  #2 b string  @@id(a, b)") + referencedBy)
        val new = analysed(customerWith("#1 a uuid  #2 c string  @@id(a, c)") + referencedBy)
        val verdicts = judged(old, new)
        assertTrue(
            verdicts.getValue("proto").none { it is Verdict.Breaking },
            "${verdicts["proto"]}",
        )
        assertEquals(1, verdicts.getValue("proto").count { it is Verdict.Note })
        assertTrue(verdicts.getValue("xsd").any { it is Verdict.Breaking })
    }
}
