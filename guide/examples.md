# Worked examples

Four example schemas live under `examples/`: `contacts`, `shop`, `ledger`, and `services`. Each
has a committed `expected/proto` tree, an `expected/sql` tree, an `expected/xsd` tree, an
`expected/jsonschema` tree, and the warnings the compiler reports for each target; `services`,
the one that declares a service, has an `expected/openapi` tree too. Build the CLI once, then point
it at any of them to reproduce what is shown here:

```text
java -jar schemata-<version>.jar compile --out out examples/contacts
```

Swap `examples/contacts` for `examples/shop`, `examples/ledger`, or `examples/services` to compile
the others.

## contacts

A personal address book: one schema, one model, one enum, and a handful of refined scalars.
It shows what Protobuf drops that Postgres keeps: a pattern on an email, bounds on an age, a
default on an enum.

From `examples/contacts/contacts.schemata`:
```schemata
/// A personal address book.
schema contacts

enum Kind { personal work }

/// One person. Email and age are checked by Postgres; Protobuf carries them unchecked.
model Contact {
  id    int64    { id }
  name  string   { max 100 }
  email string   { max 254, match "^[^@]+@[^@]+$" }
  age   int32?   { min 0, max 150 }
  kind  Kind     = personal
  born  date?
  tags  string[] { max 20 }
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
SCH2001 field 'Contact.name': refinements on string { max 100 } are not enforced by Protobuf
SCH2001 field 'Contact.email': refinements on string { max 254, match "^[^@]+@[^@]+$" } are not enforced by Protobuf
SCH2001 field 'Contact.age': refinements on int32 { min 0, max 150 } are not enforced by Protobuf
SCH2001 field 'Contact.kind': default KIND_PERSONAL is not carried by proto3
SCH2001 field 'Contact.born': date has no Protobuf representation; lowered to string
SCH2001 field 'Contact.tags': refinements on string[] { max 20 } are not enforced by Protobuf
```

Nothing is lost by the synthesized zero value; proto3 already reads an unset enum as 0, so keep
it. Every refinement on `name`, `email`, `age`, and `tags` is dropped the same way; enforce the
format and bounds in application code, since Protobuf carries no constraints. The default on `kind`
is dropped too; proto3 has no field defaults, so apply `personal` yourself if you rely on it.
`born` has no Protobuf date type, so it lowers to a plain string; parse it back to a date in
application code.

Warnings from `examples/contacts/expected/sql-warnings.txt`:
```text
SCH2105 field 'Contact.tags': refinements on string[] { max 20 } are not enforced by Postgres
```

Postgres stores `tags` as a plain array with no per-element length check. Enforce the bound in
application code, or use `@sql(strategy: table)` so each tag becomes its own row with its own
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

A storefront: customers in one schema, orders in another that imports them. It shows a
cross-schema reference, a payment union, an embedded model, and a deprecated field kept
alongside its replacement.

From `examples/shop/customers.schemata`:
```schemata
schema shop.customers

model Customer { id uuid { id }  name string { max 100 } }
```

From `examples/shop/orders.schemata`:
```schemata
import shop.customers

alias Email = string { max 254, match "^[^@]+@[^@]+$" }

alias Money = decimal(19, 4)

enum Status { pending paid shipped cancelled }

model Card { last4 string { max 4 }  brand string { max 32 } }

model BankTransfer { iban string { max 34 } }

model Cash {}

union Payment = Card | BankTransfer | Cash

/// A customer's order. One row per checkout.
model Order {
  id        uuid     { id }
  customer  Customer
  status    Status   = pending
  lines     Line[]   { minItems 1 }
  total     Money
  payment   Payment
  shipping  Address  { embed }
  placed_at instant
  note      string?  { max 500 }
  created   instant? @deprecated("use placed_at")
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
SCH2001 field 'Customer.name': refinements on string { max 100 } are not enforced by Protobuf
SCH2001 enum 'Status': proto3 requires a zero value; synthesized STATUS_UNSPECIFIED = 0
SCH2001 field 'Card.last4': refinements on string { max 4 } are not enforced by Protobuf
SCH2001 field 'Card.brand': refinements on string { max 32 } are not enforced by Protobuf
SCH2001 field 'BankTransfer.iban': refinements on string { max 34 } are not enforced by Protobuf
SCH2001 field 'Order.status': default STATUS_PENDING is not carried by proto3
SCH2001 field 'Order.lines': refinements on Line[] { minItems 1 } are not enforced by Protobuf
SCH2001 field 'Order.total': decimal has no Protobuf representation; lowered to string
SCH2001 field 'Order.note': refinements on string { max 500 } are not enforced by Protobuf
SCH2001 field 'Line.sku': refinements on string { max 64 } are not enforced by Protobuf
SCH2001 field 'Line.quantity': refinements on int32 { min 1 } are not enforced by Protobuf
SCH2001 field 'Address.street': refinements on string { max 200 } are not enforced by Protobuf
SCH2001 field 'Address.country': refinements on string { min 2, max 2 } are not enforced by Protobuf
```

