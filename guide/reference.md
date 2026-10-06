# Schemata language reference

A `.schemata` file declares records, enums, unions, aliases, and services in a namespace. You give
the compiler a set of `.schemata` files, and it compiles that one set to a Protobuf schema, with
its services as gRPC services, a Postgres schema, an XML Schema, a JSON Schema, and, for each
namespace that declares a service, an OpenAPI document.
What a 1.x release promises about this language, its output, and its diagnostics is on the
[What is stable](stability.md) page.

## 1. Files and namespaces

Every file begins with a namespace declaration: `namespace a.b.c`. Each segment is lower_snake.
Several files may share a namespace; the compilation unit is the whole set of files given on the
command line, with any directories walked recursively. Output paths follow the namespace, so
`namespace shop.orders` writes `shop/orders.proto`, `shop/orders.sql`, `shop/orders.xsd`, and
`shop/orders.schema.json`, and `shop/orders.openapi.json` when it declares a service. A doc comment
and any annotations may precede the `namespace` line itself; section 15 shows annotations there, and
`examples/shop/orders.schemata` shows a doc comment. A UTF-8 byte-order mark at the start of a file
is skipped; `fmt` never writes one.

By default, a namespace's Postgres schema is its last segment: `shop.orders` lowers to schema
`"orders"`. Two namespaces with the same last segment collide (SCH2102) unless one sets
`@sql(schema = "…")`.

```schemata
namespace shop.orders

record Order {
  @sql(key) #1 id: int64
}
--- lines.schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
}
```

## 2. Imports

`import shop.customers` brings that namespace's declarations into scope by their bare names.
`import shop.customers as cust` makes them available only as `cust.Customer`, not by the bare name.
When two unaliased imports both declare the same name, the bare name is ambiguous (SCH1009); the
fully qualified name always works. An unused import is a warning (SCH1012). An alias is
lower_snake, like a namespace segment (SCH1045). A file imports a namespace once and gives an alias
to one import only; a repeat of either is an error (SCH1046).

```schemata
--- customers.schemata
namespace shop.customers

record Customer {
  @sql(key) #1 id: int64
}
--- orders.schemata
namespace shop.orders

import shop.customers

record Order {
  @sql(key) #1 id: int64
  #2 customer: Customer
}
```

```schemata
--- customers.schemata
namespace shop.customers

record Customer {
  @sql(key) #1 id: int64
}
--- orders.schemata
namespace shop.orders

import shop.customers as cust

record Order {
  @sql(key) #1 id: int64
  #2 customer: cust.Customer
}
```

```schemata error SCH1009
--- a.schemata
namespace shop.a

record Customer {
  @sql(key) #1 id: int64
}
--- b.schemata
namespace shop.b

record Customer {
  @sql(key) #1 id: int64
}
--- orders.schemata
namespace shop.orders

import shop.a
import shop.b

record Order {
  @sql(key) #1 id: int64
  #2 customer: Customer
}
```

## 3. Comments

`//` starts a line comment; the parser ignores everything to the end of the line. `/* … */` starts
a block comment, closed by the next `*/`. `///` starts a doc comment; it attaches to the
declaration or field that follows and is carried into the generated Protobuf and SQL as a comment
and into the XSD as `xs:documentation`. A doc comment before the `namespace` line is the exception:
the parser keeps it, but none of the outputs carries it. Like a string, a doc comment may hold a tab
but no other control character below U+0020 (SCH0005).

```schemata
namespace shop.orders

// Orders placed by customers.
/// A single line on an order.
record OrderLine {
  @sql(key) #1 id: int64
  /// The number of units ordered.
  #2 quantity: int32
}
```

## 4. Identifiers and naming

Namespace segments, field names, and operation names are lower_snake: a lowercase letter, then
lowercase letters and digits, with single underscores between runs and none at the start or the end
(`order_line`, `line2`; not `order__line`, `line_`, or `_line`). Type names (record, enum, union,
alias) and service names are UpperCamel. Enum values are lower_snake. The help suggests a corrected
name when it can derive one from what you wrote. No name in a `.schemata` file, whether a
declaration, a service, an operation, a field, or an enum value, may be one of the language's
keywords: `namespace`, `import`, `as`, `record`, `enum`, `union`, `alias`, `reserved`, `true`,
`false`, `service`, `operation`, `stream`. `service` starts a service and `stream` marks a streamed
payload (section 16). `operation` is held for a future version of the language: a declaration that
starts with `operation`, or with `stream` outside a service, is an error (SCH0002). The HTTP verbs
a binding names (`get`, `post`, and the rest) are not keywords, so a field or an operation may be
called `get`. An annotation key is exempt, so `@xsd(namespace = "…")` is legal. `null` is not a
keyword, since `= null` has to be read, but a field, an operation, an enum value, or a namespace
segment may not be called `null` either: `= null` always means the literal, never an enum value of
that name. Where a suggested name would be a keyword, the help adds `_value` (`null_value`,
`true_value`).

```schemata
namespace shop.orders

enum Status {
  #1 open
  #2 closed
}

record OrderLine {
  @sql(key) #1 id: int64
  #2 status: Status
}
```

```schemata error SCH1002
namespace shop.orders

record order_line {
  @sql(key) #1 id: int64
}
```

## 5. Builtin types and refinements

| Type | Meaning | Protobuf | Postgres | Refinements |
|---|---|---|---|---|
| `bool` | true or false | `bool` | `boolean` | none |
| `int32` | 32-bit signed integer | `int32` | `integer` | `min`, `max` |
| `int64` | 64-bit signed integer | `int64` | `bigint` | `min`, `max` |
| `float32` | 32-bit floating point | `float` | `real` | `min`, `max` |
| `float64` | 64-bit floating point | `double` | `double precision` | `min`, `max` |
| `decimal(p, s)` | exact decimal with p digits, s after the point | `string` (lossy) | `numeric(p, s)` | `min`, `max` |
| `string` | text | `string` | `varchar` or `text` | `min`, `max`, `pattern` |
| `bytes` | raw binary | `bytes` | `bytea` | `min`, `max` |
| `uuid` | a UUID | `string` (lossy) | `uuid` | none |
| `date` | a calendar date | `string` (lossy) | `date` | none |
| `time` | a time of day without a date | `string` (lossy) | `time` | none |
| `instant` | a point in time, UTC | `google.protobuf.Timestamp` | `timestamptz` | none |
| `duration` | a span of time | `google.protobuf.Duration` | `interval` | none |

For a number, `min` and `max` are bounds. For `string`, `min` and `max` are lengths and `pattern`
is a regular expression. The compiler checks only that Java accepts the pattern; keeping to the
subset Postgres also accepts is your job. `decimal` takes its precision and scale positionally:
`decimal(19, 4)`. Postgres enforces refinements as column types or CHECK constraints; a
`string(max = 100)` becomes `varchar(100)`. Protobuf carries no constraints; a refined field lowers
to its plain type and reports SCH2001. A pattern matches anywhere in the value unless anchored with
`^` or `$`, but an XSD pattern always matches the whole value, so the XSD target wraps each
unanchored side in `.*`: `pattern = "abc"` becomes `.*(abc).*`. XSD's `.` does not match a newline,
so a multi-line value can fail an XSD pattern that Java accepts. A `float32` bound must lie within
±3.4028235E38 and a `float64` bound within ±1.7976931348623157E308, the types' finite ranges
(SCH1038); a default must too (SCH1042).

A string literal is written in double quotes on one line. It knows six escapes: `\"`, `\\`,
`\n`, `\t`, `\r`, and `\u{…}` with one to six hex digits naming a Unicode character, as in
`"caf\u{E9}"`. Any other character after a backslash is an error (SCH0004). The one exception is
the string of a `pattern` refinement, which is taken exactly as written apart from `\"`: a regex
is full of backslashes that mean something to the target, so `pattern = "\d+"` is the regex
`\d+`, not an error. No string, a pattern included, may hold a control character below U+0020
other than tab, newline, and carriage return, nor U+FFFE or U+FFFF, whether typed as itself or
written `\u{…}` (SCH0005): XML cannot carry them. A tab typed between the quotes is a tab.

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 sku: string(max = 100)
  #3 quantity: int32(min = 0)
  #4 price: decimal(19, 4)
  #5 code: string(pattern = "^[a-z]+$")
}
```

## 6. Nullability

`T?` means the field's value may be absent. A nullable field always lowers to a nullable column in
Postgres. In Protobuf, a nullable scalar or enum lowers to proto3 `optional`; a nullable
`list<T>?` or `map<K, V>?` lowers to a plain `repeated` or `map` and reports SCH2001, since an
empty collection already means absent; a nullable `Record?` or `Union?` lowers to a plain message
field, since a message field's presence in proto3 is already implicit, and a trailing
`// schemata: Order?` comment (likewise `instant?` and `duration?`) records the nullability. Section 17 shows all four.
A map key may not be nullable. A nullable alias may not be marked `?` again where it is used;
section 8 shows an alias declared nullable and a field that uses it bare.

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 note: string?
  #3 tags: map<string, int32>
}
```

```schemata error SCH1021
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 tags: map<string?, int32>
}
```

## 7. Defaults

`= literal` after the type gives a field its default. The literal must fit the type and any
refinements it carries. `= null` is an error; write `T?` instead. An enum default names one of its
values. Postgres carries defaults into the column; Protobuf does not, and reports SCH2001.

```schemata
namespace shop.orders

enum Status {
  #1 open
  #2 closed
}

record OrderLine {
  @sql(key) #1 id: int64
  #2 active: bool = true
  #3 quantity: int32 = 1
  #4 sku: string = "N/A"
  #5 status: Status = open
}
```

```schemata error SCH1044
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 note: string = null
}
```

## 8. Aliases

`alias Money = decimal(19, 4)` gives a refined type a name. You use the alias by name; you may not
add refinements where it is used, only where it is declared. An alias may be marked nullable, but
the `?` belongs on the alias declaration itself; a field that uses the alias writes its bare name.

```schemata
namespace shop.orders

alias Money = decimal(19, 4)
alias OptionalMoney = decimal(19, 4)?

record OrderLine {
  @sql(key) #1 id: int64
  #2 price: Money
  #3 discount: Money?
  #4 tip: OptionalMoney
}
```

```schemata error SCH1039
namespace shop.orders

alias Money = decimal(19, 4)

record OrderLine {
  @sql(key) #1 id: int64
  #2 price: Money(max = 5)
}
```

## 9. Records

A record field is written `name: type`, with an optional ordinal `#n` before the name and an
optional default after the type. Records nest: `record Order { record Line { … } }` declares
`Line` inside `Order`. A field inside `Order` refers to it as `Line`; anything outside refers to it
as `Order.Line`. A record is a value type unless it declares a key, either `@sql(key)` on a field or
`@sql(key = (a, b))` on the record itself; section 15 shows both forms. A top-level record with no
key that no field uses is an error for the Postgres target (SCH2106); this is why the records in
this guide's examples all declare a key. Protobuf derives a JSON name from each field's name by
dropping every underscore and capitalising the letter after it, so two fields of one record that
derive the same one, such as `a_1` and `a1`, are an error for the Protobuf target (SCH2008).

```schemata
namespace shop.orders

record Order {
  @sql(key) #1 id: int64

  record Line {
    #1 sku: string
    #2 quantity: int32
  }

  #2 first_line: Line
}
```

```schemata
namespace shop.orders

record Order {
  @sql(key) #1 id: int64

  record Line {
    @sql(key) #1 id: int64
    #2 quantity: int32
  }
}

record Shipment {
  @sql(key) #1 id: int64
  #2 line: Order.Line
}
```

```schemata error SCH2106
namespace shop.orders

record Orphan {
  #1 id: int64
}
```

## 10. Enums

`enum Status { #1 pending, #2 paid }` declares an enum; its values are lower_snake. The compiler
always synthesizes a zero value for proto3 and reports SCH2001. Postgres stores the value as
`text`, with a CHECK restricting it to the declared values.

```schemata
namespace shop.orders

enum Status {
  #1 pending
  #2 paid
}

record OrderLine {
  @sql(key) #1 id: int64
  #2 status: Status = pending
}
```

## 11. Unions

`union Payment = #1 Card | #2 BankTransfer | #3 Cash` declares a union; each member carries an
ordinal like a field does. A member must be a named type, meaning a record, an enum, or another
union, or a scalar; it may not be nullable and may not be a collection. A union inside a union stays
a nominal member: Protobuf nests the `oneof`, XSD and JSON Schema nest the choice, and Postgres
reports it (SCH2110). Nullability belongs on the field that holds the union, not on a member.
Protobuf lowers a union to a message holding a `oneof`, and the field holds that message; a member
named `Kind` would collide with the `oneof` itself, always named `kind` (SCH2004). Postgres lowers a
union to a discriminator column plus each member's columns, with a CHECK tying the discriminator to
the columns that member requires. A member named `Kind` collides on the Postgres side too: its own
CHECK constraint takes the same name as the discriminator's (SCH2111).

```schemata
namespace shop.orders

record Card {
  #1 id: int64
}

record BankTransfer {
  #1 id: int64
}

record Cash {
  #1 id: int64
}

union Payment = #1 Card | #2 BankTransfer | #3 Cash

record OrderLine {
  @sql(key) #1 id: int64
  #2 payment: Payment
}
```

```schemata error SCH1027
namespace shop.orders

record Card {
  @sql(key) #1 id: int64
}

union Payment = #1 Card | #2 list<string>
```

## 12. Collections

