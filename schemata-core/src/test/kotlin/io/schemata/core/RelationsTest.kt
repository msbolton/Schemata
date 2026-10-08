package io.schemata.core

import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelationsTest {
    private val base =
        """
        schema s
        model Customer { #1 id uuid { id }  #2 orders Order[] @relation(customer) }
        model Order { #1 id uuid { id }  #2 customer Customer @relation(onDelete: cascade) }
    """

    @Test
    fun `a back-reference is virtual and names its forward field`() {
        val r = analyze(base)
        val customer = r.schema!!.lookup(QualifiedName("s", listOf("Customer"))) as RecordType
        assertTrue(customer.fields[1].virtual)
        assertEquals("customer", customer.fields[1].backReferenceOf)
        val order = r.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType
        assertEquals(OnDelete.CASCADE, (order.fields[1].type as Ref).relation.onDelete)
        assertFalse(order.fields[1].virtual)
    }

    @Test
    fun `a back-reference to a missing or wrong field is SCH1050`() {
        assertEquals(
            listOf("SCH1050"),
            analyze(base.replace("@relation(customer)", "@relation(buyer)")).diagnostics.map {
                it.code.id
            },
        )
    }

    @Test
    fun `two references need named back-references`() {
        val two =
            """
            schema s
            model Customer { #1 id uuid { id }  #2 bought Order[] @relation  #3 sold Order[] @relation(seller) }
            model Order { #1 id uuid { id }  #2 buyer Customer  #3 seller Customer }
        """
        assertEquals(listOf("SCH1051"), analyze(two).diagnostics.map { it.code.id })
    }

    @Test
    fun `set_null needs a nullable reference`() {
        val r =
            analyze(base.replace("@relation(onDelete: cascade)", "@relation(onDelete: set_null)"))
        assertEquals(listOf("SCH1052"), r.diagnostics.map { it.code.id })
        assertTrue(
            analyze(
                    base.replace(
                        "customer Customer @relation(onDelete: cascade)",
                        "customer Customer? @relation(onDelete: set_null)",
                    )
                )
                .diagnostics
                .isEmpty()
        )
    }

    @Test
    fun `embed sets the relation and a one-to-one is unique plus a singular back-reference`() {
        val r =
            analyze(
                """
            schema s
            model User { #1 id uuid { id }  #2 profile Profile @relation(user) }
            model Profile { #1 id uuid { id }  #2 user User { unique }  #3 address Address { embed } }
            model Address { street string }
        """
            )
        val profile = r.schema!!.lookup(QualifiedName("s", listOf("Profile"))) as RecordType
        assertTrue(profile.fields[1].unique)
        assertTrue((profile.fields[2].type as Ref).relation.embed)
        assertTrue(
            (r.schema!!.lookup(QualifiedName("s", listOf("User"))) as RecordType).fields[1].virtual
        )
    }

    @Test
    fun `a back-reference to a many relation names its forward list field`() {
        val r =
            analyze(
                """
            schema s
            model Tag { #1 id uuid { id }  #2 orders Order[] @relation(tags) }
            model Order { #1 id uuid { id }  #2 tags Tag[] }
        """
            )
        assertTrue(r.diagnostics.isEmpty())
        val tag = r.schema!!.lookup(QualifiedName("s", listOf("Tag"))) as RecordType
        assertTrue(tag.fields[1].virtual)
        assertEquals("tags", tag.fields[1].backReferenceOf)
        val order = r.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType
        assertFalse(order.fields[1].virtual)
    }

    @Test
    fun `two forward references with one named back-reference and no other is accepted`() {
        val r =
            analyze(
                """
            schema s
            model Customer { #1 id uuid { id }  #2 sold Order[] @relation(seller) }
            model Order { #1 id uuid { id }  #2 buyer Customer  #3 seller Customer }
        """
            )
        assertTrue(r.diagnostics.isEmpty())
        val customer = r.schema!!.lookup(QualifiedName("s", listOf("Customer"))) as RecordType
        assertEquals("seller", customer.fields[1].backReferenceOf)
    }

    @Test
    fun `a bare back-reference follows the only forward reference`() {
        val r = analyze(base.replace("@relation(customer)", "@relation"))
        val customer = r.schema!!.lookup(QualifiedName("s", listOf("Customer"))) as RecordType
        assertEquals("customer", customer.fields[1].backReferenceOf)
    }

    @Test
    fun `a back-reference closes no cycle`() {
        val r = analyze(base)
        val customer = r.schema!!.lookup(QualifiedName("s", listOf("Customer"))) as RecordType
        assertFalse(customer.recursive)
    }

    @Test
    fun `the relation annotation leaves the IR`() {
        val r = analyze(base)
        val customer = r.schema!!.lookup(QualifiedName("s", listOf("Customer"))) as RecordType
        val order = r.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType
        assertTrue(customer.fields[1].annotations.isEmpty)
        assertTrue(order.fields[1].annotations.isEmpty)
    }

    @Test
    fun `a field name and onDelete together are the wrong shape`() {
        val both = base.replace("@relation(customer)", "@relation(customer, onDelete: cascade)")
        assertEquals(listOf("SCH1018"), analyze(both).diagnostics.map { it.code.id })
    }

    @Test
    fun `onDelete takes cascade, restrict, or set_null`() {
        val bad = base.replace("onDelete: cascade", "onDelete: nothing")
        assertEquals(listOf("SCH1018"), analyze(bad).diagnostics.map { it.code.id })
    }

    @Test
    fun `relation on a field that references no keyed model is misplaced`() {
        val scalar =
            base.replace(
                "#1 id uuid { id }  #2 customer",
                "#1 id uuid { id } @relation  #2 customer",
            )
        assertEquals(listOf("SCH1017"), analyze(scalar).diagnostics.map { it.code.id })
    }

    @Test
    fun `a back-reference stores nothing a constraint could name`() {
        val listed =
            """
            schema s
            model User { #1 id uuid { id }  #2 profile Profile { unique } @relation(user)  @@index(id, profile) }
            model Profile { #1 id uuid { id }  #2 user User }
        """
        assertEquals(listOf("SCH1049", "SCH1018"), analyze(listed).diagnostics.map { it.code.id })
    }
}