`id` has no Protobuf uuid type, so it lowers to a plain string; parse it back to a uuid in
application code. The `Status` enum gets a synthesized zero value, the same as `Kind` did in
`contacts`. proto3 has no field defaults, so `pending` is not carried; apply the default in
application code if you depend on it. The `minItems 1` bound on `lines` is not enforced by Protobuf;
enforce it in application code. `total` has no Protobuf decimal type, so it lowers to a string;
parse it back to a decimal in application code. Every refinement on `Customer.name`, `Card`,
`BankTransfer`, `Order.note`, `Line`, and `Address` is dropped the same way `email` and `age` were
dropped in `contacts`; enforce them in application code.

Warnings from `examples/shop/expected/sql-warnings.txt`:
```text
SCH2105 field 'Order.lines': refinements on Line[] { minItems 1 } are not enforced by Postgres
```

The child table holding `lines` has no way to enforce a minimum row count. Enforce it in
application code; child tables carry no row-count constraints.

`Customer` is keyed by `id`, so `customer` is a reference: an order carries the customer's key,
not a copy of the customer. Every target spells it the way Postgres does, as one field named
`customer_id` typed like `Customer.id`. `customer Customer { embed }` would copy the whole model
into the Protobuf, XSD, JSON Schema, and OpenAPI outputs instead, as 1.x did, and is an error for
Postgres, which never copies a keyed model into another table.

From `examples/shop/expected/proto/shop/orders.proto`:
```proto
  string customer_id = 2;  // schemata: uuid
```

From `examples/shop/expected/sql/shop/orders.sql`:
```sql
  "customer_id" uuid NOT NULL,
```

From `examples/shop/expected/jsonschema/shop/orders.schema.json`:
```json
        "customer_id": {
          "type": "string",
          "format": "uuid",
          "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        },
```

Since nothing in `orders` holds a `Customer` any more, neither `orders.proto` nor `orders.xsd`
imports `customers`.

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

A ledger split into three schemas: a chart of accounts, a double-entry journal kept in its own
Postgres schema, and period closes that reference both. It shows a composite primary key, a map
lowered to a child table, and foreign keys that cross Postgres schemas.

From `examples/ledger/accounts.schemata`:
```schemata
/// An account, keyed by tenant and code.
model Account {
  #1 tenant_id int64
  #2 code      string             { max 16 }
  #3 name      string             { max 120 }
  #4 kind      Kind
  #5 opened    date
  #6 closed    date?
  #7 limits    Limits
  #8 balances  map<string, Money> @sql(strategy: table)

  /// Overdraft and daily limits in the account's currency.
  model Limits { #1 overdraft Money  #2 daily Money? }

  @@id(tenant_id, code)
}
```

From `examples/ledger/journal.schemata`:
```schemata
/// Double-entry journal.
schema ledger.journal @sql(schema: "ledger_journal") @proto(package: "ledger.journal.v1")
```

A schema cannot share another schema's `@sql(schema: ...)`; `ledger.journal` keeps its own,
`ledger_journal`, separate from `ledger.accounts`'s `ledger`.

From `examples/ledger/journal.schemata`:
```schemata
/// A balanced entry: at least two lines.
model Entry {
  #1 id     uuid      { id }
  #2 posted date
  #3 memo   string?   { max 500 }
  #4 lines  Line[]    { minItems 2 }
  #5 ref    Reference
  reserved #6
```

