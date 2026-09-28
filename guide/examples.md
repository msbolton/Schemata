# Worked examples

Three example schemas live under `examples/`: `contacts`, `shop`, and `ledger`. Each has a
committed `expected/proto` tree, an `expected/sql` tree, and the warnings the compiler reports
for each target. Build the CLI once, then point it at any of them to reproduce what is shown
here:

```text
java -jar schemata-<version>.jar compile --out out examples/contacts
```

Swap `examples/contacts` for `examples/shop` or `examples/ledger` to compile the other two.

## contacts

A personal address book: one namespace, one record, one enum, and a handful of refined scalars.
It shows what Protobuf drops that Postgres keeps: a pattern on an email, bounds on an age, a
default on an enum.

From `examples/contacts/contacts.schemata`:
```schemata
/// A personal address book.
namespace contacts

enum Kind { #1 personal, #2 work }

/// One person. Email and age are checked by Postgres; Protobuf carries them unchecked.
record Contact {
  @sql(key)
  #1 id:    int64
  #2 name:  string(max = 100)
  #3 email: string(max = 254, pattern = "^[^@]+@[^@]+$")
  #4 age:   int32(min = 0, max = 150)?
  #5 kind:  Kind = personal
  #6 born:  date?
  #7 tags:  list<string(max = 20)>
}
```

```text
java -jar schemata-<version>.jar compile --out out examples/contacts
```

The enum widens with a synthesized zero value, and every refinement is gone:

```proto
enum Kind {
  KIND_UNSPECIFIED = 0;
  KIND_PERSONAL = 1;
  KIND_WORK = 2;
}
```

Postgres keeps the email pattern and the age bounds as `CHECK` constraints:

```sql
CONSTRAINT "ck_contact_email_max" CHECK (char_length("email") <= 254),
CONSTRAINT "ck_contact_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
```

Warnings from `examples/contacts/expected/proto-warnings.txt`:
```text
SCH2001 enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0
SCH2001 field 'Contact.email': refinements on string(max = 254, pattern = "^[^@]+@[^@]+$") are not enforced by Protobuf
SCH2001 field 'Contact.born': date has no Protobuf representation; lowered to string
```

Nothing is lost by the synthesized zero value; proto3 already reads an unset enum as 0, so keep
it. The `max` and `pattern` on `email` are gone in Protobuf; enforce the format in application
code, since Protobuf carries no constraints. `born` has no Protobuf date type, so it lowers to a
plain string; parse it back to a date in application code.

Warnings from `examples/contacts/expected/sql-warnings.txt`:
```text
SCH2105 field 'Contact.tags': refinements on list<string(max = 20)> are not enforced by Postgres
```

Postgres stores `tags` as a plain array with no per-element length check. Enforce the bound in
application code, or use `@sql(strategy = table)` so each tag becomes its own row with its own
constraint.

## shop

A storefront: customers in one namespace, orders in another that imports them. It shows a
cross-namespace reference, a payment union, an embedded record, and a deprecated field kept
alongside its replacement.

From `examples/shop/customers.schemata`:
```schemata
namespace shop.customers

record Customer {
  @sql(key) #1 id:   uuid
  #2 name: string(max = 100)
}
```

From `examples/shop/orders.schemata`:
```schemata
record Card         { #1 last4: string(max = 4)  #2 brand: string(max = 32) }
record BankTransfer { #1 iban: string(max = 34) }
record Cash         {}

union Payment = #1 Card | #2 BankTransfer | #3 Cash

/// A customer's order. One row per checkout.
record Order {
  @sql(key)
  #1 id:         uuid
  #2 customer:   Customer
  #3 status:     Status = pending
  #4 lines:      list<Line>(min = 1)
  #5 total:      Money
  #6 payment:    Payment
  @sql(strategy = embed)
  #7 shipping:   Address
  #8 placed_at:  instant
  #9 note:       string(max = 500)?
  @deprecated("use placed_at")
  #10 created:   instant?
  reserved #11, "legacy_ref"
```

```text
java -jar schemata-<version>.jar compile --out out examples/shop
```

The union lowers to a `oneof`, one field per arm:

```proto
message Payment {
  oneof kind {
    Card card = 1;
    BankTransfer bank_transfer = 2;
    Cash cash = 3;
  }
}
```

Postgres flattens the same union to a discriminator column and one `CHECK` per arm:

```sql
CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'bank_transfer', 'cash')),
CONSTRAINT "ck_order_payment_card" CHECK (("payment_kind" <> 'card') OR ("payment_card_last4" IS NOT NULL AND "payment_card_brand" IS NOT NULL)),
CONSTRAINT "ck_order_payment_bank_transfer" CHECK (("payment_kind" <> 'bank_transfer') OR ("payment_bank_transfer_iban" IS NOT NULL)),
```

Warnings from `examples/shop/expected/proto-warnings.txt`:
```text
SCH2001 field 'Customer.id': uuid has no Protobuf representation; lowered to string
SCH2001 field 'Order.status': default pending is not carried by proto3
SCH2001 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Protobuf
SCH2001 field 'Order.total': decimal has no Protobuf representation; lowered to string
```

