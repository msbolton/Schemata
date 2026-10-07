package io.schemata.migrate

import io.schemata.target.sql.ColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    private val base =
        """
        namespace s
        record Customer {
          @sql(key) #1 id: uuid
          #2 name: string(max = 100)
          #3 note: string?
        }
        """

    @Test
    fun `no relational change plans nothing`() {
        assertEquals(emptyList(), plan(base, base))
    }

    @Test
    fun `a nullable field added is one clean add column`() {
        val steps =
            plan(base, base.replace("#3 note: string?", "#3 note: string?\n  #4 tier: int32?"))
        val add = steps.single() as AddColumn
        assertEquals("tier", add.column.name)
        assertEquals(Risk.CLEAN, add.risk)
        assertEquals("s.Customer.tier", add.subject.path)
    }

    @Test
    fun `a required field without a default is added nullable then set not null and may fail`() {
        val steps =
            plan(base, base.replace("#3 note: string?", "#3 note: string?\n  #4 tier: int32"))
        assertEquals(listOf(AddColumn::class, SetNotNull::class), steps.map { it::class })
        assertTrue((steps[0] as AddColumn).column.nullable)
        assertEquals(Risk.MAY_FAIL, steps[1].risk)
    }

    @Test
    fun `a required field with a default is one clean add column with the default`() {
        val steps =
            plan(base, base.replace("#3 note: string?", "#3 note: string?\n  #4 tier: int32 = 1"))
        val add = steps.single() as AddColumn
        assertEquals(false, add.column.nullable)
        assertEquals("1", add.column.default)
    }

    @Test
    fun `a field removed is a destructive drop column`() {
        val steps = plan(base, base.replace("  #3 note: string?\n", ""))
        val drop = steps.single() as DropColumn
        assertEquals("note", drop.column)
        assertEquals(Risk.DESTRUCTIVE, drop.risk)
        assertEquals("s.Customer.note", drop.subject.path)
    }

    @Test
    fun `a field renamed under its ordinal is a rename column`() {
        val steps = plan(base, base.replace("#3 note:", "#3 comment:"))
        val rename = steps.single() as RenameColumn
        assertEquals("note" to "comment", rename.from to rename.to)
    }

    @Test
    fun `a rename pinned by a column override plans nothing`() {
        assertEquals(
            emptyList(),
            plan(base, base.replace("#3 note:", "@sql(column = \"note\") #3 comment:")),
        )
    }

    @Test
    fun `a widened type is a clean alter and a narrowed one is destructive`() {
        val widened =
            plan(base, base.replace("string(max = 100)", "string(max = 200)")).single()
                as AlterColumnType
        assertEquals(ColumnType.VARCHAR(200), widened.type)
        assertEquals(Risk.CLEAN, widened.risk)
        val narrowed =
            plan(base, base.replace("string(max = 100)", "string(max = 50)")).single()
                as AlterColumnType
        assertEquals(Risk.DESTRUCTIVE, narrowed.risk)
    }

    @Test
    fun `nullable to non-null backfills when there is a default and may fail without one`() {
        val withDefault = plan(base, base.replace("#3 note: string?", "#3 note: string = \"\""))
        assertEquals(
            listOf(SetDefault::class, Backfill::class, SetNotNull::class),
            withDefault.map { it::class },
        )
        assertEquals(Risk.CLEAN, withDefault[2].risk)
        val without = plan(base, base.replace("#3 note: string?", "#3 note: string"))
        val set = without.single() as SetNotNull
        assertEquals(Risk.MAY_FAIL, set.risk)
    }

    @Test
    fun `non-null to nullable is a clean drop not null`() {
        val steps =
            plan(base, base.replace("#2 name: string(max = 100)", "#2 name: string(max = 100)?"))
        assertTrue(steps.single() is DropNotNull)
    }

    @Test
    fun `a tightened check is dropped and re-added and may fail`() {
        val old = base.replace("#3 note: string?", "#3 age: int32(min = 0)")
        val new = base.replace("#3 note: string?", "#3 age: int32(min = 18)")
        val steps = plan(old, new)
        assertEquals(listOf(DropConstraint::class, AddConstraint::class), steps.map { it::class })
        assertEquals(Risk.MAY_FAIL, steps[1].risk)
        val loosened = plan(new, old)
        assertEquals(Risk.CLEAN, loosened[1].risk)
    }

    @Test
    fun `a key moved drops the primary key and adds the new one and may fail`() {
        val new =
            base
                .replace("@sql(key) #1 id: uuid", "#1 id: uuid")
                .replace("#2 name:", "@sql(key) #2 name:")
        val steps = plan(base, new)
        val drop = steps.filterIsInstance<DropConstraint>().single()
        val add = steps.filterIsInstance<AddConstraint>().single()
        assertEquals("pk_customer", drop.name)
        assertEquals(Constraint.PrimaryKey("pk_customer", listOf("name")), add.constraint)
        assertEquals(Risk.MAY_FAIL, add.risk)
    }

    @Test
    fun `a renamed table renames its constraints and its child table's foreign key`() {
        val old =
            """
            namespace s
            record Order {
              @sql(key) #1 id: uuid
              #2 lines: list<Line>
              record Line { #1 sku: string(max = 8) }
            }
            """
        val new = old.replace("record Order {", "@sql(table = \"purchase\") record Order {")
        val steps = plan(old, new)
        assertTrue(steps.any { it is RenameTable && it.at.table == "order" && it.to == "purchase" })
        assertTrue(
            steps.any {
                it is RenameTable && it.at.table == "order_lines" && it.to == "purchase_lines"
            }
        )
        assertTrue(
            steps.any { it is RenameConstraint && it.from == "pk_order" && it.to == "pk_purchase" }
        )
        assertTrue(
            steps.any {
                it is RenameColumn &&
                    it.at.table == "purchase_lines" &&
                    it.from == "order_id" &&
                    it.to == "purchase_id"
            }
        )
        assertTrue(
            steps.any {
                it is RenameConstraint &&
                    it.from == "fk_order_lines_order" &&
                    it.to == "fk_purchase_lines_purchase"
            }
        )
        assertTrue(steps.none { it.risk != Risk.CLEAN })
    }

    @Test
    fun `a dropped column drops the constraints that name it first`() {
        val old = base.replace("#3 note: string?", "@sql(unique) #3 code: string(max = 8)")
        val steps = plan(old, base.replace("  #3 note: string?\n", ""))
        val kinds = steps.map { it::class }
        assertTrue(kinds.indexOf(DropConstraint::class) < kinds.indexOf(DropColumn::class))
        assertEquals(
            "uq_customer_code",
            (steps.first { it is DropConstraint } as DropConstraint).name,
        )
    }

    @Test
    fun `a declaration added creates its table and one removed drops it destructively`() {
        val new = base + "\nrecord Tag { @sql(key) #1 id: uuid }\n"
        assertTrue(plan(base, new).single() is CreateTable)
        val drop = plan(new, base).single() as DropTable
        assertEquals("tag", drop.at.table)
        assertEquals("s.Tag", drop.subject.path)
    }

    @Test
    fun `a union member removed drops its variant columns and re-adds the kind check`() {
        val old =
            """
            namespace s
            record Card { #1 last4: string(max = 4) }
            union Payment = #1 Card | #2 string
            record Order { @sql(key) #1 id: uuid  #2 payment: Payment }
            """
        val new = old.replace("union Payment = #1 Card | #2 string", "union Payment = #1 Card")
        val steps = plan(old, new)
        assertTrue(
            steps.any {
                it is DropColumn && it.column == "payment_string" && it.risk == Risk.DESTRUCTIVE
            }
        )
        assertTrue(steps.any { it is DropConstraint && it.name == "ck_order_payment_kind" })
        assertTrue(
            steps.any { it is AddConstraint && it.constraint.name == "ck_order_payment_kind" }
        )
    }

    @Test
    fun `a strategy change drops the old shape and creates the new with a help that names the move`() {
        val old =
            """
            namespace s
            record Order {
              @sql(key) #1 id: uuid
              #2 tags: list<string>
            }
            """
        val new = old.replace("#2 tags:", "@sql(strategy = table) #2 tags:")
        val steps = plan(old, new)
        val drop = steps.filterIsInstance<DropColumn>().single()
        assertEquals("tags", drop.column)
        assertTrue(drop.help.contains("move the data"), drop.help)
        assertTrue(steps.any { it is CreateTable && it.table.name == "order_tags" })
    }

    @Test
    fun `a doc change is a comment and a removed doc comments null`() {
        val documented = base.replace("record Customer", "/// People.\nrecord Customer")
        val add = plan(base, documented).single() as Comment
        assertEquals("People.", add.text)
        val remove = plan(documented, base).single() as Comment
        assertEquals(null, remove.text)
    }

    @Test
    fun `a schema override moves every table and drops the old schema`() {
        val new = base.replace("namespace s", "@sql(schema = \"shop\")\nnamespace s")
        val steps = plan(base, new)
        assertEquals(
            listOf(CreateSchema::class, SetSchema::class, DropSchema::class),
            steps.map { it::class },
        )
        assertEquals("shop", (steps[1] as SetSchema).to)
    }

    @Test
    fun `steps come in the planned order`() {
        val old =
            """
            namespace s
            record Customer {
              @sql(key) #1 id: uuid
              #2 name: string(max = 100)
              #3 note: string?
              #4 gone: int32
            }
            """
        val new =
            """
            namespace s
            record Customer {
              @sql(key) #1 id: uuid
              #2 full_name: string(max = 200)
              #3 note: string = ""
              #5 added: bool?
            }
            """
        val kinds = plan(old, new).map { it::class }
        assertEquals(
            listOf(
                RenameColumn::class,
                AddColumn::class,
                AlterColumnType::class,
                SetDefault::class,
                Backfill::class,
                SetNotNull::class,
                DropColumn::class,
            ),
            kinds,
        )
    }
}