From `examples/ledger/reports.schemata`:
```schemata
/// Period closes, kept in their own schema.
schema ledger.reports @sql(schema: "ledger_reports") @proto(package: "ledger.reports.v1")

import ledger.accounts
import ledger.journal

/// A close of one account for one period.
model Close {
  #1 id      uuid    { id }
  #2 period  string  { max 7, match "^[0-9]{4}-[0-9]{2}$" }
  #3 account Account
  #4 last    Entry?
  #5 totals  Totals

  model Totals { #1 debits Money  #2 credits Money }
}
```

```text
java -jar schemata-<version>.jar compile --out out examples/ledger
```

`Account` has a composite key, so `account` carries both of its key fields in one `AccountKey`
message, declared beside `Account` in `accounts.proto`. `last` references `Entry`, keyed by one
uuid, so it is `last_id`. Nothing from `ledger.journal` is copied in, so only `accounts.proto` is
imported.

From `examples/ledger/expected/proto/ledger/reports.proto`:
```proto
import "ledger/accounts.proto";

// A close of one account for one period.
message Close {
  string id = 1;  // schemata: uuid
  string period = 2;  // schemata: string { max 7, match "^[0-9]{4}-[0-9]{2}$" }
  .ledger.accounts.v1.AccountKey account = 3;
  optional string last_id = 4;  // schemata: uuid?
```

From `examples/ledger/expected/proto/ledger/accounts.proto`:
```proto
message AccountKey {
  int64 tenant_id = 1;
  string code = 2;  // schemata: string { max 16 }
}
```

`reports.sql` carries the same two references as two foreign keys, one into each schema.

From `examples/ledger/expected/sql/ledger/reports.sql`:
```sql
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code");
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_last" FOREIGN KEY ("last_id") REFERENCES "ledger_journal"."entry" ("id");
```

The first foreign key reaches into `ledger`, the second into `ledger_journal`: one row in
`ledger_reports.close` carries keys into two different Postgres schemas, because `account` comes
from `ledger.accounts` and `last` comes from `ledger.journal`.

`reports.xsd` follows suit: `account` is an `AccountKeyType` from `accounts.xsd`, the one schema
it imports, and `last_id` is a uuid-patterned string.

From `examples/ledger/expected/xsd/ledger/reports.xsd`:
```xml
  <xs:import namespace="urn:schemata:ledger.accounts" schemaLocation="accounts.xsd"/>
```

From `examples/ledger/expected/xsd/ledger/reports.xsd`:
```xml
      <xs:element name="account" type="ns1:AccountKeyType"/>
      <xs:element name="last_id" minOccurs="0">
```

Warnings from `examples/ledger/expected/proto-warnings.txt`:
```text
SCH2001 enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0
SCH2001 field 'Account.code': refinements on string { max 16 } are not enforced by Protobuf
SCH2001 field 'Account.name': refinements on string { max 120 } are not enforced by Protobuf
SCH2001 field 'Account.opened': date has no Protobuf representation; lowered to string
SCH2001 field 'Account.balances': decimal has no Protobuf representation; lowered to string
SCH2001 enum 'Source': proto3 requires a zero value; synthesized SOURCE_UNSPECIFIED = 0
SCH2001 enum 'Side': proto3 requires a zero value; synthesized SIDE_UNSPECIFIED = 0
SCH2001 field 'Invoice.number': refinements on string { max 32 } are not enforced by Protobuf
SCH2001 field 'Payment.reference': refinements on string { max 64 } are not enforced by Protobuf
SCH2001 field 'Entry.id': uuid has no Protobuf representation; lowered to string
SCH2001 field 'Entry.memo': refinements on string { max 500 } are not enforced by Protobuf
SCH2001 field 'Entry.lines': refinements on Line[] { minItems 2 } are not enforced by Protobuf
SCH2001 field 'Close.period': refinements on string { max 7, match "^[0-9]{4}-[0-9]{2}$" } are not enforced by Protobuf
```

The `Kind`, `Source`, and `Side` enums each get a synthesized zero value, the same as `Kind` did in
`contacts`. Every refinement, on `Account.code`, `Account.name`, `Invoice.number`, and
`Payment.reference`, is dropped the same way `email` and `age` were dropped in `contacts`; enforce
them in application code. `Account.opened` and `Entry.id` have no Protobuf date or uuid type, so
they lower to plain strings; parse them back in application code. `Entry.memo`'s max is dropped
too. The map's decimal values lower to strings; parse them back to decimals in application code.
The `minItems 2` bound on `Entry.lines` and the year-month pattern on `Close.period` are not enforced
by Protobuf; enforce them in application code.