`id` has no Protobuf uuid type, so it lowers to a plain string; parse it back to a uuid in
application code. proto3 has no field defaults, so `pending` is not carried; apply the default in
application code if you depend on it. The `min = 1` bound on `lines` is not enforced by Protobuf;
enforce it in application code. `total` has no Protobuf decimal type, so it lowers to a string;
parse it back to a decimal in application code.

Warnings from `examples/shop/expected/sql-warnings.txt`:
```text
SCH2105 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Postgres
```

The child table holding `lines` has no way to enforce a minimum row count. Enforce it in
application code; child tables carry no row-count constraints.

## ledger

A ledger split into three namespaces: a chart of accounts, a double-entry journal kept in its own
Postgres schema, and period closes that reference both. It shows a composite primary key, a map
lowered to a child table, and foreign keys that cross Postgres schemas.

From `examples/ledger/accounts.schemata`:
```schemata
/// An account, keyed by tenant and code.
@sql(key = (tenant_id, code))
record Account {
  #1 tenant_id: int64
  #2 code:      string(max = 16)
  #3 name:      string(max = 120)
  #4 kind:      Kind
  #5 opened:    date
  #6 closed:    date?
  #7 limits:    Limits
  @sql(strategy = table)
  #8 balances:  map<string, Money>
```

From `examples/ledger/journal.schemata`:
```schemata
/// Double-entry journal.
@sql(schema = "ledger_journal")
@proto(package = "ledger.journal.v1")
namespace ledger.journal
```

A namespace cannot share another namespace's `@sql(schema = ...)`; `ledger.journal` keeps its own,
`ledger_journal`, separate from `ledger.accounts`'s `ledger`.

From `examples/ledger/journal.schemata`:
```schemata
/// A balanced entry: at least two lines.
record Entry {
  @sql(key)
  #1 id:     uuid
  #2 posted: date
  #3 memo:   string(max = 500)?
  #4 lines:  list<Line>(min = 2)
  #5 ref:    Reference
  reserved #6
```

From `examples/ledger/reports.schemata`:
```schemata
/// Period closes, kept in their own schema.
@sql(schema = "ledger_reports")
@proto(package = "ledger.reports.v1")
namespace ledger.reports

import ledger.accounts
import ledger.journal

/// A close of one account for one period.
record Close {
  @sql(key)
  #1 id:      uuid
  #2 period:  string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$")
  #3 account: Account
  #4 last:    Entry?
  #5 totals:  Totals

  record Totals {
    #1 debits:  Money
    #2 credits: Money
  }
}
```

```text
java -jar schemata-<version>.jar compile --out out examples/ledger
```

`reports.proto` imports both other namespaces and references their types by full package path:

```proto
import "ledger/accounts.proto";
import "ledger/journal.proto";

// A close of one account for one period.
message Close {
  string id = 1;  // schemata: uuid
  string period = 2;  // schemata: string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$")
  .ledger.accounts.v1.Account account = 3;
  .ledger.journal.v1.Entry last = 4;
```

`reports.sql` carries that same reference as two foreign keys, one into each schema:

```sql
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code");
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_last" FOREIGN KEY ("last_id") REFERENCES "ledger_journal"."entry" ("id");
```

The first foreign key reaches into `ledger`, the second into `ledger_journal`: one row in
`ledger_reports.close` carries keys into two different Postgres schemas, because `account` comes
from `ledger.accounts` and `last` comes from `ledger.journal`.

Warnings from `examples/ledger/expected/proto-warnings.txt`:
```text
SCH2001 field 'Account.balances': decimal has no Protobuf representation; lowered to string
SCH2001 field 'Close.period': refinements on string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$") are not enforced by Protobuf
```

The map's decimal values lower to strings; parse them back to decimals in application code. The
year-month pattern on `period` is not enforced by Protobuf; enforce it in application code.

Warnings from `examples/ledger/expected/sql-warnings.txt`:
```text
SCH2105 field 'Entry.lines': refinements on list<Line>(min = 2) are not enforced by Postgres
```

The child table holding `lines` cannot enforce a minimum of two. Enforce it in application code;
child tables carry no row-count constraints.

Run `check --strict` on the whole directory:

```text
java -jar schemata-<version>.jar check --strict examples/ledger
```

Every warning above becomes an error, because `--strict` treats every warning as one. None of them
is SCH1014, though: every field and enum value across these three files already carries an
explicit ordinal, so `--strict`'s other job, catching an implicit one, finds nothing to report.

## Keeping the examples current

If you change one of these `.schemata` files, its `expected/` tree and warnings files need to
change with it. Run
`SCHEMATA_GOLDEN_UPDATE=1 ./gradlew :schemata-cli:test --tests 'io.schemata.cli.examples.ExamplesTest'`
to regenerate them, then read the diff before committing: every change should trace back to the
edit you made.
