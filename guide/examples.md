# Worked examples

Three example schemas live under `examples/`: `contacts`, `shop`, and `ledger`. Each has a
committed `expected/proto` tree, an `expected/sql` tree, an `expected/xsd` tree, an
`expected/jsonschema` tree, and the warnings the compiler reports for each target. Build the CLI
once, then point it at any of them to reproduce what is shown here:

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
  @sql(key) #1 id:    int64
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

The enum widens with a synthesized zero value, and every refinement is gone.

From `examples/contacts/expected/proto/contacts.proto`:
```proto
enum Kind {
  KIND_UNSPECIFIED = 0;
  KIND_PERSONAL = 1;
  KIND_WORK = 2;
}
```

Postgres keeps the email pattern and the age bounds as `CHECK` constraints.

From `examples/contacts/expected/sql/contacts.sql`:
```sql
  CONSTRAINT "ck_contact_email_max" CHECK (char_length("email") <= 254),
  CONSTRAINT "ck_contact_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
  CONSTRAINT "ck_contact_age_min" CHECK ("age" >= 0),
  CONSTRAINT "ck_contact_age_max" CHECK ("age" <= 150),
```

Warnings from `examples/contacts/expected/proto-warnings.txt`:
```text
SCH2001 enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0
SCH2001 field 'Contact.name': refinements on string(max = 100) are not enforced by Protobuf
SCH2001 field 'Contact.email': refinements on string(max = 254, pattern = "^[^@]+@[^@]+$") are not enforced by Protobuf
SCH2001 field 'Contact.age': refinements on int32(min = 0, max = 150) are not enforced by Protobuf
SCH2001 field 'Contact.kind': default KIND_PERSONAL is not carried by proto3
SCH2001 field 'Contact.born': date has no Protobuf representation; lowered to string
SCH2001 field 'Contact.tags': refinements on list<string(max = 20)> are not enforced by Protobuf
```

Nothing is lost by the synthesized zero value; proto3 already reads an unset enum as 0, so keep
it. Every refinement on `name`, `email`, `age`, and `tags` is dropped the same way; enforce the
format and bounds in application code, since Protobuf carries no constraints. The default on `kind`
is dropped too; proto3 has no field defaults, so apply `personal` yourself if you rely on it.
`born` has no Protobuf date type, so it lowers to a plain string; parse it back to a date in
application code.

Warnings from `examples/contacts/expected/sql-warnings.txt`:
```text
SCH2105 field 'Contact.tags': refinements on list<string(max = 20)> are not enforced by Postgres
```

Postgres stores `tags` as a plain array with no per-element length check. Enforce the bound in
application code, or use `@sql(strategy = table)` so each tag becomes its own row with its own
constraint.

XSD keeps the email pattern too, as a facet on a restriction rather than a `CHECK` constraint.

From `examples/contacts/expected/xsd/contacts.xsd`:
```xml
      <xs:element name="email">
        <xs:simpleType>
          <xs:restriction base="xs:string">
            <xs:maxLength value="254"/>
            <xs:pattern value="[^@]+@[^@]+"/>
          </xs:restriction>
        </xs:simpleType>
      </xs:element>
```

JSON Schema keeps it as well, as `pattern` on the string, unchanged, since both dialects match
anywhere in the value.

From `examples/contacts/expected/jsonschema/contacts.schema.json`:
```json
        "email": {
          "type": "string",
          "pattern": "^[^@]+@[^@]+$",
          "maxLength": 254
        },
```

## shop

A storefront: customers in one namespace, orders in another that imports them. It shows a
cross-namespace reference, a payment union, an embedded record, and a deprecated field kept
alongside its replacement.

From `examples/shop/customers.schemata`:
```schemata
namespace shop.customers

record Customer { @sql(key) #1 id: uuid #2 name: string(max = 100) }
```

From `examples/shop/orders.schemata`:
```schemata
import shop.customers

alias Email = string(max = 254, pattern = "^[^@]+@[^@]+$")

alias Money = decimal(19, 4)

enum Status { #1 pending, #2 paid, #3 shipped, #4 cancelled }

record Card { #1 last4: string(max = 4) #2 brand: string(max = 32) }

record BankTransfer { #1 iban: string(max = 34) }

record Cash {}

union Payment = #1 Card | #2 BankTransfer | #3 Cash

/// A customer's order. One row per checkout.
record Order {
  @sql(key) #1  id:        uuid
  #2  customer:  Customer
  #3  status:    Status = pending
  #4  lines:     list<Line>(min = 1)
  #5  total:     Money
  #6  payment:   Payment
  @sql(strategy = embed) #7  shipping:  Address
  #8  placed_at: instant
  #9  note:      string(max = 500)?
  @deprecated("use placed_at") #10 created:   instant?
  reserved #11, "legacy_ref"
```