`list<T>` and `map<K, V>` are the two collection types; a map key must be `string`, `int32`, or
`int64`. `min` and `max` refine the collection's count, not its elements. Postgres lowers
`list<scalar>` to an array column, `list<Record>` to a child table named `<parent>_<field>` with a
`<parent>_<key>` column and a `position` column, and `map` to `jsonb`; `@sql(strategy)` changes each
of these, as section 15 shows. Postgres does not enforce a collection's `min` or `max` count either
way; the bound is dropped and reported as SCH2105, the same as any other unenforced refinement. A
collection nested inside another collection has no Protobuf form (SCH2005), and no relational form
unless the outer collection takes `@sql(strategy = json)`.

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 tags: list<string>(max = 20)
  #3 attrs: map<string, int32>
}
```

```schemata error SCH2005 SCH2110
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 grid: list<list<int32>>
}
```

## 13. Reserved

`reserved #11, "legacy_ref"` and `reserved #5..#9`, written inside a record, an enum, or a service,
mark ordinals and names that may never be used again. A range is written low`..`high, inclusive on
both ends. Reusing a reserved ordinal or name is an error. A reserved name is a former field, enum
value, or operation name, so it is lower_snake like one (SCH1003 in a record or a service, SCH1028
in an enum).

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 quantity: int32
  reserved #11, "legacy_ref"
  reserved #5..#9
}
```

```schemata error SCH1020
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  reserved #2
  #2 quantity: int32
}
```

## 14. Ordinals

`#n` numbers a field, an enum value, a union member, or an operation. Within one declaration or
service, every element carries an explicit ordinal or none do; `--strict` rejects one that leaves
them implicit. Implicit ordinals are assigned in declaration order, starting at `#1`. A field's
ordinal becomes its Protobuf field number; no target numbers an operation, whose ordinal is its
identity for `diff`.

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) id: int64
  quantity: int32
}
```

```schemata error SCH1013
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  quantity: int32
}
```

## 15. Annotations

An annotation is written `@target(key)` for a flag, `@target(key = value)` for a value, or
`@target(k1, k2 = v)` for several keys at once. `@deprecated("reason")` applies to a record, an
enum, a union, an alias, a field, an enum value, a service, or an operation, under no target. A
namespace's annotations are written before its `namespace` line; a record's are written before its
`record` line.

`@proto(package = "…")` renames a namespace's Protobuf package. `@proto(name = "…")` renames a
single declaration, field, enum value, service, or operation; section 16 shows it on the last two.

`@sql(schema = "…")` renames a namespace's Postgres schema. `@sql(table = "…")` and
`@sql(column = "…")` rename a record's table or a field's column. An empty `@sql(schema | table | column)` is an error (SCH2114); the derived name is used. `@sql(key)` on a field, or
`@sql(key = (a, b))` on a record, declares the primary key. `@sql(unique)` and `@sql(index)` add a
constraint or an index to a field's column; neither is allowed on a list or a map (SCH2110).
`@sql(type = "…")` overrides the column type. `@sql(strategy = embed | table | json)` overrides how
a field lowers to Postgres, within this matrix:

| Field's shape | Default | Also allowed |
|---|---|---|
| a record without a key | `embed` | `json` |
| a record with a key | a reference (foreign key) | `embed`, `json`, `table` |
| `list<Record>` | `table` | `json` |
| `list<scalar>` | array | `table`, `json` |
| `map` | `json` | `table` |
| a union | `embed` | `json` |

`@xsd(namespace = "…")` sets a namespace's target XML namespace; without it, the namespace lowers
to `urn:schemata:<namespace>`. `@xsd(name = "…")` renames a record, an enum, a union, a field, or
an enum value. `@xsd(attribute)` on a field lowers it to an XML attribute instead of a child
element; only a scalar or enum field can be one (SCH2204 otherwise). `@xsd(root = false)` on a
record keeps it from getting the global element every record gets by default, for a record meant
to appear only nested inside another. The remaining `@xsd` keys write XML Schema constructs
Schemata has no type for, and are what `import --from xsd` uses to carry them (section 19):
`@xsd(any)`, `@xsd(any_attribute)`, `@xsd(any_type)`, and `@xsd(mixed)` on a string, list, or map
field write a wildcard, an attribute wildcard, an `xs:anyType` element, or mixed content, with
`@xsd(process)` and `@xsd(wildcard)` giving a wildcard's `processContents` and namespace
constraint; `@xsd(all)` on a record writes `xs:all` instead of a sequence; `@xsd(list)` on a list
field writes a list simple type instead of a repeated element; and `@xsd(element_form)` and
`@xsd(attribute_form)` on a namespace set the schema's form defaults.

`@jsonschema(id = "…")` sets a document's `$id`; without it, the namespace lowers to
`urn:schemata:<namespace>`. `@jsonschema(name = "…")` renames a record, an enum, a union, a field,
or an enum value (the `$defs` key, the property name, or the enum value string). A name must be
non-empty and must not contain whitespace or any of `/ ~ # % ? " \`, which a `$ref` cannot carry.
Two declarations, fields, union members, or enum values that lower to one JSON name are an error
(SCH2302). `@jsonschema(open)` on a record drops `additionalProperties: false`, so instances may
carry properties the record does not declare.

`@openapi(version = "…")` and `@openapi(server = "…")` on a namespace set its OpenAPI document's
`info.version` (default `1.0.0`) and its one server URL, which must be an absolute URL or a path
(SCH2603). `@openapi(name = "…")` renames a service's tag or an operation's `operationId`; section
16 shows all three.

`guide/annotations.md` lists every key each target accepts, the elements it applies to, and the
codes each target can report.

```schemata
@proto(package = "shop.orders.v1")
@sql(schema = "shop_orders")
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 quantity: int32
}
```

```schemata
namespace shop.orders

@sql(key = (order_id, sku))
record OrderLine {
  #1 order_id: int64
  #2 sku: string
  #3 quantity: int32
}
```

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  @sql(strategy = table) #2 attrs: map<string, int32>
}
```

```schemata error SCH2110
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  @sql(unique) #2 tags: list<string>
}
```

```schemata
@xsd(namespace = "http://example.com/contacts")
namespace contacts

record Contact {
  @sql(key)
  @xsd(attribute) #1 id: int64
  @xsd(name = "full-name") #2 name: string(max = 100)
  #3 tags: list<string>
}
```

```schemata
@jsonschema(id = "https://example.com/schemas/contacts")
namespace contacts

@jsonschema(open)
record Contact {
  @sql(key)
  @jsonschema(name = "contactId") #1 id: int64
  #2 name: string(max = 100)
}
```

## 16. Services

A `service` names a set of operations, each a call that takes a request and returns a response,
both of them records or unions the schema declares. A service is written at the top level of a
file, beside the declarations, never inside a record. It is not a type, so no field can hold one
(SCH1006), but it shares its namespace's type names: a service may not take the name of a
declaration or of another service in the same namespace (SCH1004). Two targets read services: the
openapi target writes an OpenAPI document for them, and the Protobuf target writes each one as a
gRPC `service` in its namespace's `.proto` file. The Postgres, XSD, and JSON Schema targets write
the same output with or without them.

```schemata
/// Orders and their lines.
namespace shop.orders

record OrderId { @sql(key) #1 id: uuid }

record ListOrders { #1 status: Status? @sql(key) #2 limit: int32(min = 1, max = 200) = 50 }

record PlaceOrder { @sql(key) #1 customer_id: uuid #2 lines: list<Order.Line>(min = 1) }

record Order {
  @sql(key) #1 id:     uuid
  #2 status: Status
  #3 lines:  list<Line>
  #4 total:  decimal(12, 2)