Warnings from `examples/ledger/expected/sql-warnings.txt`:
```text
SCH2105 field 'Entry.lines': refinements on Line[] { minItems 2 } are not enforced by Postgres
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

## services

The orders from `shop` again, cut down, with a service that places and reads them. It shows each
place an operation's request can go over HTTP: the path, the query, the body, and a stream.

From `examples/services/orders.schemata`:
```schemata
model OrderId { id uuid { id } }

model ListOrders { status Status?  limit int32 { id, min 1, max 200 } = 50 }

model PlaceOrder { customer_id uuid { id }  lines Order.Line[] { minItems 1 } }
```

From `examples/services/orders.schemata`:
```schemata
/// Place and read orders.
service Orders {
  /// Fetch one order.
  get(OrderId): Order  get "/orders/{id}"
  /// Orders matching a filter, newest first.
  list(ListOrders): stream Order  get "/orders"
  place(PlaceOrder): Order  post "/orders"
  cancel(OrderId)  delete "/orders/{id}"
  upload(stream Chunk): Receipt
  reserved #6, "archive"
}
```

```text
java -jar schemata-<version>.jar compile --out out examples/services
```

The run ends:

```text
0 errors, 12 warnings
wrote 2 files to out/proto
wrote 2 files to out/sql
wrote 2 files to out/xsd
wrote 2 files to out/jsonschema
wrote 1 file to out/openapi
```

The twelve warnings are Protobuf's and Postgres's, of the kinds `shop` already explains; the
openapi target reports none. The Postgres, XSD, and JSON Schema targets write `catalog` and
`orders` as they would without the service, and the Protobuf target writes the same messages with a
gRPC service after them. The openapi target writes one document, `shop/orders.openapi.json`, since
`shop.catalog` declares no service.

`get` and `cancel` bind `id` in the path, and `cancel` has no response, so it answers `204`.
`list` is a `get` with nothing in its path, so both fields of `ListOrders` become query parameters,
neither required, since `status` is nullable and `limit` has a default; its response is a stream
of server-sent events.

From `examples/services/expected/openapi/shop/orders.openapi.json`:
```json
    "/orders": {
      "get": {
        "operationId": "Orders_list",
        "tags": [
          "Orders"
        ],
        "summary": "Orders matching a filter, newest first.",
        "parameters": [
          {
            "name": "status",
            "in": "query",
            "required": false,
            "style": "form",
            "explode": true,
            "schema": {
              "anyOf": [
                {
                  "$ref": "#/components/schemas/shop.orders.Status"
                },
                {
                  "type": "null"
                }
              ]
            }
          },
          {
            "name": "limit",
            "in": "query",
            "required": false,
            "style": "form",
            "explode": true,
            "schema": {
              "type": "integer",
              "minimum": 1,
              "maximum": 200,
              "default": 50
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Stream of Order",
            "content": {
              "text/event-stream": {
                "schema": {
                  "$ref": "#/components/schemas/shop.orders.Order"
                }
              }
            }
          }
        }
      },
```

`place` is a `post` that binds nothing in its path, so the whole `PlaceOrder` model is the body.
`upload` has no binding, so it is `post /Orders/upload`, and its streamed request is
newline-delimited JSON.

From `examples/services/expected/openapi/shop/orders.openapi.json`:
```json
    "/Orders/upload": {
      "post": {
        "operationId": "Orders_upload",
        "tags": [
          "Orders"
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/x-ndjson": {
              "schema": {
                "$ref": "#/components/schemas/shop.orders.Chunk"
              }
            }
          }
        },
        "responses": {
          "200": {
            "description": "Receipt",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/shop.orders.Receipt"
                }
              }
            }
          }
        }
      }
    }
```

Every type an operation reaches is a component keyed by its schema name, `Money` from
`shop.catalog` included, so the document stands alone: `Order.total` refers to it within the same
file.

From `examples/services/expected/openapi/shop/orders.openapi.json`:
```json
          "total": {
            "$ref": "#/components/schemas/shop.catalog.Money"
          }