```text
java -jar schemata-<version>.jar compile --out out examples/shop
```

The union lowers to a `oneof`, one field per arm.

From `examples/shop/expected/proto/shop/orders.proto`:
```proto
message Payment {
  oneof kind {
    Card card = 1;
    BankTransfer bank_transfer = 2;
    Cash cash = 3;
  }
}
```

Postgres flattens the same union to a discriminator column and a `CHECK` for each arm that has
fields; `Cash` has no fields, so it gets none.

From `examples/shop/expected/sql/shop/orders.sql`:
```sql
  CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'bank_transfer', 'cash')),
  CONSTRAINT "ck_order_payment_card" CHECK (("payment_kind" <> 'card') OR ("payment_card_last4" IS NOT NULL AND "payment_card_brand" IS NOT NULL)),
  CONSTRAINT "ck_order_payment_bank_transfer" CHECK (("payment_kind" <> 'bank_transfer') OR ("payment_bank_transfer_iban" IS NOT NULL)),
```

Warnings from `examples/shop/expected/proto-warnings.txt`:
```text
SCH2001 field 'Customer.id': uuid has no Protobuf representation; lowered to string
SCH2001 field 'Customer.name': refinements on string(max = 100) are not enforced by Protobuf
SCH2001 enum 'Status': proto3 requires a zero value; synthesized STATUS_UNSPECIFIED = 0
SCH2001 field 'Card.last4': refinements on string(max = 4) are not enforced by Protobuf
SCH2001 field 'Card.brand': refinements on string(max = 32) are not enforced by Protobuf
SCH2001 field 'BankTransfer.iban': refinements on string(max = 34) are not enforced by Protobuf
SCH2001 field 'Order.status': default STATUS_PENDING is not carried by proto3
SCH2001 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Protobuf
SCH2001 field 'Order.total': decimal has no Protobuf representation; lowered to string
SCH2001 field 'Order.note': refinements on string(max = 500) are not enforced by Protobuf
SCH2001 field 'Line.sku': refinements on string(max = 64) are not enforced by Protobuf
SCH2001 field 'Line.quantity': refinements on int32(min = 1) are not enforced by Protobuf
SCH2001 field 'Address.street': refinements on string(max = 200) are not enforced by Protobuf
SCH2001 field 'Address.country': refinements on string(min = 2, max = 2) are not enforced by Protobuf
```

`id` has no Protobuf uuid type, so it lowers to a plain string; parse it back to a uuid in
application code. The `Status` enum gets a synthesized zero value, the same as `Kind` did in
`contacts`. proto3 has no field defaults, so `pending` is not carried; apply the default in
application code if you depend on it. The `min = 1` bound on `lines` is not enforced by Protobuf;
enforce it in application code. `total` has no Protobuf decimal type, so it lowers to a string;
parse it back to a decimal in application code. Every refinement on `Customer.name`, `Card`,
`BankTransfer`, `Order.note`, `Line`, and `Address` is dropped the same way `email` and `age` were
dropped in `contacts`; enforce them in application code.

Warnings from `examples/shop/expected/sql-warnings.txt`:
```text
SCH2105 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Postgres
```

The child table holding `lines` has no way to enforce a minimum row count. Enforce it in
application code; child tables carry no row-count constraints.

`orders.xsd` imports `customers.xsd` for the cross-namespace `customer` reference.

From `examples/shop/expected/xsd/shop/orders.xsd`:
```xml
  <xs:import namespace="urn:schemata:shop.customers" schemaLocation="customers.xsd"/>
```

JSON Schema references `Customer` the same way, but the `$ref` is the absolute `$id` of the
`shop.customers` document, since references across documents cannot be relative.

From `examples/shop/expected/jsonschema/shop/orders.schema.json`:
```json
        "customer": {
          "$ref": "urn:schemata:shop.customers#/$defs/Customer"
        },
```