  record Line { #1 sku: string(max = 64) #2 quantity: int32(min = 1) }
}

enum Status { #1 pending, #2 paid, #3 shipped, #4 cancelled }

record Chunk { @sql(key) #1 bytes: bytes }

record Receipt { @sql(key) #1 count: int64 }

/// Place and read orders.
service Orders {
  /// Fetch one order.
  #1 get(OrderId): Order  get "/orders/{id}"
  /// Orders matching a filter, newest first.
  #2 list(ListOrders): stream Order  get "/orders"
  #3 place(PlaceOrder): Order  post "/orders"
  #4 cancel(OrderId)  delete "/orders/{id}"
  #5 upload(stream Chunk): Receipt
  reserved #6, "archive"
}
```

### Operations

An operation is written `#n name(Request): Response`, optionally followed by a binding. Its name is
lower_snake and unique within its service (SCH1005). Either payload may be left out: `cancel`
above returns nothing, and `ping(): Pong` would take nothing. A payload names a record or a union,
directly or through an alias, and may come from another namespace, as a field's type can. It may
not be nullable, a scalar, an enum, or a collection (SCH1047); wrap such a value in a record.

Operations take ordinals and `reserved` exactly as a record's fields do (sections 13 and 14):
every operation of a service carries an ordinal or none does (SCH1013), `--strict` reports
implicit ones, and `reserved` keeps an ordinal or a name from coming back (SCH1020). `diff` matches
operations by ordinal, so number them. A doc comment and `@deprecated` apply to a service and to
each operation.

```schemata error SCH1047
namespace shop.orders

record Order {
  @sql(key) #1 id: uuid
}

service Orders {
  #1 get(uuid): Order
}
```

### Bindings

A binding gives an operation its HTTP verb and path: `get "/orders/{id}"`. The verb is one of
`get`, `post`, `put`, `patch`, `delete`, `head`, and `options` (SCH0006). The path starts with
`/`, ends with one only when it is `/` itself, and is made of segments of letters, digits, `.`,
`_`, `~`, and `-`, or of parameters written `{name}` in lower_snake (SCH0007).

Each `{name}` binds the request record's field of that name. The field must exist, must be a
scalar or an enum, and must not be nullable, since a path segment is always present; a path names
each parameter once, and only a record request can bind one, not a union (SCH1048). A verb and a
path belong to one operation in a namespace, across all of its services, and paths that differ only
in their parameters' names, such as `/orders/{id}` and `/orders/{order_id}`, are one path
(SCH1048).

The request's other fields go where the verb puts them:

| Verb | A field the path binds | Every other field |
|---|---|---|
| `get`, `delete`, `head`, `options` | a path parameter | a query parameter; it must be a scalar, an enum, or a list of either (SCH2601), and a union request, having no fields to spread, cannot use these verbs (SCH2601) |
| `post`, `put`, `patch` | a path parameter | the request body: the whole record when the path binds nothing, an object of the remaining fields when it binds some, and no body when it binds them all; a union request is always the whole body |

An operation without a binding is `post /<tag>/<operation>`, where the tag is the service's name or
its `@openapi(name)`, and its request is the body: `upload` above is `post /Orders/upload`.

```schemata error SCH1048
namespace shop.orders

record OrderId {
  @sql(key) #1 id: uuid
}

service Orders {
  #1 get(OrderId): OrderId  get "/orders/{order}"
}
```

```schemata error SCH1048
namespace shop.orders

record OrderId {
  @sql(key) #1 id: uuid
}

service Orders {
  #1 get(OrderId): OrderId  get "/orders/{id}"
  #2 fetch(OrderId): OrderId  get "/orders/{id}"
}
```

### Streams

`stream` before a payload makes it a sequence of values instead of one. A streamed response is
server-sent events, `text/event-stream`, one value per event; a streamed request is
newline-delimited JSON, `application/x-ndjson`, one value per line. A streamed request is always a
body, so it binds no path parameters and needs `post`, `put`, or `patch` (SCH1048).

```schemata error SCH1048
namespace shop.uploads

record Chunk {
  @sql(key) #1 bytes: bytes
}

service Uploads {
  #1 upload(stream Chunk): Chunk  get "/uploads"
}
```

### The OpenAPI document

The openapi target writes one OpenAPI 3.1 document for each namespace that declares a service, at
the namespace's path with `.openapi.json` (`shop/orders.openapi.json`). Its `info.title` is the
namespace, `info.description` the namespace's doc comment, and `info.version` the namespace's
`@openapi(version)`, or `1.0.0`; `@openapi(server)` gives `servers` its one entry.

Each service is a tag, named by the service or its `@openapi(name)`, described by the service's doc
comment. Each operation sits under its path and verb, the paths in the order their first operation
is declared. Its `operationId` is `<tag>_<operation>` (`Orders_get`) unless the operation sets
`@openapi(name)`. A tag or an `operationId` holds only letters, digits, `_`, `.`, and `-`
(SCH2603); two services with one tag, two operations with one `operationId`, an operation without
a binding whose derived verb and path another operation binds, or two operations whose paths differ
only in their parameters' names, whatever their verbs, are an error (SCH2602), since OpenAPI holds
such paths as one. Two bindings of one verb and path, parameter names aside, are already refused
(SCH1048). The first paragraph of an operation's doc comment is its `summary` and the rest its
`description`. `@deprecated` on an operation, or on its service, marks the operation
`deprecated: true`; on a service, the tag's description also says `Deprecated.`

A parameter is named by its field, carries the field's doc comment and `@deprecated`, and takes
the field's schema: its type, refinements, nullability, and default. `@jsonschema(name)` renames a
property, never a parameter (SCH2604). A path parameter is always `required`; a query parameter is
`required` unless its field is nullable or has a default, and is written `style: form` with
`explode: true`, so a list repeats the parameter.

A response is `200`, with the response's schema as `application/json`, or as `text/event-stream`
when it is streamed; an operation without a response answers `204`. Every record, enum, and union
an operation reaches is a component under `#/components/schemas`, keyed `<namespace>.<Name>`
(`shop.orders.Order`, `shop.orders.Order.Line`) and lowered exactly as the JSON Schema target
lowers it. A record's nested declarations are components beside it even when no operation reaches
them, since a record lowers together with its nested declarations. A request record whose fields
become parameters or a partial body, as `OrderId`'s and `ListOrders`'s do above, is not a component
itself; only the types its fields name are.

`get` and `cancel` above share a path; in `shop/orders.openapi.json` they become:

```json
    "/orders/{id}": {
      "get": {
        "operationId": "Orders_get",
        "tags": [
          "Orders"
        ],
        "summary": "Fetch one order.",
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "format": "uuid",
              "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Order",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/shop.orders.Order"
                }
              }
            }
          }
        }
      },
      "delete": {
        "operationId": "Orders_cancel",
        "tags": [
          "Orders"
        ],
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "format": "uuid",
              "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            }
          }
        ],
        "responses": {
          "204": {
            "description": "No content"
          }
        }
      }
```

The three `@openapi` keys, together:

```schemata
@openapi(version = "2.1.0", server = "https://api.example.com/v2")
namespace shop.orders

record OrderId {
  @sql(key) #1 id: uuid
}

record Order {
  @sql(key) #1 id: uuid
}

@openapi(name = "orders")
service Orders {
  @openapi(name = "getOrder") #1 get(OrderId): Order  get "/orders/{id}"
  #2 ping(): Order
}
```

The document's version is `2.1.0` and its server `https://api.example.com/v2`; the tag is
`orders`, `get`'s `operationId` is `getOrder`, and `ping` is `post /orders/ping` with
`operationId` `orders_ping`.

### Protobuf

The Protobuf target writes each service as a gRPC `service` at the end of its namespace's `.proto`
file, after the messages and enums, in the order the services are declared, with one `rpc` per
operation in the order the operations are written.

A service is named by its own name, or by its `@proto(name)`. It shares the package's names with
the messages and enums, so a service and a message or enum of one proto name are an error
(SCH2004). An rpc is named by the UpperCamel form of its operation's name, `list_orders` becoming
`ListOrders`, or by the operation's `@proto(name)`; two rpcs of one name in a service are an error
too (SCH2004). An override that is not a valid Protobuf identifier is SCH2007.

A request or a response is written the way a field of that type would be: the message's name as
it stands in the file, `Order` or `Order.Line`, or `.shop.catalog.Money` with an `import` of its
file when it comes from another namespace. A union is the message the union lowers to, and an
alias the type it stands for. `stream` carries over as it is. An operation without a request or a
response takes `.google.protobuf.Empty` in its place, and the file imports
`google/protobuf/empty.proto`.

The `Orders` service at the start of this section becomes, at the end of `shop/orders.proto`:

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

What gRPC has no place for rides in a `// schemata:` note, the same comment that carries a field's
refinements, so `import --from proto` can read it back (section 19):

| Schemata | Protobuf |
|---|---|
| a service | `service`, named by its name or its `@proto(name)` |
| an operation | `rpc`, named by the UpperCamel form of its name or by its `@proto(name)` |
| a request or a response | the message, `stream` included |
| no request, or no response | `.google.protobuf.Empty`, and its import |
| a doc comment | a `//` comment above the service or the rpc |
| `@deprecated` | `option deprecated = true;` inside the service, or in a body the rpc then takes |
| a binding | a note after the rpc: `// schemata: get "/orders/{id}"` |
| an ordinal | a note after the rpc, `// schemata: #5`, only when it is not the rpc's position in the service, counting from 1 |
| `reserved` | a note line after the last rpc: `// schemata: reserved #6, "archive"` |
| `@openapi` keys | nothing |

An rpc with both an ordinal and a binding takes one note, the ordinal first, the two separated by
`; `. Every one of those carriers, in one namespace:

```schemata
/// Orders served as a gRPC API under a pinned package.
@proto(package = "shop.v1")
namespace corpus.grpc

record OrderId { @sql(key) #1 id: int64 }

record Order { @sql(key) #1 id: int64 #2 note: string }

record Summary { @sql(key) #1 count: int64 }

record Chunk { @sql(key) #1 seq: int64 #2 data: bytes }

/// Read and feed orders.
@proto(name = "OrderApi")
service Orders {
  /// Fetch one order.
  @proto(name = "Fetch") #1 get(OrderId): Order  get "/orders/{id}"
  #2 watch(): stream Summary  get "/summary"
  /// Feed order data; replaced by a batch import.
  @deprecated #5 upload(stream Chunk)  post "/chunks"
  reserved #3..#4, "old"
}
```

gives, in `corpus/grpc.proto`:

```proto
// Read and feed orders.
service OrderApi {
  // Fetch one order.
  rpc Fetch(OrderId) returns (Order);  // schemata: get "/orders/{id}"
  rpc Watch(.google.protobuf.Empty) returns (stream Summary);  // schemata: get "/summary"
  // Feed order data; replaced by a batch import.
  rpc Upload(stream Chunk) returns (.google.protobuf.Empty) {  // schemata: #5; post "/chunks"
    option deprecated = true;
  }
  // schemata: reserved #3..#4, "old"
}
```

`upload` is the third rpc but `#5`, so its note says so; `get` and `watch` sit at their ordinals
and need none.

### Evolving and formatting

`schemata diff` compares services too, a service by its qualified name and an operation by its
ordinal. The openapi and proto rulebooks judge a change to a service; section 20 lists their
verdicts.

`fmt` writes a service in braces, one operation per line, with two spaces before the binding. When
that line would pass 100 columns, the binding moves to the next line, one level deeper. A service
with no members and no comments inside its braces is `service S {}`.

## 17. How constructs lower

The OpenAPI document holds, under `#/components/schemas`, only the declarations its operations
reach, each lowered as the JSON Schema target lowers it; its `@jsonschema` keys apply there too.
The Protobuf target writes a service as a gRPC `service` after its namespace's messages; the
Postgres, XSD, and JSON Schema targets read no service.

| Construct | Protobuf | Postgres | XSD | JSON Schema | OpenAPI |
|---|---|---|---|---|---|
| a record (default strategy) | a nested or referenced message field | embedded: the record's fields become columns on the containing table, prefixed by the field name | an `xs:complexType` plus a global element | an `object` in `$defs` with `additionalProperties: false` unless `@jsonschema(open)`; a field of the type is a `$ref` | as JSON Schema, as a component keyed `<namespace>.<Name>`; a field of the type is a `$ref` to `#/components/schemas/<namespace>.<Name>` |
| a keyed record (`@sql(key)`) | an ordinary message; the key adds nothing to it | its own table, keyed by the declared column or columns; a field elsewhere of this type becomes a foreign key to it | the same; the key is not represented | the same; the key is not represented | the same; the key is not represented |
| a record declared inside another (`Order.Line`) | a nested message under the enclosing message | nesting only scopes the name; the field that uses the type still follows the record or collection rules in this table | its own named type, `OuterInnerType` | its own `$defs` entry, keyed `Outer.Inner` | its own component, keyed `<namespace>.Outer.Inner` |
| an enum | `enum`, always with a synthesized zero value (SCH2001) | `text`, with a CHECK restricting it to the declared values | an `xs:simpleType` restricting `xs:string` to an enumeration | `type: string` with `enum`; when a value has a doc, `oneOf` of `const` entries so the docs survive | as JSON Schema |
| a union | a message holding a `oneof`; the field holds that message; each member is a field named from the member type's simple name in lower_snake or its `@proto(name)` | a discriminator column plus each member's columns, with a CHECK tying the discriminator to the columns a given member requires | a complexType holding an `xs:choice`, one element per member | an object with `oneOf`, one single-property closed object per member, tagged by the member type's name in lower snake (`bank_transfer`, `int64`), or by its `@jsonschema(name)` as written | as JSON Schema |
| `list<scalar>` | `repeated <scalar>` | an array column by default; `@sql(strategy = table)` gives it a child table, `json` a `jsonb` column | a repeated element | an `array` with `items`, `minItems`, `maxItems` | as JSON Schema |
| `list<Record>` | `repeated <Message>` | a child table by default, named `<parent>_<field>`, keyed by the parent's key columns plus `position`; `@sql(strategy = json)` gives it a `jsonb` column | a repeated element of the record's type | an `array` of `$ref` items | as JSON Schema |
| `map<K, V>` | the native `map<K, V>` type | `jsonb` by default; `@sql(strategy = table)` gives it a child table with `key` and `value` columns | a wrapper element holding `entry` elements keyed by a `key` attribute; a refined scalar value sits in a `value` child element, since an extension cannot carry facets | an `object` with `additionalProperties`; an integer key adds a digit pattern on `propertyNames`, a refined string key its constraints | as JSON Schema |
| a nullable field (`T?`) | proto3 `optional` for a scalar or enum; a plain `repeated` or `map` for a nullable list or map (SCH2001); a plain message field for a nullable record, union, `instant`, or `duration`, whose presence is already implicit, with a trailing `// schemata: T?` comment | the column allows `NULL` | `minOccurs="0"` on an element, or `use="optional"` on an attribute | not `required`; a scalar's `type` becomes `[T, "null"]`, anything else `anyOf` with `{"type": "null"}` | as JSON Schema; as a query parameter, not `required` |
| a default (`= literal`) | dropped, and kept only as a trailing comment (SCH2001) | a `DEFAULT` clause on the column | `default=` on the element or attribute; on an element, XSD applies it only when the element is present and empty | `default`, an annotation the reader applies; the field is not `required` | as JSON Schema; as a query parameter, not `required` |
| a refinement (`min`, `max`, `pattern`) | dropped, and kept only as a trailing comment (SCH2001) | a narrower column type, such as `varchar(100)` or `numeric(19, 4)`, or a CHECK constraint; a `pattern` with a construct Postgres regexes lack (`\p{…}`, `\b` as a word boundary, named groups, possessive quantifiers) drops the CHECK with a warning (SCH2105); lookahead and lookbehind are fine | facets on the restriction, such as `xs:maxLength` or `xs:pattern`; a pattern is anchored by wrapping an unanchored side in `.*`, and XSD's `.` excludes newlines | `minimum`/`maximum`, `minLength`/`maxLength`, `pattern` unchanged (both dialects match anywhere); a construct ECMA-262 lacks drops the pattern (SCH2301), including an identity escape such as `\-` outside a class, which the Unicode dialect JSON Schema assumes rejects; `min`/`max` on a decimal are dropped, since a decimal is a string with a precision-and-scale pattern (SCH2301); `bytes` bounds become base64 lengths (SCH2301 for `max`) | as JSON Schema, with SCH2604 where JSON Schema reports SCH2301 |
| an alias | transparent: it lowers exactly as its underlying type would | transparent, for the same reason | inlined: the alias itself is not represented | inlined | inlined |
| a doc comment (`///`) | a `//` comment above the declaration | `COMMENT ON TABLE` or `COMMENT ON COLUMN` | an `xs:documentation` element inside `xs:annotation` | `description` | `description`, as JSON Schema |
| `@deprecated` | `option deprecated = true` or `[deprecated = true]` | not represented | not represented | `deprecated: true` on the def or property; not on enum values | as JSON Schema |
| `reserved` | `reserved <n>;` and `reserved "name";` inside the message; in a service, a `// schemata: reserved …` line | nothing | not represented | not represented | not represented |
| a service | a `service`, named by its name or `@proto(name)`; section 16 | nothing | nothing | nothing | a tag; section 16 |
| an operation | an `rpc`, named in UpperCamel or by `@proto(name)`; no payload is `.google.protobuf.Empty`, and the binding and an ordinal off its position ride in a `// schemata:` note | nothing | nothing | nothing | an operation under its path and verb; section 16 |

### 17.1 Validating JSON instances

A document has no top-level `type`; validate an instance against one definition by its pointer,
`<$id>#/$defs/<Name>`, for example `urn:schemata:shop.orders#/$defs/Order`. Register every
generated document with your validator by its `$id` (most validators take a map from `$id` to
document), because references across namespaces are absolute. `format` keywords (`uuid`, `date`,
`date-time`, `duration`) are assertions only when the validator enables format assertions; `uuid`
also carries a pattern so it is enforced regardless. `int64` is an `integer` with its full range,
`decimal(p, s)` is a string such as `"24.4800"`, and `bytes` is a base64 string. `time` is a string
matched by a pattern rather than a `format`, because RFC 3339's `time` requires a zone offset and a
Schemata `time` has none. An integer map key is checked only by its digit pattern; its range is not
enforced. JSON Schema has no per-value deprecation for enum values, so `@deprecated` on an enum
value is not carried.

## 18. The CLI

`schemata` has seven commands: `compile`, `check`, `import`, `targets`, `fmt`, `diff`, and `lsp`.
This section covers `compile`, `check`, `targets`, `fmt`, and `lsp`; `import` is section 19 and
`diff` is section 20.

`compile <paths>...` compiles to `--out` (default `out`), for `--target` (a comma-separated list;
default `proto`, `sql`, `xsd`, `jsonschema`, and `openapi`), reporting diagnostics in `--format`
(`human`, to stderr, or `json`, to stdout), colored per `--color` (`auto`, `always`, or `never`),
and treating every warning as an error under `--strict`, which also reports any field, enum value,
union member, or operation left with an implicit ordinal. The openapi target writes nothing for a
namespace without a service. A path may be a file or a directory, walked recursively for
`.schemata` files.

`check <paths>...` takes the same options except `--out`; it reports every diagnostic `compile`
would, for every target, without writing anything.

`targets` lists the targets, the annotation keys each accepts, and the diagnostic codes each can
report, under `--format` (`human` or `json`).

`schemata fmt PATHS...` rewrites schema files in the canonical layout and names each file it
changed; `schemata fmt --check PATHS...` writes nothing, prints a diff for each file that would
change, and exits 1 if any would, which is how CI keeps a repository formatted. Comments are kept:
one on its own line stays above the element that follows it, and a comment at the end of a line
stays on that line (after the element, or after the opening brace). Long lines are never wrapped,
except that an operation's binding moves to a line of its own (section 16). Doc-comment text keeps
its indentation beyond one space after `///`. A file that does not parse is reported like `check`
would and left untouched.

`lsp` runs the language server for an editor; section 21 describes it. It takes no options and
writes nothing but protocol messages to stdout.

The exit code tells you what happened without reading the output: `0` when there is nothing to
report, `2` when every diagnostic is a warning, and `1` when any diagnostic is an error, or the
command line itself was wrong.

A human report goes to stderr, one diagnostic at a time, followed by a summary line. This is
`schemata check contacts.schemata` run against the `examples/contacts` file, shortened here; the
run reported eight warnings in total.

```text
warning[SCH2001] (lossy) (proto): enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0
 --> contacts.schemata:4:6
  |
4 | enum Kind { #1 personal, #2 work }
  |      ^^^^
  = help: keep the synthesized zero value; proto3 reads an unset enum as 0

warning[SCH2105] (lossy) (sql): field 'Contact.tags': refinements on list<string(max = 20)> are not enforced by Postgres
  --> contacts.schemata:14:3
   |
14 |   #7 tags:  list<string(max = 20)>
   |   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   = help: use `@sql(strategy = table)` so the elements become rows with their own constraints

0 errors, 8 warnings
```

A JSON report is one document on stdout: the diagnostics, the files `compile` wrote, the targets it
skipped because they had errors, and the exit code. `check` never writes, so `written` and `skipped`
are always empty, as they are below. This is the same run as `--format json`, reformatted for
reading here and with the diagnostics array shortened to its first entry:

```json
{
  "diagnostics": [
    {
      "code": "SCH2001",
      "severity": "warning",
      "category": "lossy",
      "promoted": false,
      "target": "proto",
      "message": "enum 'Kind': proto3 requires a zero value; synthesized KIND_UNSPECIFIED = 0",
      "help": "keep the synthesized zero value; proto3 reads an unset enum as 0",
      "span": {"file": "contacts.schemata", "startLine": 4, "startColumn": 6, "endLine": 4, "endColumn": 9}
    }
  ],
  "written": [],
  "skipped": [],
  "exitCode": 2
}
```

A span's lines and columns count from 1 and its end is inclusive: `endColumn` is the column of the
span's last character, so `Kind` above runs from column 6 to 9. Columns count Unicode code points,
so an emoji is one column. A diagnostic at the end of input is one column wide and sits one column
past the last character.

`schemata --version` prints the compiler's version and exits.

## 19. Importing

