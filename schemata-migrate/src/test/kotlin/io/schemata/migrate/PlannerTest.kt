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
        assertEquals(false, (steps[0] as DropConstraint).cascade)
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
        assertTrue(drop.cascade)
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
        val drop = steps.first { it is DropConstraint } as DropConstraint
        assertEquals("uq_customer_code", drop.name)
        assertTrue(drop.cascade)
    }

    @Test
    fun `a field retyped and made nullable re-adds its check as may-fail`() {
        val old =
            """
            namespace s
            enum Status { #1 a #2 b }
            record Item {
              @sql(key) #1 id: uuid
              #2 x: string
            }
            """
        val steps = plan(old, old.replace("#2 x: string", "#2 x: Status?"))
        val add = steps.filterIsInstance<AddConstraint>().single()
        assertEquals("ck_item_x_enum", add.constraint.name)
        assertEquals(Risk.MAY_FAIL, add.risk)
    }

    @Test
    fun `a retyped column drops its checks before the type change`() {
        val old =
            """
            namespace s
            record Item {
              @sql(key) #1 id: uuid
              #2 code: string(pattern = "^[a-z]+$")
            }
            """
        val steps = plan(old, old.replace(Regex("string\\(pattern = .*\\)"), "int32"))
        val drop = steps.indexOfFirst { it is DropConstraint && it.name.startsWith("ck_item_code") }
        val alter = steps.indexOfFirst { it is AlterColumnType }
        assertTrue(drop in 0 until alter, steps.toString())
        assertEquals(Risk.DESTRUCTIVE, steps[alter].risk)
    }

    @Test
    fun `a moved key drops the child's parent column and adds the new one`() {
        val old =
            """
            namespace s
            record Order {
              @sql(key) #1 id: uuid
              #2 code: string(max = 8)
              #3 lines: list<Line>
              record Line { #1 sku: string(max = 8) }
            }
            """
        val new =
            old.replace("@sql(key) #1 id: uuid", "#1 id: uuid")
                .replace("#2 code:", "@sql(key) #2 code:")
        val steps = plan(old, new)
        assertTrue(steps.none { it is AlterColumnType || it is RenameColumn }, steps.toString())
        val add = steps.filterIsInstance<AddColumn>().single()
        assertEquals("order_code", add.column.name)
        val drop = steps.filterIsInstance<DropColumn>().single()
        assertEquals("order_id", drop.column)
        assertEquals(Risk.DESTRUCTIVE, drop.risk)
        assertEquals(
            "populate \"order_lines\".\"order_code\" from the parent before the foreign keys return, then rerun with --allow-destructive",
            drop.help,
        )
        val fkDrop =
            steps.indexOfFirst { it is DropConstraint && it.name == "fk_order_lines_order" }
        val fkAdd =
            steps.indexOfFirst {
                it is AddConstraint &&
                    it.constraint is Constraint.Foreign &&
                    it.constraint.name == "fk_order_lines_order"
            }
        assertTrue(fkDrop in 0 until fkAdd, steps.toString())
        val fk = steps[fkAdd] as AddConstraint
        assertEquals(Risk.MAY_FAIL, fk.risk)
        assertEquals(
            "populate \"order_lines\".\"order_code\" from the parent before applying",
            fk.help,
        )
    }

    @Test
    fun `a renumbered field drops the old column before adding the new one`() {
        val steps = plan(base, base.replace("#3 note: string?", "#4 note: string?"))
        assertEquals(listOf(DropColumn::class, AddColumn::class), steps.map { it::class })
        assertEquals("note", (steps[0] as DropColumn).column)
        assertEquals("note", (steps[1] as AddColumn).column.name)
        assertEquals(Risk.DESTRUCTIVE, steps[0].risk)
    }

    @Test
    fun `two fields swapping names rename through a temporary name`() {
        val old =
            """
            namespace s
            record Pair {
              @sql(key) #3 id: uuid
              #1 a: int32
              #2 b: int32
            }
            """
        val new = old.replace("#1 a: int32", "#1 b: int32").replace("#2 b: int32", "#2 a: int32")
        val renames = plan(old, new).map { it as RenameColumn }
        assertEquals(
            listOf("a" to "a__schemata_tmp", "b" to "a", "a__schemata_tmp" to "b"),
            renames.map { it.from to it.to },
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
    fun `a union member's type change drops its variant columns and adds the new ones`() {
        val old =
            """
            namespace s
            record Card { #1 last4: string(max = 4) }
            record Voucher { #1 code: string(max = 16) }
            union Payment = #1 Card | #2 string
            record Order {
              @sql(key) #1 id: uuid
              #2 payment: Payment
              #3 card: Card?
              #4 voucher: Voucher?
            }
            """
        val new = old.replace("= #1 Card | #2 string", "= #1 Voucher | #2 string")
        val steps = plan(old, new)
        assertTrue(steps.none { it is RenameColumn || it is AlterColumnType }, steps.toString())
        val drop = steps.filterIsInstance<DropColumn>().single()
        assertEquals("payment_card_last4", drop.column)
        assertEquals(Risk.DESTRUCTIVE, drop.risk)
        assertEquals(
            "payment_voucher_code",
            steps.filterIsInstance<AddColumn>().single().column.name,
        )
    }

    @Test
    fun `an embedded record swapped for another is a drop and an add`() {
        val old =
            """
            namespace s
            record Address { #1 street: string(max = 50) }
            record Location { #1 street: string(max = 80) }
            record Order {
              @sql(key) #1 id: uuid
              #2 billing: Address
              #3 spare: Address?
              #4 other: Location?
            }
            """
        val new = old.replace("#2 billing: Address", "#2 billing: Location")
        val steps = plan(old, new)
        assertTrue(steps.none { it is RenameColumn || it is AlterColumnType }, steps.toString())
        val drop = steps.indexOfFirst { it is DropColumn && it.column == "billing_street" }
        val add = steps.indexOfFirst { it is AddColumn && it.column.name == "billing_street" }
        assertTrue(drop in 0 until add, steps.toString())
        assertEquals(Risk.DESTRUCTIVE, steps[drop].risk)
        assertEquals(DESTRUCTIVE_HELP, steps[drop].help)
        assertEquals(ColumnType.VARCHAR(80), (steps[add] as AddColumn).column.type)
    }

    @Test
    fun `a list element record swapped drops the child table`() {
        val old =
            """
            namespace s
            record Line { #1 sku: string(max = 8) }
            record Item { #1 sku: string(max = 16) }
            record Order {
              @sql(key) #1 id: uuid
              #2 lines: list<Line>
              #3 line: Line?
              #4 item: Item?
            }
            """
        val new = old.replace("list<Line>", "list<Item>")
        val steps = plan(old, new)
        val drop = steps.indexOfFirst { it is DropTable && it.at.table == "order_lines" }
        val create = steps.indexOfFirst { it is CreateTable && it.table.name == "order_lines" }
        assertTrue(drop in 0 until create, steps.toString())
        assertEquals(Risk.DESTRUCTIVE, steps[drop].risk)
        assertTrue(steps.none { it is AlterColumnType || it is RenameTable }, steps.toString())
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

    @Test
    fun `a retyped column with a default drops it before the type change and sets the new one after`() {
        val old = base.replace("#3 note: string?", "#3 retries: string = \"3\"")
        val new = base.replace("#3 note: string?", "#3 retries: int32 = 3")
        val steps = plan(old, new)
        assertEquals(
            listOf(DropDefault::class, AlterColumnType::class, SetDefault::class),
            steps.map { it::class },
        )
        assertEquals("3", (steps[2] as SetDefault).default)
        assertEquals(
            "values that do not fit integer (the cast fails or truncates)",
            steps[1].reason,
        )
    }

    @Test
    fun `a type override is judged by the type it spells`() {
        val old = base.replace("#3 note: string?", "@sql(type = \"integer\") #3 n: int32")
        val new = old.replace("\"integer\"", "\"bigint\"")
        assertEquals(Risk.CLEAN, (plan(old, new).single() as AlterColumnType).risk)
        assertEquals(Risk.DESTRUCTIVE, (plan(new, old).single() as AlterColumnType).risk)
    }

    @Test
    fun `two tables swapping names route their keys through a temporary name`() {
        val old =
            """
            namespace s
            record A { @sql(key) #1 id: uuid }
            record B { @sql(key) #1 id: uuid }
            """
        val new =
            old.replace("record A", "@sql(table = \"b\") record A")
                .replace("record B", "@sql(table = \"a\") record B")
        val keys = plan(old, new).filterIsInstance<RenameConstraint>().map { it.from to it.to }
        assertEquals(
            listOf(
                "pk_a" to "pk_a__schemata_tmp",
                "pk_b" to "pk_a",
                "pk_a__schemata_tmp" to "pk_b",
            ),
            keys,
        )
    }

    @Test
    fun `a renamed table's key moves out of the way before a new table takes its name`() {
        val old =
            """
            namespace s
            record Order { @sql(key) #1 id: uuid }
            """
        val new =
            """
            namespace s
            @sql(table = "purchase") record Order { @sql(key) #1 id: uuid }
            record Basket { @sql(key) #1 id: uuid }
            """
                .replace("record Basket", "@sql(table = \"order\") record Basket")
        val steps = plan(old, new)
        val rename = steps.indexOfFirst { it is RenameConstraint && it.from == "pk_order" }
        val create = steps.indexOfFirst { it is CreateTable && it.table.name == "order" }
        assertTrue(rename in 0 until create, steps.toString())
    }

    @Test
    fun `a key widened in place is re-added clean with its foreign keys`() {
        val old =
            """
            namespace s
            record Order {
              @sql(key) #1 id: int32
              #2 lines: list<Line>
              record Line { #1 sku: string(max = 8) }
            }
            """
        val steps = plan(old, old.replace("#1 id: int32", "#1 id: int64"))
        val adds = steps.filterIsInstance<AddConstraint>()
        assertEquals(3, adds.size, steps.toString())
        assertTrue(adds.all { it.risk == Risk.CLEAN }, adds.toString())
        val narrowed = plan(old.replace("int32", "int64"), old)
        val fk =
            narrowed.filterIsInstance<AddConstraint>().single {
                it.constraint is Constraint.Foreign
            }
        assertEquals(Risk.MAY_FAIL, fk.risk)
        assertEquals("fix or delete the rows whose parent is missing before applying", fk.help)
    }

    @Test
    fun `a strategy change sets the new shape not null after the old shape is dropped`() {
        val old =
            """
            namespace s
            record Address { #1 street: string(max = 50) }
            record Order {
              @sql(key) #1 id: uuid
              #2 billing: Address
            }
            """
        val new = old.replace("#2 billing:", "@sql(strategy = json) #2 billing:")
        val steps = plan(old, new)
        val drop = steps.indexOfFirst { it is DropColumn }
        val notNull = steps.indexOfFirst { it is SetNotNull }
        assertTrue(drop in 0 until notNull, steps.toString())
    }

    @Test
    fun `a renamed enum value is rewritten before its check returns and is clean`() {
        val old =
            """
            namespace s
            enum Status { #1 pending, #2 paid }
            record Order {
              @sql(key) #1 id: uuid
              #2 status: Status
            }
            """
        val steps = plan(old, old.replace("#2 paid", "#2 settled"))
        assertEquals(
            listOf(DropConstraint::class, RenameValue::class, AddConstraint::class),
            steps.map { it::class },
        )
        val rename = steps[1] as RenameValue
        assertEquals(
            Triple("status", "paid", "settled"),
            Triple(rename.column, rename.from, rename.to),
        )
        assertTrue(steps.all { it.risk == Risk.CLEAN }, steps.toString())
    }

    @Test
    fun `a foreign key between two files is dropped by the earlier file`() {
        val b =
            """
            namespace b
            record Customer { @sql(key) #1 id: uuid }
            """
        val a =
            """
            namespace a
            import b
            record Order {
              @sql(key) #1 id: uuid
              #2 buyer: Customer
            }
            """
        val migration =
            Planner.plan(
                side(mapOf("a.schemata" to a, "b.schemata" to b)),
                side(mapOf("a.schemata" to a, "b.schemata" to b.replace("id: uuid", "id: string"))),
            )
        val files = migration.namespaces.associate { it.path to it.steps }
        val drop = files.getValue("a.sql").filterIsInstance<DropConstraint>().single()
        assertEquals("fk_order_buyer", drop.name)
        assertTrue(drop.ifExists)
        val alter = files.getValue("a.sql").indexOfFirst { it is AlterColumnType }
        assertTrue(files.getValue("a.sql").indexOf(drop) < alter)
        assertTrue(
            files.getValue("b.sql").none { it is DropConstraint && it.name == "fk_order_buyer" }
        )
        assertTrue(
            files.getValue("b.sql").any {
                it is AddConstraint && it.constraint.name == "fk_order_buyer"
            }
        )
    }
}