The `Payment` union lowers to a `oneOf`, one single-property closed object per arm, tagged by the
member's name; here is the `card` arm.

From `examples/shop/expected/jsonschema/shop/orders.schema.json`:
```json
        {
          "type": "object",
          "properties": {
            "card": {
              "$ref": "#/$defs/Card"
            }
          },
          "required": [
            "card"
          ],
          "additionalProperties": false
        },
```

The union lowers to a complexType holding an `xs:choice`, one element per arm.

From `examples/shop/expected/xsd/shop/orders.xsd`:
```xml
  <xs:complexType name="PaymentType">
    <xs:choice>
      <xs:element name="card" type="tns:CardType"/>
      <xs:element name="bank_transfer" type="tns:BankTransferType"/>
      <xs:element name="cash" type="tns:CashType"/>
    </xs:choice>
  </xs:complexType>
```

`examples/shop/expected/xsd-sample.xml` is a hand-written document that validates against
`orders.xsd`, payment and all. `examples/shop/expected/jsonschema-sample.json` is the same order
as JSON; it validates against `urn:schemata:shop.orders#/$defs/Order`.

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
  @sql(strategy = table) #8 balances:  map<string, Money>
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
  @sql(key) #1 id:     uuid
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
  @sql(key) #1 id:      uuid
  #2 period:  string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$")
  #3 account: Account
  #4 last:    Entry?
  #5 totals:  Totals

  record Totals { #1 debits: Money #2 credits: Money }
}
```

```text
java -jar schemata-<version>.jar compile --out out examples/ledger
```

`reports.proto` imports both other namespaces and references their types by full package path.

From `examples/ledger/expected/proto/ledger/reports.proto`:
```proto
import "ledger/accounts.proto";
import "ledger/journal.proto";