    schemata import --from xsd|proto|sql [--out DIR] [--namespace NAME] [--strict] [--format human|json] [--color auto|always|never] PATHS...

`import` reads a schema that already exists, as XML Schema, Protobuf, or Postgres DDL, and writes
the `.schemata` source that describes the same data, reporting everything it could not carry over
exactly. What the three formats share comes first; each has its own subsection after it.

### The command

`--from` names the format: `xsd` reads `.xsd` files, `proto` reads `.proto` files, and `sql` reads
`.sql` files of Postgres DDL. A path may be a file or a directory, walked recursively for files of
that format, the same as `compile`. The files found are imported together, so a reference from one
to a declaration in another resolves wherever that declaration is. The output is one file per
namespace, written to `--out/import/<namespace as a path>.schemata`: namespace `shop.orders` writes
`out/import/shop/orders.schemata`. Nothing is written when any error is reported. Exit codes match
`compile`: `0` when nothing is reported, `2` when every diagnostic is a warning, `1` when any is an
error, or the command line itself was wrong. `--strict` promotes every warning to an error, as it
does for `compile`, and so keeps the import from writing anything. `--format` and `--color` work
as they do for `compile`.

Each format names its namespaces from what it has:

- An XML Schema's `targetNamespace` names it, as the XSD subsection describes.
- A `.proto` or `.sql` file found by walking a directory takes its path under that directory, the
  way `protoc` addresses a file and the way `compile` lays its output out:
  `protos/shop/orders.proto`, found by walking `protos`, is `namespace shop.orders`. A segment that is not lower_snake is
  lower-snaked and the change reported (SCH2402), so `k8s.io/api/core/v1/generated.proto` becomes
  `k8s_io.api.core.v1.generated`. Importing a directory `compile` wrote, such as `out/proto` or
  `out/sql`, gets every namespace back under its own name. Proto files that share a package, and a
  DDL file with tables in several schemas, are named as their subsections describe.
- A `.proto` or `.sql` file named on its own has no such path. It takes the name it declares, its
  `package` or the one schema it puts its tables in, when that is already a namespace name, and
  otherwise its file stem, lower-snaked, reporting the name it derived (SCH2402).
- A declared name the namespace does not already say is kept: `@proto(package = "…")` when the
  package differs from the namespace, `@sql(schema = "…")` when the schema is not the namespace's
  last segment, which is the schema the sql target would otherwise use (section 1).

`--namespace NAME` names the namespace of a single input file and silences that note. It applies
only when exactly one input file is given; more than one is a usage error, as is a name that is not
dotted lower_snake segments.

Every import reports in one family:

| Code | Meaning |
|---|---|
| SCH2401 | error: a file cannot be read, a reference, import, or include cannot be resolved, or two constructs lower to one name |
| SCH2402 | warning: a namespace name was derived rather than taken as written, or an rpc's name was lower-snaked into one the target would not write back |
| SCH2403 | warning: a construct was approximated; it is kept, but the regenerated schema will differ |
| SCH2404 | warning: a type or facet was widened or dropped |
| SCH2405 | warning: a construct was dropped |

The diagnostics appendix lists each message and its help. Each subsection below ends with a table
of the constructs its format can hold, what each becomes, and the code it reports: none when it
lowers exactly, SCH2403 or SCH2404 when it is approximated, and SCH2405 when it is dropped.

An import from XSD or Protobuf never carries `@sql(key)`, since neither format declares a key, so
compiling the result under the sql target reports SCH2106 for every record until you add one by
hand; the proto, xsd, and jsonschema targets compile it straight away. An import from SQL carries
the keys its tables declare.

### From XSD

`import --from xsd` resolves an `xs:import` or `xs:include` by its own `schemaLocation` against the
importing file's directory, so an input never needs naming twice because another input imports it,
and an input that another input includes is merged into that one rather than imported on its own.

A schema's `targetNamespace` becomes the output's `namespace`. `urn:schemata:<name>` becomes
`namespace <name>`, matching what the xsd target itself writes for a Schemata namespace. Any other
URI becomes `namespace <file stem>`, with `@xsd(namespace = "<uri>")` on the namespace to keep the
real one and a note, SCH2402, naming the namespace it derived; pass `--namespace` to choose the name
yourself and silence the note. A schema with no `targetNamespace` uses the file stem alone, without
the annotation; the note still appears, and `--namespace` silences it.

#### Types and facets

| XSD type(s) | Schemata type | Notes |
|---|---|---|
| `boolean` | `bool` | |
| `int`, `integer`, `short`, `byte`, `unsignedShort`, `unsignedByte` | `int32` | |
| `long`, `unsignedInt` | `int64` | |
| `unsignedLong`, `nonNegativeInteger` | `int64(min = 0)` | |
| `positiveInteger` | `int64(min = 1)` | |
| `negativeInteger` | `int64(max = -1)` | |
| `nonPositiveInteger` | `int64(max = 0)` | |
| `float` | `float32` | |
| `double` | `float64` | |
| `decimal` | `decimal(p, s)` | `p` and `s` are `totalDigits` and `fractionDigits`; without both, `decimal(38, 9)` (SCH2403) |
| `string`, `normalizedString`, `token`, `language`, `Name`, `NCName`, `NMTOKEN`, `ID`, `IDREF`, `anyURI` | `string` | |
| a `string` restricted by the UUID pattern | `uuid` | |
| `base64Binary` | `bytes` | |
| `hexBinary` | `bytes` | SCH2404 |
| `date`, `time`, `dateTime`, `duration` | `date`, `time`, `instant`, `duration` | |
| `anyType` | `string` with `@xsd(any_type)` | see Wildcards, open content, and mixed |
| any other builtin | `string` | SCH2404 |

`minInclusive`/`maxInclusive` and `minLength`/`maxLength`/`length` become `min`/`max`.
`minExclusive`/`maxExclusive` shift by one on an integer type (`minExclusive = 0` becomes
`min = 1`); on anything else, an exclusive bound has no Schemata equivalent and is dropped
(SCH2404). A facet whose value does not parse as the number or count it should be is dropped
(SCH2404), and so is a `minOccurs` or `maxOccurs` that is not a count, which then reads as `1`.
`pattern` carries over, with its anchoring reversed, since an XSD pattern always matches the whole
value and a Schemata pattern matches anywhere unless anchored. It is written as the regex it is,
every backslash kept (`[A-Z]{2}\d{4}` becomes `pattern = "^[A-Z]{2}\d{4}$"`); one holding a lone
backslash before a quote, which no valid XSD regex does, has no literal and is dropped (SCH2404).
`enumeration` becomes an `enum`. `whiteSpace` and any other facet the table does not name are
dropped (SCH2404); a lone `totalDigits` or `fractionDigits` falls under the `decimal(38, 9)` note
above. A named simple type with none of this, just a restriction of a builtin, is inlined at its
use. List and union simple types have a subsection of their own below.

#### Names

A named complex type becomes a `record`. A trailing `Type` is stripped when what is left is
UpperCamel (`OrderType` becomes `Order`); a name without that suffix, such as `Address`, imports as
given and is reported (SCH2403), because it will regenerate as `AddressType`. The one exception is a
name ending `Type` whose stem is not UpperCamel, such as `gpxType`: it becomes `record Gpx` with
`@xsd(name = "gpx")` restoring the original name, silently, since the override already makes the
round trip exact. The same naming applies to the other two named types: an enumerated simple type
becomes an `enum`, and a choice-only complex type a `union`, so `paymentType` becomes
`union Payment` with `@xsd(name = "payment")`.

The first global element naming a complex type marks that record as a root. Its name regenerates as
the record name in lower_snake (`Order` gives `order`), or as the `@xsd(name)` override; when the
element is named otherwise, the override is added if it alone makes the name exact, and otherwise
the mismatch is reported (SCH2403) with the name it will regenerate as. A global element with its
own anonymous complex type becomes a top-level record named after it, reported the same way when
that name will not regenerate (`myThing` will regenerate as `my_thing`); one whose record name a
named type already owns, such as `gpx` beside `gpxType`, is an error (SCH2401) and dropped, while one
whose record name another global element's record already took, such as `SecondDefiningParameter`
after `secondDefiningParameter`, is numbered (`SecondDefiningParameter2`, SCH2403). The xsd
target writes one global element per record of its own namespace, so a second global element of the
same type, and one of a simple type or of a type in another namespace, are dropped (SCH2405). A
complex type never used as a global element becomes `@xsd(root = false)`. An anonymous complex type
becomes a record nested under the element that uses it, named after that element.

An element, attribute, or enum value name that is not a valid Schemata identifier lowers to
lower_snake with `@xsd(name = "…")` restoring the original, silently; a value that cannot be an XML
name at all, such as `2d`, is prefixed (`v2d`) and reported (SCH2403). A name that is a Schemata
keyword, such as `true`, `stream`, or `import`, or the reserved name `null`, takes a `_value` suffix
the same way: an enumeration value `true` becomes `true_value` with `@xsd(name = "true")`, and a
default naming it follows. A record named after an element whose name starts with a digit is
prefixed with `V`, so `3d` gives `record V3d`. In an enumeration value, `+` is spelled `plus` and a
`-` that does not join two letters or digits is spelled `minus` (`+x-y` gives `plus_x_y`, `-x-y`
gives `minus_x_y`, while `paid-out` stays `paid_out`), and a value whose name an earlier value already
took is numbered (`v_2`). An element and an attribute of one record with the same name keep apart
as they do in XML: the element keeps the name and the attribute takes `<name>_attribute`, which the
regenerated attribute is then named too (SCH2403).

An attribute becomes an `@xsd(attribute)` field, placed after the element fields; a required
attribute (`use="required"`) is non-nullable, any other is `?`. A `fixed` value is imported as a
Schemata default (SCH2405), since Schemata has no equivalent of a value XML forces on every
instance; on a repeated element, which has no default, it is dropped (SCH2405). A default carries
over only when Schemata can write it as a literal: a number as a plain decimal (`.5` becomes `0.5`,
`1e5` becomes `100000`), a boolean's `1` and `0` as `true` and `false`, a string quoted, and an enum
value by its imported name. One with no Schemata literal, such as `INF`, a `dateTime`, a value of a
complex type, or a value its enum does not have, is dropped (SCH2403). `xs:documentation` becomes a
`///` doc comment, each line without the indentation the schema gave it; `xs:appinfo` is dropped
silently.

#### Derivation and polymorphism

Schemata has no base-record relationship, so an `extension` flattens the base type's fields in
first, ahead of its own (SCH2403). A `restriction` of a complex type keeps only its own content and
inherits the base's attributes, less any it prohibits (SCH2403). An extension of `xs:anyType` has
an empty base. A `simpleContent` extension or restriction becomes a record with a `value` field of
the base's simple type, beside the type's attributes (SCH2403). When the base is itself a complex
type with simple content, the chain is followed to the simple type at its root, whose type `value`
takes with every facet along the way, and the attributes declared along the chain are inherited;
only a chain that reaches a type with element content imports `value` as `string` (SCH2403).

What XSD expresses through derivation, a value that is one of several types, Schemata expresses as
a union, and the importer makes that union. A polymorphic head is either of two things:

- An abstract complex type. Its members are the non-abstract complex types that derive from it, at
  any depth and in any input; an abstract type in between stands for its own members.
- A substitution group's head element. Its members are the types of the global elements whose
  `substitutionGroup` chain reaches it, plus the head's own type when the head is not abstract.

A head with two or more members becomes a union of them, declared in the head's namespace and
importing the namespaces its members live in, and every element of the abstract type, and every
reference to the head element, holds that union (SCH2403; the regenerated XSD writes it as a
choice):

```xml
<xs:complexType name="ShapeType" abstract="true">
  <xs:sequence><xs:element name="label" type="xs:string"/></xs:sequence>
</xs:complexType>
<xs:complexType name="CircleType">
  <xs:complexContent><xs:extension base="ShapeType">
    <xs:sequence><xs:element name="radius" type="xs:double"/></xs:sequence>
  </xs:extension></xs:complexContent>
</xs:complexType>
<xs:complexType name="SquareType">
  <xs:complexContent><xs:extension base="ShapeType">
    <xs:sequence><xs:element name="side" type="xs:double"/></xs:sequence>
  </xs:extension></xs:complexContent>
</xs:complexType>
<xs:complexType name="DrawingType">
  <xs:sequence><xs:element name="shape" type="ShapeType" maxOccurs="unbounded"/></xs:sequence>
</xs:complexType>
<xs:element name="drawing" type="DrawingType"/>
```

imports as

```
union Shape = Circle | Square

@xsd(root = false)
record Circle { label: string radius: float64 }

@xsd(root = false)
record Square { label: string side: float64 }

record Drawing { shape: list<Shape>(min = 1) }
```

A substitution group's union is named after its head element, or `<Head>Choice` when a type or
another record of that namespace already has that name; when the head element's type is an
abstract type with exactly the same members, the type's union serves both. A head with one member
lowers to that member's type wherever it is used, with no union (SCH2403). An abstract type with
no members stays a record, its `abstract` dropped (SCH2405), and a head element with no members
stays an element of its own type. A member element declared with an inline type has no type a
union could name, and one of a simple type or of a type that cannot be resolved has no record a
union could hold, so each is left out of the union (SCH2405); a member element with no type at all
takes its head's. `block`, `final`, `blockDefault`, and `finalDefault` have no Schemata
equivalent and are dropped, once per document (SCH2405).

Only an abstract type or a substitution group makes a union. A concrete base type stays a record
of its own fields, and each type derived from it a separate record with the base's fields flattened
in. An instance that uses `xsi:type` to put a derived type where the concrete base is declared is
therefore not valid against the regenerated XSD, and the derived type's extra fields have no place
in the base's record. If such variants matter, make the base abstract, or list the variants as a
substitution group or a choice, before importing.

#### Wildcards, open content, and mixed

Open content becomes a field that holds the open part as text, with an `@xsd` key the xsd target
reads to write the same construct back:

- An `xs:any` becomes a field named `any` (`any_2`, `any_3` for a record's later wildcards) with
  `@xsd(any)`: `string`, `string?` with `minOccurs="0"`, or `list<string>` when it repeats.
- An `xs:anyAttribute` becomes `attributes: map<string, string>` with `@xsd(any_attribute)`.
- `mixed="true"` adds `text: string?` with `@xsd(mixed)`, placed after the element fields.
- An element of type `xs:anyType`, or one declared with no type at all, becomes a `string` field
  with `@xsd(any_type)`.

A wildcard's `processContents` is kept as `@xsd(process = "strict")` or `@xsd(process = "skip")`.
The xsd target writes `lax` when the key is absent, so `lax` needs no key; XSD's own default when
the attribute is missing is `strict`, so a wildcard that says nothing gets
`@xsd(process = "strict")`. A namespace constraint other than `##any` is kept as
`@xsd(wildcard = "##other")`, or whatever list it names. When a declared element or attribute
already takes one of these names, the synthesized field gives way: `any_2`, `mixed_text`,
`any_attributes`.

```xml
<xs:complexType name="ParaType" mixed="true">
  <xs:sequence>
    <xs:element name="bold" type="xs:string" minOccurs="0" maxOccurs="unbounded"/>
    <xs:any namespace="##other" minOccurs="0" maxOccurs="unbounded"/>
    <xs:element name="extra" type="xs:anyType" minOccurs="0"/>
  </xs:sequence>
  <xs:attribute name="lang" type="xs:string"/>
  <xs:anyAttribute processContents="lax"/>
</xs:complexType>
<xs:element name="para" type="ParaType"/>
```

imports, with no diagnostics, as

```
record Para {
  bold:       list<string>
  @xsd(any)
  @xsd(process = "strict")
  @xsd(wildcard = "##other")
  any:        list<string>
  @xsd(any_type) extra:      string?
  @xsd(mixed) text:       string?
  @xsd(attribute) lang:       string?
  @xsd(any_attribute) attributes: map<string, string>
}
```

A wildcard that is a branch of a choice the importer makes a union has no type to be a member, so
it is dropped (SCH2405); a choice of nothing but wildcards makes a record of `@xsd(any)` fields
instead of a union.

#### Content models

A `sequence`'s children become fields in order. An element with `maxOccurs` greater than one
becomes `list<T>`, with `min`/`max` from `minOccurs`/`maxOccurs`; `nillable="true"` adds `?` to
the element type, giving `list<T?>`; a `default` on a repeated element is dropped (SCH2403), since a
list has no default. An element with `maxOccurs="0"` can never appear and is dropped (SCH2405). A
single element with `minOccurs="0"` becomes `T?`, unless it carries a `default`, which already
implies optional presence. An element with both a `type` and an inline type keeps the `type`
(SCH2403).

An `xs:all` that is a type's content becomes a record with `@xsd(all)`, which the xsd target
writes back as `xs:all`; each element is required or, with `minOccurs="0"`, `?`, since `xs:all`
holds an element at most once. One reached through an extension's base is read as a sequence
(SCH2403), and one inside a group inside a sequence is flattened into the record (SCH2403).

A `sequence` nested inside another that occurs exactly once adds nothing, and its elements are
flattened in place, silently. One that is optional or repeats becomes a record nested in the type,
named after its first element (`<First>Group`), held by a field `<first>_group` that is `?` or a
list to match (SCH2403); a second one starting with the same element is numbered (`XGroup2`).

A complex type whose whole content model is a `choice` becomes a `union`, one member per branch:

- An element branch is its type. The branch element's own name is not kept, which is reported
  (SCH2403) when it differs from what its type's name would lower to.
- A `sequence` or `xs:all` branch becomes a top-level record with `@xsd(root = false)`, named after
  its first element (`<First>Group`), and the union lists it (SCH2403).
- A `choice` nested in the choice adds its branches to the union's own.
- An element branch of a list type cannot be a union member and imports as `string` (SCH2405); one
  with no type has nowhere to carry `@xsd(any_type)` and is a plain `string` (SCH2404).
- Two or more branches of one type could not be told apart, so each becomes a record of its own,
  named after its element, non-root, holding the type in a `value` field (`record ArchiveTimeStamp
  { value: XAdESTimeStamp }`), and the union lists those records (SCH2403).
- A branch whose element or type cannot be resolved is an error (SCH2401) and no member; a choice
  left with no member at all is an empty record of the union's name, as a union needs one.

Only a choice that occurs once, in a type with no attributes, attribute wildcard, or mixed content,
makes a union; a choice that repeats, or a type carrying any of those, is a record instead,
holding the choice as an inline one (below) beside its attribute and text fields. A union has
nowhere to put `abstract`, so a choice-only type's is dropped (SCH2405). An inline `choice` nested
inside a `sequence` becomes, when every branch is a complex type, a synthesized union named
`<Record>Choice` held in a field called `choice` (SCH2403); otherwise each branch, a wildcard among
them, becomes its own optional field (SCH2403), a list when the choice repeats.

```xml
<xs:complexType name="SettingsType">
  <xs:all>
    <xs:element name="units" type="xs:string"/>
    <xs:element name="zoom" type="xs:int" minOccurs="0"/>
  </xs:all>
</xs:complexType>
<xs:complexType name="TrackType">
  <xs:sequence>
    <xs:element name="name" type="xs:string"/>
    <xs:sequence minOccurs="0" maxOccurs="unbounded">
      <xs:element name="lat" type="xs:double"/>
      <xs:element name="lon" type="xs:double"/>
    </xs:sequence>
  </xs:sequence>
</xs:complexType>
<xs:complexType name="PlaceType">
  <xs:choice>
    <xs:element name="code" type="xs:string"/>
    <xs:sequence>
      <xs:element name="street" type="xs:string"/>
      <xs:element name="city" type="xs:string"/>
    </xs:sequence>
  </xs:choice>
</xs:complexType>
<xs:element name="settings" type="SettingsType"/>
<xs:element name="track" type="TrackType"/>
<xs:element name="place" type="PlaceType"/>
```

imports as

```
@xsd(all)
record Settings { units: string zoom: int32? }

record Track {
  name:      string
  lat_group: list<LatGroup>

  record LatGroup { lat: float64 lon: float64 }
}

union Place = string | StreetGroup

@xsd(root = false)
record StreetGroup { street: string city: string }
```

reporting the `lat`/`lon` sequence, the `code` branch's name, and the `street` branch's record
(SCH2403 each). A named `group` or `attributeGroup` expands in place wherever it is referenced; a
group reference that repeats becomes a record named after the group, held by a field of the same
name (SCH2403). The xsd target's own rendering of `map<K, V>` is recognized on the way back in and
becomes `map<K, V>` again, not a record.

#### Simple types

An `xs:list` simple type becomes `list<T>` of its item type, with `@xsd(list)` so the xsd target
writes it back as a whitespace-separated list rather than a repeated element; `length`,
`minLength`, and `maxLength` on a restricted list become the list's `min` and `max`, and any other
facet on it is dropped (SCH2404). An item type that is itself a list imports as `string` (SCH2403),
and a repeated element of a list type becomes a list of lists (SCH2403).

An `xs:union` simple type becomes what its members have in common, reported (SCH2403) either way:
the one builtin they all lower to (`xs:int xs:short` gives `int32`), or, when every member is an
enumeration, one enum of all their values in order, each value once. A named union of enumerations
is a top-level enum of that name; an inline one is nested in the record that uses it. Any other
union imports as `string`.

```xml
<xs:simpleType name="Ints"><xs:list itemType="xs:int"/></xs:simpleType>
<xs:simpleType name="AnySize"><xs:union memberTypes="Size Extra"/></xs:simpleType>
```

given `Size` with the values `s` and `m` and `Extra` with `xl`, a field of `AnySize` holds
`enum AnySize { s, m, xl }` and a field of `Ints` is `@xsd(list) chest: list<int32>`.

#### Forms and includes

The xsd target writes elements qualified and attributes unqualified. A schema whose
`elementFormDefault` or `attributeFormDefault` says otherwise carries
`@xsd(element_form = "unqualified")` or `@xsd(attribute_form = "qualified")` on its namespace, which
the xsd target writes back. `elementFormDefault` is `unqualified` when a schema does not set it, so
a schema that says nothing gets `@xsd(element_form = "unqualified")`. A `form` on one element or
attribute that differs from its schema's default has no Schemata equivalent and is dropped
(SCH2403), as are an included document's own form defaults when they differ from the including
document's (SCH2403).

An `xs:include` merges the included document into the including one, and includes of includes in
turn; an include cycle stops at the first document it would visit twice. An included document with
no `targetNamespace` is a chameleon: it takes the including document's namespace, references and
all, so `type="Foo"` written inside it means the includer's `Foo`. An included document that
declares another namespace is an error (SCH2401), and so is one that declares a namespace when the
including document has none. `xs:redefine` and `xs:override` are read as includes of the documents
they name, and the redefinitions themselves are dropped (SCH2405). A `schemaLocation` with `..`
segments is normalized, so one that climbs out of its directory still finds an input.

An unresolved import, include, or type reference is an error (SCH2401), as are two inputs declaring
the same namespace without one including the other, and two elements lowering to the same field. So
is a simple type, group, or attribute group whose references lead back to itself, a construct
missing an attribute it cannot be read without (a `group` with no `name`, an `extension` with no
`base`), and a document whose `DOCTYPE` names an external DTD, which the importer refuses to read. A
`DOCTYPE` with only an internal subset is read, and nothing external it names is ever loaded.

#### What each construct becomes

| XSD construct | Imported as | Code |
|---|---|---|
| a named complex type | a record | SCH2403 when its name will not regenerate |
| a global element of a named type | the type's record, marked as a root | SCH2403 when its name will not regenerate |
| a global element with an anonymous type | a top-level record | SCH2403 when its name will not regenerate |
| a second global element of one type, one of a simple type, one of another namespace's type | nothing | SCH2405 |
| an anonymous complex type | a record nested under the element | |
| an element | a field; `T?` when optional, `list<T>` when repeated | |
| an element with both a `type` and an inline type | a field of the `type` | SCH2403 |
| `maxOccurs="0"` | nothing | SCH2405 |
| an attribute | an `@xsd(attribute)` field | |
| a `default` with a Schemata literal | a default | |
| a `default` without one, or on a repeated element | nothing | SCH2403 |
| `fixed` | a default; nothing on a repeated element | SCH2405 |
| an enumeration | an enum | |
| a facet | a refinement, per the type table | SCH2404 when dropped |
| `xs:documentation` | a doc comment | |
| `xs:appinfo` | nothing, silently | |
| a nested sequence occurring once | its elements, flattened | |
| an optional or repeated nested sequence | a nested `<First>Group` record | SCH2403 |
| `xs:all` as a type's content | an `@xsd(all)` record | |
| `xs:all` through an extension base, or in a group in a sequence | a sequence, flattened | SCH2403 |
| a choice as a type's whole content | a union | SCH2403 when a branch name differs |
| a sequence or `xs:all` branch of a choice | a top-level `<First>Group` record the union lists | SCH2403 |
| two branches of one type | a record per branch holding `value` | SCH2403 |
| an inline choice in a sequence | a `<Record>Choice` union, or one optional field per branch | SCH2403 |
| a group or attribute group reference | its content, in place | |
| a repeated group reference | a record named after the group | SCH2403 |
| an extension | the base's fields flattened in | SCH2403 |
| a complex restriction | its own content and the base's attributes | SCH2403 |
| simple content | a record with a `value` field | SCH2403 |
| an abstract type with members | a union of them | SCH2403 |
| a substitution group with members | a union of them | SCH2403 |
| a head with one member | that member's type | SCH2403 |
| an abstract type with no members | a record | SCH2405 |
| a substitution member with an inline, simple, or unresolved type | nothing | SCH2405 |
| `abstract` on a choice-only type | nothing | SCH2405 |
| `block`, `final`, `blockDefault`, `finalDefault` | nothing | SCH2405 |
| `xs:any` | an `@xsd(any)` string field | |
| `xs:any` as a branch of a union | nothing | SCH2405 |
| `xs:anyAttribute` | an `@xsd(any_attribute)` map field | |
| `mixed="true"` | an `@xsd(mixed)` text field | |
| `xs:anyType`, or an element with no type | an `@xsd(any_type)` string field | |
| a list simple type | an `@xsd(list)` list | |
| a list of lists | `string` | SCH2403 |
| a union simple type | its shared builtin, an enum of its values, or `string` | SCH2403 |
| a choice branch of a list type | a `string` member | SCH2405 |
| a choice branch with no type | a `string` member | SCH2404 |
| `elementFormDefault`, `attributeFormDefault` | `@xsd(element_form)`, `@xsd(attribute_form)` | |
| a `form` that differs from the default | nothing | SCH2403 |
| `xs:import` | an `import` of the namespace | |
| `xs:include` | the included declarations, merged | |
| `xs:redefine`, `xs:override` | the named document, merged; the redefinitions dropped | SCH2405 |
| `xs:notation` | nothing | SCH2405 |
| an identity constraint, other than the map form | nothing | SCH2405 |

### From Protobuf

`import --from proto` reads proto2, proto3, and editions files. An `import` resolves among the
inputs by its path under their roots (the directories named on the command line), then beside the
importing file, then under each root on disk; a file found that way is read and imported too. An
import of protoc's own files under `google/protobuf/`, `timestamp.proto` and `descriptor.proto`
alike, needs no file at all, since their types are known by name. Files under one root that
declare one package are one package to `protoc`, so they import as one namespace, the package's,
with each segment lower-snaked if need be (SCH2402). Two files whose namespaces coincide but whose packages differ are an error (SCH2401).

| Protobuf type | Schemata type | Notes |
|---|---|---|
| `double`, `float` | `float64`, `float32` | |
| `int32`, `int64`, `bool`, `string`, `bytes` | the same | |
| `sint32`, `sfixed32` | `int32` | SCH2404 |
| `sint64`, `sfixed64` | `int64` | SCH2404 |
| `uint32`, `fixed32` | `int64(min = 0, max = 4294967295)` | SCH2404 |
| `uint64`, `fixed64` | `int64(min = 0)` | SCH2404: values above `int64`'s range are lost |
| a message or enum | a reference, spelled as Schemata resolves it from the field | |
| `google.protobuf.Timestamp`, `Duration` | `instant`, `duration` | |
| a wrapper, such as `google.protobuf.StringValue` | its scalar, `?` | SCH2403: the regenerated field is `optional`, not a wrapper |
| `google.protobuf.Any` | `bytes` | SCH2404 |
| `google.protobuf.Struct`, `Value`, `ListValue`, `FieldMask`, `Empty` | `string` | SCH2404 |
| any other `google.protobuf` type, such as `Api` | `string` | SCH2404 |

A proto3 field with no label is required, `T`; an `optional` one is `T?`; a `repeated` one is
`list<T>`. A proto2 `required` field is `T`, and an `optional` one `T?` unless it has a
`[default]`. A message-typed field has presence on the wire whatever its label, so Protobuf alone
cannot say whether a Schemata field of a record type was `T` or `T?`: it imports as `T`, unless a
note says `T?` (below). Field numbers become ordinals, and names that are not lower_snake are
lower-snaked with `@proto(name = "…")` restoring them; a message or enum name that is not
UpperCamel gets the same treatment, and a field named after a keyword takes a `_value` suffix.
Two fields, values, or declarations that lower to one name are an error (SCH2401). Leading comments become doc comments,
with a trailing comment on the same line added after a blank line; a comment separated from its
declaration by a blank line is not a doc and is dropped silently.

A `map<K, V>` becomes `map<K, V>`. A `string`, `int32`, or `int64` key carries over; a `sint` or
`sfixed` key becomes `int32` or `int64`, and a `uint` or `fixed` key `int64` (SCH2404). Schemata has
no `bool` map key, so a map with one is dropped, field and all (SCH2405).

An enum loses what the Protobuf target adds to it. The target writes each value as
`<UPPER_SNAKE(Enum)>_<VALUE>` after a synthesized `<UPPER_SNAKE(Enum)>_UNSPECIFIED = 0`, so the
prefix comes off (`STATUS_PENDING` in `Status` becomes `pending`) and that zero value is dropped
silently. A zero value spelled another way that means "not set", such as `UNKNOWN`, `UNSET`, or
`LEVEL_UNKNOWN`, is dropped too, reported because the regenerated enum names it
`<PREFIX>_UNSPECIFIED` (SCH2403). A value the target would spell differently, such as `RED` in
`Color`, keeps its spelling in `@proto(name = "…")`. Value numbers become ordinals, unless one is
zero or negative after the zero value is set aside: then the values are renumbered in order and
carry no ordinals (SCH2403). A Schemata enum needs a value, so when the zero value is the only one,
it stays, under a spelling the target can write beside the zero value it synthesizes (SCH2403). An
alias, a second name for a number, is dropped (SCH2405).

A message that is exactly one `oneof`, with nothing else in it and members of distinct types, is
how the Protobuf target writes a union, and imports as one, its field numbers becoming the members'
ordinals. A `oneof` not named `kind` (SCH2403), or a member field named other than the target
would name it (SCH2403), is reported, since the regenerated message will differ; a member's
`deprecated` and default are dropped (SCH2405). Any other `oneof` becomes one nullable field per
member (SCH2403): at most one of them is set, which Schemata cannot say.

`reserved` numbers and ranges become `reserved` ordinals and reserved names become reserved names,
lower-snaked when need be (SCH2403). An enum's reserved numbers below 1 are dropped (SCH2403),
since Schemata ordinals start at 1.

Options steer how code is generated and how fields are encoded, not what the data is, so they are
ignored silently: file options such as `java_package` and `go_package`, message and enum options,
`packed`, editions `features`, and custom options in parentheses. One is read: `deprecated`, on a
message, enum, field, or value, becomes `@deprecated`. `json_name` is dropped (SCH2405), since
Schemata derives every JSON name itself.

The Protobuf target's `// schemata:` comments are read back; a hand-written file may use them too.
A trailing `// schemata: <type>` after a field gives the Schemata type the proto type stands for,
with its refinements and nullability, and `; default = <literal>` after it, or
`// schemata: default = <literal>` alone, the default. The note applies only when it fits the field:
a proto `string` may carry `string`, `uuid`, `decimal`, `date`, or `time`; every other scalar only
itself; a message or enum field only that type's name; a list or map element by element. A note
that does not fit, or does not read as a type, is ignored (SCH2403), and so is a default that names
no value of its enum.

```proto
message Order {
  string id = 1;  // schemata: uuid
  Payment payment = 2;  // schemata: Payment?
  int64 points = 3;  // schemata: int64(min = 0, max = 4294967295)
}
```

gives `#1 id: uuid`, `#2 payment: Payment?`, and `#3 points: int64(min = 0, max = 4294967295)`,
with no diagnostics.

A proto2 file is read the same way, with `[default = …]` carried when Schemata can write it as a
literal (a string, a boolean, a decimal or hex or octal integer, a decimal number, or an enum value
by its imported name) and dropped otherwise (SCH2403). Groups, `extensions` ranges, and `extend`
blocks are dropped (SCH2405). An editions file (`edition = "2023"`) is read as proto3, reported
once (SCH2403): a field is `T?` only when it says `optional`, whatever its features say.
`import public` re-exports nothing in Schemata (SCH2403); the importing file imports the namespace
directly.

Most of the above, in one file, `protos/shop/orders.proto`:

```proto
syntax = "proto3";

package shop.orders;

import "google/protobuf/timestamp.proto";
import "google/protobuf/wrappers.proto";

option java_package = "com.example.shop";

enum Status {
  STATUS_UNSPECIFIED = 0;
  STATUS_PENDING = 1;
  STATUS_PAID = 2;
}

// One checkout.
message Order {
  string id = 1;  // schemata: uuid
  Status status = 2;
  repeated Line lines = 3;
  map<string, string> labels = 4;
  google.protobuf.Timestamp placed_at = 5;
  google.protobuf.StringValue note = 6;
  optional int32 priority = 7;
  Payment payment = 8;  // schemata: Payment?
  uint32 points = 9;
  reserved 10, 12 to 14;
  reserved "coupon";

  message Line {
    string sku = 1;
    int64 quantity = 2;
  }
}

message Payment {
  oneof kind {
    Card card = 1;
    string voucher = 2;
  }
}

message Card {
  string last4 = 1;
}
```

`schemata import --from proto protos` writes `out/import/shop/orders.schemata`:

```
namespace shop.orders

enum Status { #1 pending, #2 paid }

/// One checkout.
record Order {
  #1 id:        uuid
  #2 status:    Status
  #3 lines:     list<Line>
  #4 labels:    map<string, string>
  #5 placed_at: instant
  #6 note:      string?
  #7 priority:  int32?
  #8 payment:   Payment?
  #9 points:    int64(min = 0, max = 4294967295)

  record Line { #1 sku: string #2 quantity: int64 }

  reserved #10, #12..#14, "coupon"
}

union Payment = #1 Card | #2 string

record Card { #1 last4: string }
```

and reports the wrapper (SCH2403), the `uint32` (SCH2404), and the `voucher` member, which the
regenerated `oneof` will name `string` (SCH2403).

A `service` imports as a service, named as a message would be, and each `rpc` as an operation; a
leading comment becomes a doc comment and `option deprecated = true` becomes `@deprecated`, on the
service and on the rpc alike. A service and a message or enum that lower to one name are an error
(SCH2401). An rpc's name is lower-snaked: `GetOrder` becomes `get_order`. The target writes an
operation's name back in UpperCamel, so when that does not give the rpc's name again, as `GetURL`
gives `get_url` and then `GetUrl`, the operation keeps `@proto(name = "GetURL")` and the rename is
reported (SCH2402). Two rpcs of a service that lower to one name are an error (SCH2401).

A request or a response that names a message becomes a reference to the record or union it
imports as, `stream` kept. `google.protobuf.Empty` is no payload: an empty `()` for the request, no
`: Response` for the response. An operation carries only a record or a union, so an rpc is dropped
(SCH2405) when either side is anything else: an enum, a type that does not resolve, any other
`google.protobuf` type, or a `stream` of `google.protobuf.Empty`, which Schemata cannot write.

The notes the target writes on a service are read back. The note after an rpc gives its ordinal
and binding, `// schemata: #5; delete "/items/{name}"`, either part alone or both; a note line
inside the service, `// schemata: reserved #6, "archive"`, gives its `reserved`. An operation with
no ordinal in its note takes its position among the rpcs kept, counting from 1. A note that does
not read is ignored and the operation kept (SCH2403).

From `schemata-cli/src/test/resources/import/proto-kitchen/kitchen/services.proto`:

```proto
// The ordering API.
service Orders {
  rpc Get (kitchen.maps.Item) returns (kitchen.maps.Item);  // schemata: get "/items/{name}"
  rpc Watch (kitchen.maps.Item) returns (stream kitchen.maps.Item);
  // Forget an item.
  rpc Forget (kitchen.maps.Item) returns (google.protobuf.Empty) {  // schemata: #5; delete "/items/{name}"
    option deprecated = true;
  }
  rpc Submit (Request) returns (kitchen.maps.Item);
  rpc GetURL (Request) returns (Request);  // schemata: #7
  // schemata: reserved #6, "archive"
}
```

imports as:

```
/// The ordering API.
service Orders {
  #1 get(kitchen.maps.Item): kitchen.maps.Item  get "/items/{name}"
  #2 watch(kitchen.maps.Item): stream kitchen.maps.Item
  /// Forget an item.
  @deprecated #5 forget(kitchen.maps.Item)  delete "/items/{name}"
  #4 submit(Request): kitchen.maps.Item
  @proto(name = "GetURL") #7 get_url(Request): Request
  reserved #6, "archive"
}
```

and the service gives one report, `service 'Orders': rpc 'GetURL': renamed to 'get_url'` (SCH2402).
`submit` has no note, so it takes its position, `#4`.

#### What each construct becomes

| Protobuf construct | Imported as | Code |
|---|---|---|
| `syntax = "proto2"` or `"proto3"` | read | |
| `edition = "…"` | read as proto3 | SCH2403 |
| `package` | the namespace, or `@proto(package)` beside a path's | SCH2402 when derived |
| `import` | an `import` of the namespace the file lowers to | SCH2401 when unresolved |
| `import public` | an `import`; nothing re-exported | SCH2403 |
| a message | a record, nested messages and enums nested in it | |
| a message that is exactly one `oneof` of distinct types | a union | SCH2403 when its names differ from the target's |
| any other `oneof` | one nullable field per member | SCH2403 |
| a field | a field with its number as its ordinal | |
| a field of a widened scalar or well-known type | per the type table | SCH2403, SCH2404 |
| a map with a `bool` key | nothing | SCH2405 |
| a map with another integer key | `map<int32, V>` or `map<int64, V>` | SCH2404 |
| an enum | an enum without the prefix and the synthesized zero value | |
| a zero value spelled another way | nothing | SCH2403 |
| an enum value numbered 0 or below | values renumbered without ordinals | SCH2403 |
| an alias | nothing | SCH2405 |
| `reserved` | `reserved` | SCH2403 when a name is renamed or a number is below 1 |
| `// schemata:` note | the type and default it gives; after an rpc, its ordinal and binding; in a service, its `reserved` | SCH2403 when ignored |
| a proto2 `[default]` | a default | SCH2403 when it has no Schemata literal |
| `[deprecated = true]`, `option deprecated = true` | `@deprecated`, on a service and an rpc too | SCH2405 on a union member |
| `json_name` | nothing | SCH2405 |
| any other option | nothing, silently | |
| a group | nothing | SCH2405 |
| `extensions`, `extend` | nothing | SCH2405 |
| a service | a service | |
| an `rpc` | an operation, its name lower-snaked | SCH2402 when `@proto(name)` keeps its spelling |
| an rpc's `google.protobuf.Empty` | no request or no response | |
| an rpc's request or response that is not a message of the inputs, or a `stream` of `Empty` | nothing; the rpc is dropped | SCH2405 |
| a doc comment | a doc comment | |

### From SQL

`import --from sql` reads Postgres DDL, hand-written or as `pg_dump` writes it. Every input is read
into one catalog, so a constraint, index, or comment may name a table another file creates, as the
sql target itself writes a foreign key between two files into the later one. A statement the reader
cannot parse is an error (SCH2401), and reading resumes after the next `;`.

The statements read are `CREATE SCHEMA`, `CREATE TABLE`, `CREATE [UNIQUE] INDEX`,
`ALTER TABLE … ADD` of a constraint, and `COMMENT ON TABLE` and `COMMENT ON COLUMN`. Statements with
no bearing on the shape of the data are ignored silently: `SET`, `SELECT`, `GRANT`, `REVOKE`,
transactions, `DROP`, every other `ALTER`, `CREATE SEQUENCE`, `EXTENSION`, `ROLE`, and the like,
and a comment on anything but a table or a column. Statements that define something Schemata has
no place for, or carry data, are dropped (SCH2405): views, types, domains, functions, procedures,
triggers, rules, policies, and the rest of `CREATE`'s forms, `CREATE TEMP TABLE`,
`CREATE TABLE … AS`, `OF`, and `PARTITION OF`, `ALTER TABLE … ADD COLUMN`, `DO`, `INSERT`, `UPDATE`,
`DELETE`, `COPY`, and an index on an expression. On a table, `INHERITS`, `PARTITION BY`, `WITH`,
`TABLESPACE`, `USING`, `ON COMMIT`, `UNLOGGED`, `LIKE`, and an `EXCLUDE` constraint are dropped
(SCH2405), and a `GENERATED … STORED` column imports as a plain column (SCH2405).

Each schema becomes one namespace, named as the command section describes; a schema sharing its
file with others takes its own name, or the stem when its name is not a namespace name. Tables
created without a schema are in `public`, which is never a namespace's name. Every table becomes a
record named after it in UpperCamel, with `@sql(table = "…")` when the name will not regenerate,
and each column becomes a field, lower-snaked with `@sql(column = "…")` when need be, `?` when the
column allows `NULL` and is not in the primary key. A table created twice is an error, the second
ignored (SCH2401).

| Postgres type | Schemata type | Notes |
|---|---|---|
| `boolean` | `bool` | |
| `integer`, `bigint` | `int32`, `int64` | |
| `real`, `double precision` | `float32`, `float64` | |
| `numeric(p, s)`, `numeric(p)` | `decimal(p, s)`, `decimal(p, 0)` | |
| `numeric` | `decimal(38, 9)` | SCH2403 |
| `text` | `string` | |
| `varchar(n)` | `string(max = n)` | |
| `bytea`, `uuid`, `date`, `time` | `bytes`, `uuid`, `date`, `time` | |
| `timestamptz`, `interval` | `instant`, `duration` | |
| `smallint`, `serial`, `smallserial` | `int32` with `@sql(type)` | SCH2404; a serial's generation SCH2403 |
| `bigserial` | `int64` with `@sql(type)` | SCH2404; its generation SCH2403 |
| `char(n)` | `string(min = n, max = n)` with `@sql(type)` | SCH2404 |
| `timestamp`, `timetz`, `interval` with fields | `instant`, `time`, `duration` with `@sql(type)` | SCH2404 |
| `money` | `decimal(19, 4)` with `@sql(type)` | SCH2404 |
| any other type, such as `citext`, `inet`, or `jsonb` with no note | `string` with `@sql(type)` | SCH2404 |
| `T[]` | `list<T>` | SCH2404 when `T` is not a type the sql target writes |
| `json` with a note | as `jsonb` | SCH2404 |

Spellings are read as Postgres reads them: `character varying(n)` is `varchar(n)`, `int4` is
`integer`, `timestamp with time zone` is `timestamptz`, and so on. A type the sql target would not
write for the field keeps its spelling in `@sql(type = "…")`, so the regenerated column is the same.
An identity column keeps its type, its generation dropped (SCH2403). A `DEFAULT` carries over when
it is a literal of the field's type, and is dropped otherwise, `now()` among them (SCH2403).

A `jsonb` column whose note names a record, a list, or a map holds that type, stored as json:
`@sql(strategy = json)`, which a map needs no key for since json is a map's default. A record the
note names that no table defines, which is every record the sql target stores as json, becomes an
empty record nested beside the field, reported (SCH2403), since the DDL never held its fields. A
note naming a type in another namespace (`other.ns.Address`) imports that namespace instead.

```sql
CREATE TABLE shop.customer (
  id uuid PRIMARY KEY,
  address jsonb NOT NULL,  -- schemata: Address
  prefs jsonb,  -- schemata: map<string, string>
  extra jsonb
);
```

```schemata
namespace shop

record Customer {
  @sql(key) id:      uuid
  @sql(strategy = json) address: Address
  prefs:   map<string, string>?
  @sql(type = "jsonb") extra:   string?

  record Address {}
}
```

#### Checks

A `CHECK` on one column becomes refinements on its field when every part of it says one:
`c >= n`, `c <= n`, `c BETWEEN a AND b`, `c > n` and `c < n` on an integer (shifted by one), the
same on `char_length(c)`, `length(c)`, or `octet_length(c)` for a string's or bytes' length, and
`c ~ '…'` for a pattern. `c ~* '…'` becomes a pattern too, its case-insensitivity dropped (SCH2404). A
`c IN ('a', 'b')` check on a `text` column makes the column an enum. The all-or-none and presence
checks the structures below use are read by those structures. Any other check is dropped
(SCH2405), quoted in the message.

```sql
CREATE TABLE shop."order" (
  id uuid PRIMARY KEY,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid')),
  qty integer NOT NULL CHECK (qty BETWEEN 1 AND 99),
  code text CHECK (code ~ '^[A-Z]{3}$')
);
```

```schemata
namespace shop

record Order {
  @sql(key) id:     uuid
  status: Status = pending
  qty:    int32(min = 1, max = 99)
  code:   string(pattern = "^[A-Z]{3}$")?

  enum Status { pending, paid }
}
```

An enum is nested in its record and named after its column, with a value that is not lower_snake
lower-snaked and reported (SCH2403), since the regenerated check will list it that way. A note
naming a type on an `IN`-checked column names the enum.

#### Structures

The sql target spreads one record over columns, constraints, and other tables. The importer reads
each structure back by its shape, never by a constraint's name, so a hand-written file reads the
same as the target's own output, and a table with none of these shapes is a plain record.

Keys. A primary key flags its fields `@sql(key)` when they are in key order among the fields, and
is written on the record otherwise, `@sql(key = (code, tenant_id))`. A table with no primary key
imports without one (SCH2403); add `@sql(key)` by hand before compiling to SQL. A key field cannot
be a reference, so a key column a foreign key also covers, as in a join table, stays a plain key
field typed and named as its column, and the foreign key is dropped (SCH2405). A `UNIQUE`
constraint or a plain index over exactly one field's columns becomes `@sql(unique)` or
`@sql(index)`; one over the primary key adds nothing; one over several fields, a partial index, and
an index using another method than `btree` are dropped (SCH2405).

```sql
CREATE TABLE shop.plan (
  tenant_id uuid NOT NULL,
  code varchar(8) NOT NULL,
  name text NOT NULL,
  PRIMARY KEY (code, tenant_id)
);
```

```schemata
namespace shop

@sql(key = (code, tenant_id))
record Plan { tenant_id: uuid code: string(max = 8) name: string }
```

References. A foreign key to the whole primary key of another table's record is a field of that
record's type, `?` when its columns allow `NULL`, named `f` when its columns are `<f>_<key column>`,
and otherwise after its first column, kept with `@sql(column)` (SCH2403). `ON DELETE`, `ON UPDATE`,
`DEFERRABLE`, `INITIALLY DEFERRED`, and `MATCH FULL` have no Schemata equivalent and are dropped
(SCH2403). A foreign key to anything but a primary key is dropped (SCH2405), and one to a table not
among the inputs is an error (SCH2401), its column kept as a plain field.

```sql
CREATE TABLE shop.customer (id uuid PRIMARY KEY);
CREATE TABLE shop."order" (
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL REFERENCES shop.customer (id),
  referrer_id uuid REFERENCES shop.customer (id)
);
```

```schemata
namespace shop

record Customer { @sql(key) id: uuid }

record Order { @sql(key) id: uuid customer: Customer referrer: Customer? }
```

Child tables. A table named `<parent>_<field>`, keyed by the parent's key columns, each prefixed
`<parent>_`, plus `position integer` or `key`, with a cascading foreign key to the parent, is a list
(`position`) or map (`key`) field of the parent, not a record of its own. Its other columns are the
element: one `value` column a scalar or an enum, `value_…` columns under a foreign key a reference,
and any other columns a record nested in the parent and named after the field in the singular
(`lines` gives `Line`), with the child's own child tables as its fields.

```sql
CREATE TABLE shop."order" (id uuid PRIMARY KEY);
CREATE TABLE shop.order_lines (
  order_id uuid NOT NULL REFERENCES shop."order" (id) ON DELETE CASCADE,
  position integer NOT NULL,
  sku text NOT NULL,
  quantity integer NOT NULL,
  PRIMARY KEY (order_id, position)
);
CREATE TABLE shop.order_tags (
  order_id uuid NOT NULL REFERENCES shop."order" (id) ON DELETE CASCADE,
  position integer NOT NULL,
  value text NOT NULL,
  PRIMARY KEY (order_id, position)
);
CREATE TABLE shop.order_prices (
  order_id uuid NOT NULL REFERENCES shop."order" (id) ON DELETE CASCADE,
  key text NOT NULL,
  value numeric(10, 2) NOT NULL,
  PRIMARY KEY (order_id, key)
);
```

```schemata
namespace shop

record Order {
  @sql(key) id:     uuid
  lines:  list<Line>
  @sql(strategy = table) tags:   list<string>
  @sql(strategy = table) prices: map<string, decimal(10, 2)>

  record Line { sku: string quantity: int32 }
}
```

Unions. A `<f>_kind` text column whose `IN` check lists member names, beside columns named
`<f>_<member>` or `<f>_<member>_…`, is a union field `f`, its union nested in the record. A member
named after a builtin with no columns of its own is that builtin; a member with one `<f>_<member>`
column is that column's type; one whose columns a foreign key covers is a reference; any other is a
record of its columns, nested beside the union, each field required when the member's presence
check, `(<f>_kind <> 'm') OR (… IS NOT NULL …)`, names it. A member record that would take a name
another declaration in the record already has is numbered, reported because the regenerated kind
literal follows the new name (SCH2403).

```sql
CREATE TABLE shop."order" (
  id uuid PRIMARY KEY,
  payment_kind text NOT NULL CHECK (payment_kind IN ('card', 'cash', 'uuid')),
  payment_card_last4 varchar(4),
  payment_card_brand text,
  payment_uuid uuid,
  CHECK ((payment_kind <> 'card') OR (payment_card_last4 IS NOT NULL)),
  CHECK ((payment_kind <> 'uuid') OR (payment_uuid IS NOT NULL))
);
```

```schemata
namespace shop

record Order {
  @sql(key) id:      uuid
  payment: Payment

  union Payment = Card | Cash | uuid

  record Card { last4: string(max = 4) brand: string? }

  record Cash {}
}
```

Embedded records. Two or more columns under one prefix `<f>_` with an all-or-none check,
`(a IS NULL AND b IS NULL) OR (a IS NOT NULL AND b IS NOT NULL)`, are a nullable field `f` of a
record nested in the table's, its fields the columns without the prefix, required when the check
names them.

```sql
CREATE TABLE shop.site (
  id uuid PRIMARY KEY,
  home_street text,
  home_zip varchar(10),
  CHECK ((home_street IS NULL AND home_zip IS NULL)
      OR (home_street IS NOT NULL AND home_zip IS NOT NULL))
);
```

```schemata
namespace shop

record Site {
  @sql(key) id:   uuid
  home: Home?

  record Home { street: string zip: string(max = 10) }
}
```

#### Comments and dumps

`COMMENT ON TABLE` becomes the record's doc comment and `COMMENT ON COLUMN` the field's. A
`-- schemata:` comment on a column's line, or alone on the next line when the column's line has
none, is the column's note, read as the Protobuf section describes and applied when it fits the
column; one that does not fit or does not read is ignored (SCH2403). Every other SQL comment is
ignored.

`pg_dump` splits a table across statements, and the reader puts it back together: constraints
added by `ALTER TABLE ONLY … ADD CONSTRAINT` attach to their table, an
`ALTER COLUMN … ADD GENERATED … AS IDENTITY` marks the column an identity, a
`SET DEFAULT nextval(…)` on an `integer`, `bigint`, or `smallint` column reads as the `serial` it
expanded from, casts such as `'pending'::text` are read through, and `= ANY (ARRAY[…])` reads as
`IN (…)`. The settings, ownership, and sequence statements around them are ignored, so a dump
imports as the DDL it was made from does.

#### What each construct becomes

| Postgres construct | Imported as | Code |
|---|---|---|
| `CREATE SCHEMA` | a namespace, or `@sql(schema)` beside a path's | SCH2402 when derived |
| `CREATE TABLE` | a record | |
| a table created twice | the first | SCH2401 |
| a column | a field of the type in the type table | SCH2404 when widened |
| `NOT NULL` | a required field | |
| `DEFAULT` with a literal of the field's type | a default | |
| any other `DEFAULT` | nothing | SCH2403 |
| an identity or serial column | its integer type | SCH2403 |
| `GENERATED … STORED` | a plain column | SCH2405 |
| a single-column check of a refinement's shape | refinements | SCH2404 for `~*` |
| `IN` check on a `text` column | a nested enum | SCH2403 when a value is renamed |
| any other check | nothing | SCH2405 |
| `PRIMARY KEY` | `@sql(key)` | |
| no primary key | no key | SCH2403 |
| a foreign key over a primary key column | nothing; the column is a plain key field | SCH2405 |
| `UNIQUE` or an index over one field | `@sql(unique)` or `@sql(index)` | |
| `UNIQUE` or an index over several fields, partial, or not `btree` | nothing | SCH2405 |
| a foreign key to a primary key | a reference field | |
| `ON DELETE`, `ON UPDATE`, deferral, `MATCH FULL` | nothing | SCH2403 |
| a foreign key column not named `<field>_<key>` | a field named after the column | SCH2403 |
| a foreign key to anything else | nothing | SCH2405 |
| a foreign key to a table not in the inputs | a plain field | SCH2401 |
| a child table | a list or map field | |
| `<f>_kind` with member columns | a union field | SCH2403 when a member record is numbered |
| a column group with an all-or-none check | a nullable embedded record | |
| `jsonb` with a note | the noted type, stored as json | SCH2403 for a record the DDL does not define |
| `COMMENT ON TABLE`, `COMMENT ON COLUMN` | doc comments | |
| `-- schemata:` note | the type and default it gives | SCH2403 when ignored |
| a view, type, function, or other `CREATE` form | nothing | SCH2405 |
| a data statement (`INSERT`, `UPDATE`, `DELETE`, `COPY`) | nothing | SCH2405 |
| a table option or `LIKE`, `EXCLUDE` | nothing | SCH2405 |
| a setting, grant, ownership, or sequence statement | nothing, silently | |

### Round trips

Compiling a schema under one target, importing that output, and compiling the import again under
the same target gives back that target's output byte for byte, for each of the three formats; the
corpus and the examples are tested that way. What the `.schemata` source keeps along the way
depends on the format.

XSD: names, docs, nullability, defaults, refinements, and the `@xsd` keys come back, and importing
the xsd target's own output reports nothing. Ordinals, `reserved`, `@deprecated`, and the other
targets' annotations do not, since an XML Schema holds none of them.

Protobuf: names, ordinals, docs, `@deprecated`, and `reserved` come back from the proto itself, and
every refinement, default, and type Protobuf cannot say rides on the `// schemata:` note the target
writes, so importing the proto target's own output reports nothing but the rpc names described
below. Nullability of a message-typed field rides on the note too: a field of a nullable record,
union, `instant`, or `duration` is written with `// schemata: T?`. A `.proto` written by a compiler
before 1.1, which did not write that note, imports such a field as required.

A service comes back with its operations, their payloads and streams, docs, and `@deprecated` from
the proto, and their bindings, ordinals, and `reserved` from the notes. Names come back as the
proto spells them, so an operation the target named by `@proto(name)` imports under its rpc's
name, lower-snaked; an override such as `@proto(name = "GetURL")`, which no lower_snake name gives
in UpperCamel, comes back as `@proto(name)` and is reported (SCH2402). `@openapi` keys do not come
back, since a `.proto` holds none.

SQL: tables, keys, references, child tables, unions, embedded records, enums, refinements, and
docs come back from the DDL, but ordinals do not, since a column has none; fields keep column
order, with child tables last. A record stored as json comes back empty, `record Address {}`,
reported (SCH2403), since the DDL holds a `jsonb` column and not the record's fields; that note is
the only one importing the sql target's own output reports. Restore the fields by hand, or import
the same schema from another format.

## 20. Evolving a schema

    schemata diff    [--target proto,sql,xsd,jsonschema,openapi] [--strict] [--format human|json] [--color auto|always|never] OLD NEW

`diff OLD NEW` compares two versions of a schema set — each a file or a directory, loaded the same
way `compile` loads one — and judges every change against each target's compatibility rulebook:
whether data produced under OLD stays valid, or readable, under NEW, on that target, and for the
openapi target whether a client generated from OLD's document still makes the same calls. `--target`
restricts which rulebooks judge (default all five); `--strict` promotes every note to a breaking
change. Exit codes match `compile` and `check`: `0` when no selected verdict is a note or a break
(including when there are no changes at all, printed as `no changes`), `2` when there is a note and
no break, `1` when any break. `--format json` prints one document on stdout, for CI; a human report
goes to stderr, the same as `check`. When a side does not parse or analyze cleanly, `diff` reports
`SCH2503` for that side instead of comparing anything; it does the same when the two sides share no
namespace at all, since that is two unrelated schema sets rather than two versions of one.

### Identity

- A namespace or declaration matches its counterpart by qualified name.
- A field, enum value, or union member matches by ordinal within its declaration — ordinals are the
  stable identity, the same as section 14 describes. An ordinal left implicit matches by position
  instead, so reordering a field, value, or member without numbering it reads as a rename and a
  type change; number a schema (`check --strict` reports every implicit ordinal) before relying on
  `diff`. When a compared declaration still carries an implicit ordinal on either side, the report's
  trailer says so (`note: ordinals are implicit in 2 declarations; run check --strict`).
- A field, value, or member that keeps its name but moves to a new ordinal is a removal of the old
  ordinal and an addition of the new one, on every target — even Postgres, XSD, and JSON Schema,
  which never emit ordinals and so judge the move by those two changes, not as a rename. Keep an
  ordinal once published.
- A rename is a change of name under the same ordinal. The name a target actually writes (its
  "emitted name") follows that target's own override — `@proto(name)`, `@sql(column | table |
  schema)`, `@xsd(name)`, or `@jsonschema(name)` — so a rename pinned by an override is compatible
  on that target alone, and a changed override is itself a rename on that target alone, even when
  the declared name did not move.
- A service matches by qualified name and an operation by ordinal within its service, the same way.
  On openapi, a service's emitted name is its tag and an operation's is its `operationId`, each
  after `@openapi(name)`; on proto, they are the service's and the rpc's names, each after
  `@proto(name)`.

### Verdicts

Every change is judged `compatible`, a note, or breaking, independently per target. Compatible means
data produced under OLD stays valid, or readable, under NEW on that target. A note is still
compatible but carries a caveat, printed as a warning (`SCH2502`); breaking is printed as an error
(`SCH2501`). The table below is each target's rulebook; "emitted name" is after the target's own
override, so a pinned rename is a doc-level change only.

| Change | Protobuf | Postgres | XSD | JSON Schema |
|---|---|---|---|---|
| Field added, nullable or defaulted | compatible | compatible | compatible | compatible |
| Field added, required (non-null, no default) | compatible | breaking | breaking | breaking |
| Field removed (any) | compatible on the wire; note when the ordinal or name is not reserved in NEW | breaking (data loss) | breaking (old instances carry an unknown element) | breaking on a closed record; compatible under `@jsonschema(open)` |
| Field renamed, emitted name changes | compatible; note (the JSON mapping uses names) | breaking | breaking | breaking |
| Field renamed, emitted name pinned | compatible | compatible | compatible | compatible |
| Scalar type changed | compatible for `int32`↔`int64`, `string`→`bytes`, enum↔`int32`; note for `bytes`→`string` (old values must be valid UTF-8); breaking otherwise | compatible for `int32`→`int64`, `float32`→`float64`, a wider `string(max)`, `decimal` to a wider precision at the same scale; breaking otherwise | compatible when every OLD value is valid for NEW (`int32`→`int64`, a wider length or range); breaking otherwise | as XSD, on the JSON forms (`decimal` scale change breaks the pattern) |
| Scalar ↔ record, list, map, or union; record ↔ union; key type of a map | breaking | breaking | breaking | breaking |
| Nullable → non-null, no default | compatible; note | breaking | breaking | breaking |
| Nullable → non-null, with default | compatible; note (default not carried) | breaking (existing NULL rows) | compatible (the element keeps `minOccurs = 0`) | breaking (old documents may carry `null`) |
| Non-null → nullable | compatible | compatible | compatible | compatible |
| Refinement tightened (`max` lower, `min` higher, `pattern` changed, list bounds tightened) | note (not carried) | breaking | breaking | breaking |
| Refinement loosened | compatible | compatible | compatible | compatible |
| Default added or changed | note (not carried) | compatible | note (applied to empty elements only) | compatible |
| Default removed from a non-null field | note | breaking (inserts omitting the column fail) | breaking (the element becomes `minOccurs = 1`, or the attribute `required`) | breaking (`required`) |
| Enum value added | compatible | compatible | compatible | compatible |
| Enum value removed | breaking unless reserved, then note | breaking | breaking | breaking |
| Enum value renamed, emitted name changes | compatible | breaking | breaking | breaking |
| Union member added | compatible | compatible | compatible | compatible |
| Union member removed or type changed | breaking | breaking | breaking | breaking |
| Declaration removed | note | breaking when it had a table | breaking when it had a root element, else note | breaking (every def is addressable) |
| Declaration added | compatible | compatible | compatible | compatible |
| Declaration kind changed | breaking | breaking | breaking | breaking |
| Namespace removed | as its declarations removed one by one: note | breaking when any had a table | breaking when any had a root element, else note | breaking when it had declarations |
| `@xsd(name)` / `@jsonschema(name)` changed on a declaration | compatible | compatible | breaking when the record had a root element, else note / compatible | compatible / breaking (the `$defs` key changes) |
| `@sql(key)` added, removed, or moved | compatible | breaking | compatible | compatible |
| `@sql(strategy)` changed | compatible | breaking | compatible | compatible |
| `@sql(unique)` added | compatible | breaking (existing duplicate rows) | compatible | compatible |
| `@sql(unique)` removed, `@sql(index)` changed | compatible | compatible | compatible | compatible |
| `@sql(type)` changed | compatible | breaking | compatible | compatible |
| `@proto(package)` / `@xsd(namespace)` / `@jsonschema(id)` changed | breaking / compatible / compatible | compatible | compatible / breaking / compatible | compatible / compatible / breaking |
| `@xsd(attribute)` added or removed on a field | compatible | compatible | breaking (an element becomes an attribute or back) | compatible |
| `@xsd(root = false)` added to a record that had a root element | compatible | compatible | breaking (old root documents no longer validate) | compatible |
| `@xsd(root = false)` removed | compatible | compatible | compatible | compatible |
| `@jsonschema(open)` removed from a record | compatible | compatible | compatible | breaking (extra properties now rejected) |
| `@jsonschema(open)` added | compatible | compatible | compatible | compatible |
| `reserved` added | compatible | compatible | compatible | compatible |
| `reserved` removed | note (reuse risk) | compatible | compatible | compatible |
| `@deprecated` added or removed | compatible | compatible | compatible | compatible |
| Doc changed | compatible | compatible | compatible | compatible |

`list<T>` → `list<T?>` is compatible everywhere; the reverse is a nullability tightening on the
element.

The openapi rulebook judges a change to a record, enum, or union exactly as the JSON Schema column
says when an operation of OLD reaches that declaration, since its component is the JSON Schema
lowering; a change to any other declaration is compatible on openapi, which never writes it. The
Postgres, XSD, and JSON Schema rulebooks call every change to a service compatible, since none of
them writes one. The proto rulebook judges a service by what a gRPC client generated from OLD
calls: each rpc by its method path, `/<package>.<Service>/<Rpc>`, with the request and response
messages it was built against. A binding rides in a note beside the rpc, never in that path. The
two rulebooks' own rows:

| Change | Protobuf | OpenAPI |
|---|---|---|
| Service added, operation added | compatible | compatible |
| Service removed | breaking | breaking |
| Operation removed | breaking; unless its name is `reserved` in NEW, the help suggests reserving it, so a later operation cannot take over its rpc name | breaking; unless its name is `reserved` in NEW, the help suggests reserving it, so a later operation cannot take over its default `operationId` |
| Operation renamed | breaking when its rpc name changes; compatible when `@proto(name)` keeps it | breaking when its `operationId` changes, or, without a binding, its derived URL; compatible when `@openapi(name)` keeps the `operationId` and a binding keeps the URL |
| Request or response changed, `stream` included | breaking | breaking |
| Binding added, removed, or changed | compatible | breaking when the URL moves; binding an operation to its derived URL, or unbinding one that was bound to it, is compatible |
| `@proto(name)` changed on a service or an operation | breaking when the service or rpc name it emits changes | compatible |
| `@openapi(name)` changed on a service or an operation | compatible | breaking when the tag or `operationId` it emits changes; a service's tag prefixes every `operationId` that does not set its own |
| `@openapi(version)` or `@openapi(server)` changed | compatible | compatible |
| `@deprecated` added or removed on a service or an operation | note | note |
| `reserved` changed on a service | compatible | compatible |
| Namespace removed | as in the table above | breaking when it declared a service, else compatible |

`@proto(package)` moves every method path in its namespace too, and is already breaking (above).

`reserved` changes how a removal reads on proto only. Removing a field is compatible on the wire
regardless, but reported as a note unless the removed ordinal and name are both still `reserved` in
NEW — reserve both to clear the note, since a reserved number or name can no longer be handed to
something else by accident. Removing an enum value is breaking unless both are reserved, and then a
note, since old senders can still send the value. That an element was `@deprecated` in OLD never
changes the verdict on a change to it, on any target; it shapes the report instead: a change to
something OLD had deprecated reads `deprecated field 'created' removed` (or renamed, retyped, and so
on) in the human report, and carries `deprecatedInOld` in the JSON one.

### Reporting

A human report groups changes under the declaration (or namespace) they belong to, one line per
change, followed by a verdict per target (`proto: compatible, sql: breaking, xsd: breaking,
jsonschema: breaking`). An annotation, doc, or deprecation change on a member names the member
first (`field 'id': @sql(key) removed`). Every note and break also renders as its own diagnostic,
with the rulebook's message and help and the changed side's excerpt. A trailer gives the total
change count and a `breaking`/`note` count per selected target. A JSON report holds one entry per
change (`kind`, `path`, `old`, `new`, `file`, `line`, `deprecatedInOld`, and a `verdicts` object
keyed by target), a `summary` per target, and the `exitCode`. `old` and `new` are the values as
Schemata source, so a string reads `"eu"` with its quotes. When the sides cannot be compared
(`SCH2503`), the JSON report keeps that shape — `changes` empty, every `summary` count zero,
`exitCode` 1 — and adds an `errors` array, one `{code, message, help, file, line}` per reason.

### Worked example

From `schemata-cli/src/test/resources/evolution/rename-pinned/old/s.schemata`:
```
namespace s

record Order {
  #1 id: uuid
  @sql(column = "note") @xsd(name = "note") @jsonschema(name = "note") #9 note: string(max = 500)?
}
```

From `schemata-cli/src/test/resources/evolution/rename-pinned/new/s.schemata`:
```
namespace s

record Order {
  #1 id: uuid
  @sql(column = "note") @xsd(name = "note") @jsonschema(name = "note") #9 comment: string(max = 500)?
}
```

`schemata diff old new` reports:

```text
s.Order
  field 'note' renamed to 'comment'    proto: note, sql: compatible, xsd: compatible, jsonschema: compatible

1 change
proto: 0 breaking, 1 note
sql: 0 breaking, 0 notes
xsd: 0 breaking, 0 notes
jsonschema: 0 breaking, 0 notes

warning[SCH2502] (lossy): proto: s.Order.comment: field renamed from 'note' to 'comment'; this changes the JSON mapping
 --> new/s.schemata:5:75
  |
5 |   @sql(column = "note") @xsd(name = "note") @jsonschema(name = "note") #9 comment: string(max = 500)?
  |                                                                           ^^^^^^^
  = help: pin the emitted name with @proto(name = "note")

0 errors, 1 warning
```

The declared name changed, but `@sql(column)`, `@xsd(name)`, and `@jsonschema(name)` already pin
what those three targets emit, so only proto — the one target left unpinned — reads the rename as a
change, and only as a note: proto keys the wire format by ordinal, so old and new messages still
decode into each other, but the JSON mapping Protobuf derives from the field name moves. Pinning
proto too (`@proto(name = "note")`, as the warning's help says) would make every target compatible.
The pins need not predate the rename: added in the same change, each target still compares the
name the old field emitted with the name the new one emits, and both are the name `note`, so
every target reads the rename as compatible.

A service is compared the same way. The two sides of this example differ only in their services,
and NEW adds a second service, `Audit`.

From `schemata-cli/src/test/resources/evolution/services/old/orders.schemata`:
```
service Orders {
  #1 get(OrderId): Order  get "/orders/{id}"
  #2 list(Filter): stream Order  get "/orders"
  #3 place(PlaceOrder): Order  post "/orders"
  #4 cancel(OrderId)  delete "/orders/{id}"
  #5 old(OrderId): Order
}
```

From `schemata-cli/src/test/resources/evolution/services/new/orders.schemata`:
```
service Orders {
  #1 fetch(OrderId): Order  get "/orders/{id}"
  #2 list(Filter): Order  get "/orders"
  #3 place(PlaceOrder): Order  post "/orders/new"
  @deprecated #5 old(OrderId): Order
  #6 count(): Count
  reserved #4, "cancel"
}
```

`schemata diff old new` reports, shortened here to the first two of its nine diagnostics:

```text
s.Orders
  operation 'get' renamed to 'fetch'    proto: breaking, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: breaking
  operation 'list' response changed from stream Order to Order    proto: breaking, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: breaking
  operation 'place' binding changed from post /orders to post /orders/new    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: breaking
  operation 'old': marked deprecated    proto: note, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: note
  operation 'count' added    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible
  operation 'cancel' removed    proto: breaking, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: breaking
  reserved changed    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible
s.Audit
  service added    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible

8 changes
proto: 3 breaking, 1 note
sql: 0 breaking, 0 notes
xsd: 0 breaking, 0 notes
jsonschema: 0 breaking, 0 notes
openapi: 4 breaking, 1 note

error[SCH2501]: openapi: s.Orders.fetch: the operation was renamed, so its operationId changes from Orders_get to Orders_fetch
  --> new/orders.schemata:17:6
   |
17 |   #1 fetch(OrderId): Order  get "/orders/{id}"
   |      ^^^^^
   = help: pin the operationId with @openapi(name = "Orders_get")

error[SCH2501]: proto: s.Orders.fetch: the operation was renamed, so its rpc path changes from /s.Orders/Get to /s.Orders/Fetch
  --> new/orders.schemata:17:6
   |
17 |   #1 fetch(OrderId): Order  get "/orders/{id}"
   |      ^^^^^
   = help: pin the rpc name with @proto(name = "Get")

7 errors, 2 warnings
```

Both rulebooks that write a service see the changes, and judge them by what each writes. `get`'s
rename moves its `operationId`, which generated clients call by name, and its rpc's method path,
from `/s.Orders/Get` to `/s.Orders/Fetch`; `@openapi(name = "Orders_get")` and
`@proto(name = "Get")`, as the two helps say, would keep both. Dropping `list`'s `stream` breaks
both too, and `old`'s deprecation is a note on both. `place`'s new path breaks openapi only, since
the proto keeps a binding in a note and its method path stays. Reserving `cancel` quiets the help
on its removal but not the break: a client that calls it still fails.

With both pins, added in the same change as the rename, the rename is compatible everywhere. Here
OLD's `Orders` holds only `#1 get(OrderId): Order  get "/orders/{id}"`.

From `schemata-cli/src/test/resources/evolution/services-pinned/new/orders.schemata`:
```
service Orders {
  @proto(name = "Get") @openapi(name = "Orders_get") #1 fetch(OrderId): Order  get "/orders/{id}"
}
```

`schemata diff old new` reports:

```text
s.Orders
  operation 'get' renamed to 'fetch'    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible
  operation 'fetch': @proto(name) added    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible
  operation 'fetch': @openapi(name) added    proto: compatible, sql: compatible, xsd: compatible, jsonschema: compatible, openapi: compatible

3 changes
proto: 0 breaking, 0 notes
sql: 0 breaking, 0 notes
xsd: 0 breaking, 0 notes
jsonschema: 0 breaking, 0 notes
openapi: 0 breaking, 0 notes

no diagnostics
```

## 21. Editor support

`schemata lsp` is a language server: an editor starts it and talks to it over its standard input
and output. It reports the diagnostics `schemata check` reports about the schemas themselves, with
the same codes and help lines, as you type. It runs no target, so the target checks (the `SCH2...`
codes) stay with the command line; run `check` or `compile` for those.

In VS Code, install the Schemata extension from its releases page (a `.vsix` file; "Extensions:
Install from VSIX..."). It needs `schemata` 0.8.0 or later on your `PATH`, or the path to it in the
`schemata.path` setting.

In Zed, clone [zed-schemata](https://github.com/msbolton/zed-schemata) and run "zed: install dev
extension" on the clone; building it needs Rust installed through rustup. It needs `schemata`
0.8.0 or later too, and runs `schemata lsp` from your `PATH` or from the path you give it under
`binary.path`. The two options `roots` and `strict`, which the VS Code extension takes too, go under
`initialization_options` in Zed's settings:

```json
{
  "lsp": {
    "schemata": {
      "binary": { "path": "/path/to/schemata" },
      "initialization_options": { "roots": ["model"], "strict": true }
    }
  }
}
```

Zed highlights with a tree-sitter grammar, which lives in
[tree-sitter-schemata](https://github.com/msbolton/tree-sitter-schemata) and is checked against
this repository's own schema files.

Any other editor with a language-server client can run `schemata lsp` itself.

What the server does:

| You do | You get |
|---|---|
| Open or edit a file | Diagnostics for every file of its schema set, open or not |
| Go to definition | A type name goes to its declaration, across files; the alias in `cust.Customer` goes to the import; an import goes to the `namespace` line of each file that declares it; an enum default goes to the value |
| Hover | The declaration's kind and qualified name, the rest of a field or alias as written, and the doc comment; on an operation, its line as `fmt` writes it, less annotations, and its doc comment; on a builtin type name such as `int32` or `list`, a one-line description |
| Find references | Every use of a declaration, field, enum value, namespace, or import alias in its set |
| Rename | The declaration and every use, in one edit; a service or an operation too |
| Format document | The same result as `schemata fmt` |
| Outline | The namespace, its declarations, and their fields and values; each service, with its operations |

**Schema sets.** Imports name a namespace, not a file, so the server has to know which files belong
together. By default a file's set is every `.schemata` file in its own directory. When one schema
is spread over nested directories, list its top directory in `schemata.roots` (paths relative to
the first workspace folder); everything beneath a root is then one set, and a file under several
roots belongs to the nearest. Two sets never see each other's declarations, so a repository can
hold several schemas, or two versions of one, side by side.

Make a root the directory that holds the schema, not the repository: every `.schemata` file
beneath a root joins its set, copies in build output included, and a second copy of a namespace
turns every declaration in it into a duplicate. Hidden directories such as `.git` are skipped.

**While a file does not parse.** You spend most of your typing time with a file that is not yet
valid. The file shows its syntax errors and nothing else. Once a file has parsed, the rest of its
set keeps using the last version of it that did, so files that import it do not light up with
errors that are not theirs; a file that has never parsed contributes nothing until it does. Go to
definition, hover, references, the outline, and formatting pause for the broken file itself and
resume when it parses again. A definition or reference that another file finds inside the broken
one comes from that last parsed version, so its location can be off until the file parses again.
Rename waits until every file of the set parses.

**Rename.** Rename changes names in the schema and nothing else. It does not add `@proto(name)`,
`@sql(column)`, `@openapi(name)`, or any other override, so the emitted names change with it;
`schemata diff` tells you what that breaks on each target (section 20). A reserved name string and
an existing override are text, not uses of the name, and stay as they are. A namespace cannot be
renamed. Rename refuses a name that is not an identifier, is a keyword, or is already taken where
the old name lives, and a declaration may not take a builtin type name, `list`, or `map`. A service's
new name must be UpperCamel and an operation's lower_snake, and a service and a declaration of one
namespace may not take each other's name. Rename also tries the rename before it answers, and
refuses one that would:

- change what another name refers to, as when a nested record renamed to `Item` would capture the
  uses of a top-level `Item`;
- add an error, as when an import without an alias makes the new name ambiguous;
- leave a use behind, because a type that mentions the old name has an error of its own (as in
  `map<Strng, Customer>`) and so was never looked up; fix that type first;
- edit a file that is not open and has changed on disk since the server read it; try again.

**Settings.** `schemata.path` is the `schemata` binary to run (default: the one on `PATH`).
`schemata.roots` lists schema-set roots. `schemata.strict` reports every field, enum value, union
member, or operation left with an implicit ordinal as an error, as `--strict` does; unlike
`--strict`, it does not turn other warnings into errors.

In Zed the same options are `binary.path` and the `roots` and `strict` entries of
`initialization_options`, as shown above.