```

`OrderId` and `ListOrders` are not components: their fields are parameters, and nothing refers to
the models themselves.

Under `--target proto`, the service is a gRPC `service` at the end of `shop/orders.proto`, after
the messages, and each operation an `rpc` named in UpperCamel. `cancel` has no response, so it
returns `.google.protobuf.Empty`, and the file imports `google/protobuf/empty.proto` beside
`shop/catalog.proto`, which `Order.total`'s `Money` comes from.

From `examples/services/expected/proto/shop/orders.proto`:
```proto
import "google/protobuf/empty.proto";
import "shop/catalog.proto";
```

The streams carry over as gRPC streams, and the doc comments as `//` comments. A binding has no
place in a gRPC `service`, so it rides in a `// schemata:` note after its rpc, and the `reserved`
statement in a note line after the last one. No rpc needs an ordinal in its note, since each
operation's ordinal is its position.

From `examples/services/expected/proto/shop/orders.proto`:
```proto
// Place and read orders.
service Orders {
  // Fetch one order.
  rpc Get(OrderId) returns (Order);  // schemata: get "/orders/{id}"
  // Orders matching a filter, newest first.
  rpc List(ListOrders) returns (stream Order);  // schemata: get "/orders"
  rpc Place(PlaceOrder) returns (Order);  // schemata: post "/orders"
  rpc Cancel(OrderId) returns (.google.protobuf.Empty);  // schemata: delete "/orders/{id}"
  rpc Upload(stream Chunk) returns (Receipt);
  // schemata: reserved #6, "archive"
}
```

Those notes are what lets the round trip close: `schemata import --from proto out/proto` reports
nothing and reads the service back as it is declared above, with its ordinals, bindings, streams,
docs, and `reserved`, and compiling that import under `--target proto` writes `shop/orders.proto`
again byte for byte.

Section 16 of the reference has every rule, and section 20 shows what `schemata diff` says about a
changed service.

## GPX

`schemata import --from xsd` goes the other way: it reads an existing `.xsd` and writes a
`.schemata` file. The GPX 1.1 schema (`http://www.topografix.com/GPX/1/1`) is a good one to walk
through, since it exercises most of what the importer does: a target namespace that is not
`urn:schemata:…`, a decimal with no declared precision, a fixed attribute value, and an `xs:any` it
cannot carry.

```text
java -jar schemata-<version>.jar import --from xsd --out out gpx.xsd
```

GPX's `targetNamespace` is a plain URI, not `urn:schemata:…`, so the output keeps it on the
`schema` line as `@xsd(namespace: "…")` and takes its schema name from the file name, `gpx`,
reported once (SCH2402); pass `--namespace gpx` yourself to silence that note. The root complex
type, `gpxType`, becomes `model Gpx`, with `@xsd(name: "gpx")` restoring the element name XSD
expects back.

From `schemata-cli/src/test/resources/import/gpx/expected/gpx.schemata`:
```
/// GPX schema version 1.1 - For more information on GPX and this schema, visit http://www.topografix.com/gpx.asp
///
/// GPX uses the following conventions: all coordinates are relative to the WGS84 datum.  All measurements are in metric units.
schema gpx @xsd(namespace: "http://www.topografix.com/GPX/1/1")

/// GPX documents contain a metadata header, followed by waypoints, routes, and tracks.  You can add your own elements
/// to the extensions section of the GPX document.
model Gpx {
  /// Metadata about the file.
  metadata   Metadata?
  /// A list of waypoints.
  wpt        Wpt[]
  /// A list of routes.
  rte        Rte[]
  /// A list of tracks.
  trk        Trk[]
  /// You can add extend GPX by adding your own elements from another schema here.
  extensions Extensions?
```

Every warning is lossy; none stops the import from writing its file.

Warnings from `schemata-cli/src/test/resources/import/gpx/expected/import-warnings.txt`:
```
SCH2402 gpx.xsd: schema name 'gpx' was derived from the file name
SCH2405 attribute 'version': fixed value imported as a default
SCH2403 element 'ele': decimal without totalDigits and fractionDigits imported as decimal(38, 9)
SCH2403 element 'magvar': decimal without totalDigits and fractionDigits imported as decimal(38, 9)
SCH2404 element 'magvar': facet maxExclusive dropped
```