// A close of one account for one period.
message Close {
  string id = 1;  // schemata: uuid
  string period = 2;  // schemata: string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$")
  .ledger.accounts.v1.Account account = 3;
  .ledger.journal.v1.Entry last = 4;  // schemata: Entry?
```

`reports.sql` carries that same reference as two foreign keys, one into each schema.

From `examples/ledger/expected/sql/ledger/reports.sql`:
```sql
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code");
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_last" FOREIGN KEY ("last_id") REFERENCES "ledger_journal"."entry" ("id");
```

The first foreign key reaches into `ledger`, the second into `ledger_journal`: one row in
`ledger_reports.close` carries keys into two different Postgres schemas, because `account` comes
from `ledger.accounts` and `last` comes from `ledger.journal`.

`reports.xsd` imports both other namespaces too, one `xs:import` per schema, the same two
namespaces `account` and `last` reach into.

From `examples/ledger/expected/xsd/ledger/reports.xsd`:
```xml
  <xs:import namespace="urn:schemata:ledger.accounts" schemaLocation="accounts.xsd"/>
  <xs:import namespace="urn:schemata:ledger.journal" schemaLocation="journal.xsd"/>
```

Warnings from `examples/ledger/expected/proto-warnings.txt`:
```text
SCH2001 enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0
SCH2001 field 'Account.code': refinements on string(max = 16) are not enforced by Protobuf
SCH2001 field 'Account.name': refinements on string(max = 120) are not enforced by Protobuf
SCH2001 field 'Account.opened': date has no Protobuf representation; lowered to string
SCH2001 field 'Account.balances': decimal has no Protobuf representation; lowered to string
SCH2001 enum 'Source': proto3 requires a zero value; synthesized SOURCE_UNSPECIFIED = 0
SCH2001 enum 'Side': proto3 requires a zero value; synthesized SIDE_UNSPECIFIED = 0
SCH2001 field 'Invoice.number': refinements on string(max = 32) are not enforced by Protobuf
SCH2001 field 'Payment.reference': refinements on string(max = 64) are not enforced by Protobuf
SCH2001 field 'Entry.id': uuid has no Protobuf representation; lowered to string
SCH2001 field 'Entry.memo': refinements on string(max = 500) are not enforced by Protobuf
SCH2001 field 'Entry.lines': refinements on list<Line>(min = 2) are not enforced by Protobuf
SCH2001 field 'Close.period': refinements on string(max = 7, pattern = "^[0-9]{4}-[0-9]{2}$") are not enforced by Protobuf
```

The `Kind`, `Source`, and `Side` enums each get a synthesized zero value, the same as `Kind` did in
`contacts`. Every refinement, on `Account.code`, `Account.name`, `Invoice.number`, and
`Payment.reference`, is dropped the same way `email` and `age` were dropped in `contacts`; enforce
them in application code. `Account.opened` and `Entry.id` have no Protobuf date or uuid type, so
they lower to plain strings; parse them back in application code. `Entry.memo`'s max is dropped
too. The map's decimal values lower to strings; parse them back to decimals in application code.
The `min = 2` bound on `Entry.lines` and the year-month pattern on `Close.period` are not enforced
by Protobuf; enforce them in application code.

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

## GPX

`schemata import --from xsd` goes the other way: it reads an existing `.xsd` and writes a
`.schemata` file. The GPX 1.1 schema (`http://www.topografix.com/GPX/1/1`) is a good one to walk
through, since it exercises most of what the importer does: a namespace that is not
`urn:schemata:…`, a decimal with no declared precision, a fixed attribute value, and an `xs:any` it
cannot carry.

```text
java -jar schemata-<version>.jar import --from xsd --out out gpx.xsd
```

GPX's `targetNamespace` is a plain URI, not `urn:schemata:…`, so the output keeps it on the
namespace as `@xsd(namespace = "…")` and takes its own namespace from the file name, `gpx`,
reported once (SCH2402); pass `--namespace gpx` yourself to silence that note. The root complex
type, `gpxType`, becomes `record Gpx`, with `@xsd(name = "gpx")` restoring the element name XSD
expects back.

From `schemata-cli/src/test/resources/import/gpx/expected/gpx.schemata`:
```
/// GPX schema version 1.1 - For more information on GPX and this schema, visit http://www.topografix.com/gpx.asp
///
/// GPX uses the following conventions: all coordinates are relative to the WGS84 datum.  All measurements are in metric units.
@xsd(namespace = "http://www.topografix.com/GPX/1/1")
namespace gpx

/// GPX documents contain a metadata header, followed by waypoints, routes, and tracks.  You can add your own elements
/// to the extensions section of the GPX document.
@xsd(name = "gpx")
record Gpx {
  /// Metadata about the file.
  metadata:   Metadata?
  /// A list of waypoints.
  wpt:        list<Wpt>
  /// A list of routes.
  rte:        list<Rte>
  /// A list of tracks.
  trk:        list<Trk>
  /// You can add extend GPX by adding your own elements from another schema here.
  extensions: Extensions?
```

Every warning is lossy; none stops the import from writing its file.

Warnings from `schemata-cli/src/test/resources/import/gpx/expected/import-warnings.txt`:
```
SCH2402 gpx.xsd: namespace 'gpx' was derived from the file name
SCH2405 attribute 'version': fixed value imported as a default
SCH2403 element 'ele': decimal without totalDigits and fractionDigits imported as decimal(38, 9)
SCH2403 element 'magvar': decimal without totalDigits and fractionDigits imported as decimal(38, 9)
SCH2404 element 'magvar': facet maxExclusive dropped
```

Besides the namespace note already covered, `version`'s `fixed="1.1"` becomes a plain default
(SCH2405); `ele` and the other coordinates have no `totalDigits`/`fractionDigits`, so they import as
`decimal(38, 9)` (SCH2403); and `magvar`'s `maxExclusive` has no equivalent on a decimal and is
dropped (SCH2404). Compiling `out/import/gpx.schemata` under the sql target reports SCH2106 on
every record, since the import never adds `@sql(key)`; add keys by hand before compiling to SQL.
The proto, xsd, and jsonschema targets compile it as it stands. More generally, compiling an
import's own `.schemata` output under the xsd target and importing that result again regenerates
it byte for byte, with no diagnostics at all.

## Keeping the examples current

If you change one of these `.schemata` files, its `expected/` tree and warnings files need to
change with it. Run
`SCHEMATA_GOLDEN_UPDATE=1 ./gradlew :schemata-cli:test --tests 'io.schemata.cli.examples.ExamplesTest'`
to regenerate them, then read the diff before committing: every change should trace back to the
edit you made. `examples/shop/expected/xsd-sample.xml` is not regenerated this way; it is
hand-written, and the same test run validates it against the regenerated `orders.xsd`, so update it
by hand if a change to `shop` would make it invalid.