Besides the schema name note already covered, `version`'s `fixed="1.1"` becomes a plain default
(SCH2405); `ele` and the other coordinates have no `totalDigits`/`fractionDigits`, so they import as
`decimal(38, 9)` (SCH2403); and `magvar`'s `maxExclusive` has no equivalent on a decimal and is
dropped (SCH2404). Compiling `out/import/gpx.schemata` under the sql target reports SCH2106 on
every model, since the import never adds `{ id }`; add keys by hand before compiling to SQL.
The proto, xsd, and jsonschema targets compile it as it stands. More generally, compiling an
import's own `.schemata` output under the xsd target and importing that result again regenerates
it byte for byte, with no diagnostics at all.

## Import a proto

`schemata import --from proto` reads `.proto` files the same way. Google's `google.type.Money`
message is a small, real one: a currency code and an amount held as whole units and nanos. It sits
in a directory laid out the way `protoc` expects, `google/type/money.proto`, beside a license
header and the file options every Google API file carries.

From `schemata-cli/src/test/resources/import/proto-money/google/type/money.proto`:
```proto
syntax = "proto3";

package google.type;

option go_package = "google.golang.org/genproto/googleapis/type/money;money";
option java_multiple_files = true;
option java_outer_classname = "MoneyProto";
option java_package = "com.google.type";
option objc_class_prefix = "GTP";

// Represents an amount of money with its currency type.
message Money {
  // The three-letter currency code defined in ISO 4217.
  string currency_code = 1;

  // The whole units of the amount.
  // For example if `currencyCode` is `"USD"`, then 1 unit is one US dollar.
  int64 units = 2;
```

From the directory holding `google/`:

```text
java -jar schemata-<version>.jar import --from proto --out out google/type/money.proto
```

The file is named on its own, so its schema name comes from its `package`, `google.type`, which is
already a schema name; the import writes `out/import/google/type.schemata` and reports nothing:

```text
no diagnostics
wrote 1 file to out/import
```

From `schemata-cli/src/test/resources/import/proto-money/expected/google/type.schemata`:
```schemata
/// Represents an amount of money with its currency type.
model Money {
  /// The three-letter currency code defined in ISO 4217.
  #1 currency_code string
  /// The whole units of the amount.
  /// For example if `currencyCode` is `"USD"`, then 1 unit is one US dollar.
  #2 units         int64
  /// Number of nano (10^-9) units of the amount.
  /// The value must be between -999,999,999 and +999,999,999 inclusive.
  /// If `units` is positive, `nanos` must be positive or zero.
  /// If `units` is zero, `nanos` can be positive, zero, or negative.
  /// If `units` is negative, `nanos` must be negative or zero.
  /// For example $-1.75 is represented as `units`=-1 and `nanos`=-750,000,000.
  #3 nanos         int32
}
```

The field numbers are the ordinals and the comments are the docs. The `option` lines steer code
generation in other languages and say nothing about the data, so they are ignored without a
warning, and the license header, separated from the message by a blank line, is not a doc. The
corpus golden quoted here also holds `Date`, from `google/type/date.proto` beside it: importing the
directory holding `google/` instead finds two files under one root that declare
`package google.type`, which `protoc` reads as one package, so they import as one schema,
`google.type`, in one file. Had `money.proto` been the only file there, it would have taken its path
under that directory, `google.type.money`, and kept its package as
`@proto(package: "google.type")`.

Nothing here needed a warning because every type in `Money` is one Schemata has. A `uint32`, a
`google.protobuf.StringValue`, or a `oneof` mixed with other fields would each be reported, and
section 19 of the reference lists what each construct becomes. Compiling the result under the
proto target gives `message Money` back with the same fields, numbers, and comments.

## Keeping the examples current

If you change one of these `.schemata` files, its `expected/` tree and warnings files need to
change with it. Run
`SCHEMATA_GOLDEN_UPDATE=1 ./gradlew :schemata-cli:test --tests 'io.schemata.cli.examples.ExamplesTest'`
to regenerate them, then read the diff before committing: every change should trace back to the
edit you made. `examples/shop/expected/xsd-sample.xml` is not regenerated this way; it is
hand-written, and the same test run validates it against the regenerated `orders.xsd`, so update it
by hand if a change to `shop` would make it invalid.
