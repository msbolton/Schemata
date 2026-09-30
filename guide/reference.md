# Schemata language reference

A `.schemata` file declares records, enums, unions, and aliases in a namespace. You give the
compiler a set of `.schemata` files, and it compiles that one set to a Protobuf schema, a Postgres
schema, an XML Schema, and a JSON Schema.

## 1. Files and namespaces

Every file begins with a namespace declaration: `namespace a.b.c`. Each segment is lower_snake.
Several files may share a namespace; the compilation unit is the whole set of files given on the
command line, with any directories walked recursively. Output paths follow the namespace, so
`namespace shop.orders` writes `shop/orders.proto`, `shop/orders.sql`, `shop/orders.xsd`, and
`shop/orders.schema.json`. A doc comment and any annotations may precede the `namespace` line
itself; section 15 shows
annotations there, and `examples/shop/orders.schemata` shows a doc comment.

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
fully qualified name always works. An unused import is a warning (SCH1012).

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
the parser keeps it, but none of the outputs carries it.

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

Namespace segments and field names are lower_snake. Type names (record, enum, union, alias) are
UpperCamel. Enum values are lower_snake. The help suggests a corrected name when it can derive one
from what you wrote. No name in a `.schemata` file, whether a declaration, a field, or an enum
value, may be one of the language's reserved words: `namespace`, `import`, `as`, `record`, `enum`,
`union`, `alias`, `reserved`, `true`, `false`, `service`, `operation`, `stream`. `service`,
`operation`, and `stream` are held for a future version of the language. An annotation key is
exempt, so `@xsd(namespace = "…")` is legal.

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
so a multi-line value can fail an XSD pattern that Java accepts.

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
field, since a message field's presence in proto3 is already implicit. Section 16 shows all four.
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
this guide's examples all declare a key.

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
ordinal like a field does. A member must be a named type, meaning a record or an enum, or a scalar;
it may not be nullable and may not be a collection. Nullability belongs on the field that holds the
union, not on a member. Protobuf lowers a union to a message holding a `oneof`, and the field holds
that message; a member named `Kind` would collide with the `oneof` itself, always named `kind`
(SCH2004). Postgres lowers a union to a discriminator column plus each member's columns, with a
CHECK tying the discriminator to the columns that member requires. A member named `Kind` collides
on the Postgres side too: its own CHECK constraint takes the same name as the discriminator's
(SCH2111).

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

`reserved #11, "legacy_ref"` and `reserved #5..#9`, written inside a record or an enum, mark
ordinals and names that may never be used again. A range is written low`..`high, inclusive on both
ends. Reusing a reserved ordinal or name is an error.

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

`#n` numbers a field, an enum value, or a union member. Within one declaration, every element
carries an explicit ordinal or none do; `--strict` rejects a declaration that leaves them implicit.
Implicit ordinals are assigned in declaration order, starting at `#1`. Ordinals become Protobuf
field numbers.

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
enum, a union, an alias, a field, or an enum value, under no target. A namespace's annotations are
written before its `namespace` line; a record's are written before its `record` line.

`@proto(package = "…")` renames a namespace's Protobuf package. `@proto(name = "…")` renames a
single declaration, field, or enum value.

`@sql(schema = "…")` renames a namespace's Postgres schema. `@sql(table = "…")` and
`@sql(column = "…")` rename a record's table or a field's column. `@sql(key)` on a field, or
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
to appear only nested inside another.

`@jsonschema(id = "…")` sets a document's `$id`; without it, the namespace lowers to
`urn:schemata:<namespace>`. `@jsonschema(name = "…")` renames a record, an enum, a union, a field,
or an enum value (the `$defs` key, the property name, or the enum value string). `@jsonschema(open)`
on a record drops `additionalProperties: false`, so instances may carry properties the record does
not declare.

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

## 16. How constructs lower

| Construct | Protobuf | Postgres | XSD | JSON Schema |
|---|---|---|---|---|
| a record (default strategy) | a nested or referenced message field | embedded: the record's fields become columns on the containing table, prefixed by the field name | an `xs:complexType` plus a global element | an `object` in `$defs` with `additionalProperties: false` unless `@jsonschema(open)`; a field of the type is a `$ref` |
| a keyed record (`@sql(key)`) | an ordinary message; the key adds nothing to it | its own table, keyed by the declared column or columns; a field elsewhere of this type becomes a foreign key to it | the same; the key is not represented | the same; the key is not represented |
| a record declared inside another (`Order.Line`) | a nested message under the enclosing message | nesting only scopes the name; the field that uses the type still follows the record or collection rules in this table | its own named type, `OuterInnerType` | its own `$defs` entry, keyed `Outer.Inner` |
| an enum | `enum`, always with a synthesized zero value (SCH2001) | `text`, with a CHECK restricting it to the declared values | an `xs:simpleType` restricting `xs:string` to an enumeration | `type: string` with `enum`; when a value has a doc, `oneOf` of `const` entries so the docs survive |
| a union | a message holding a `oneof`; the field holds that message | a discriminator column plus each member's columns, with a CHECK tying the discriminator to the columns a given member requires | a complexType holding an `xs:choice`, one element per member | an object with `oneOf`, one single-property closed object per member, tagged by the member type's name in lower snake (`bank_transfer`, `int64`) |
| `list<scalar>` | `repeated <scalar>` | an array column by default; `@sql(strategy = table)` gives it a child table, `json` a `jsonb` column | a repeated element | an `array` with `items`, `minItems`, `maxItems` |
| `list<Record>` | `repeated <Message>` | a child table by default, named `<parent>_<field>`, keyed by the parent's key columns plus `position`; `@sql(strategy = json)` gives it a `jsonb` column | a repeated element of the record's type | an `array` of `$ref` items |
| `map<K, V>` | the native `map<K, V>` type | `jsonb` by default; `@sql(strategy = table)` gives it a child table with `key` and `value` columns | a wrapper element holding `entry` elements keyed by a `key` attribute; a refined scalar value sits in a `value` child element, since an extension cannot carry facets | an `object` with `additionalProperties`; an integer key adds a digit pattern on `propertyNames`, a refined string key its constraints |
| a nullable field (`T?`) | proto3 `optional` for a scalar or enum; a plain `repeated` or `map` for a nullable list or map (SCH2001); a plain message field for a nullable record or union, whose presence is already implicit | the column allows `NULL` | `minOccurs="0"` on an element, or `use="optional"` on an attribute | not `required`; a scalar's `type` becomes `[T, "null"]`, anything else `anyOf` with `{"type": "null"}` |
| a default (`= literal`) | dropped, and kept only as a trailing comment (SCH2001) | a `DEFAULT` clause on the column | `default=` on the element or attribute; on an element, XSD applies it only when the element is present and empty | `default`, an annotation the reader applies; the field is not `required` |
| a refinement (`min`, `max`, `pattern`) | dropped, and kept only as a trailing comment (SCH2001) | a narrower column type, such as `varchar(100)` or `numeric(19, 4)`, or a CHECK constraint | facets on the restriction, such as `xs:maxLength` or `xs:pattern`; a pattern is anchored by wrapping an unanchored side in `.*`, and XSD's `.` excludes newlines | `minimum`/`maximum`, `minLength`/`maxLength`, `pattern` unchanged (both dialects match anywhere); a construct ECMA-262 lacks drops the pattern (SCH2301); `min`/`max` on a decimal are dropped, since a decimal is a string with a precision-and-scale pattern (SCH2301); `bytes` bounds become base64 lengths (SCH2301 for `max`) |
| an alias | transparent: it lowers exactly as its underlying type would | transparent, for the same reason | inlined: the alias itself is not represented | inlined |
| a doc comment (`///`) | a `//` comment above the declaration | `COMMENT ON TABLE` or `COMMENT ON COLUMN` | an `xs:documentation` element inside `xs:annotation` | `description` |
| `reserved` | `reserved <n>;` and `reserved "name";` inside the message | nothing | not represented | not represented |

### 16.1 Validating JSON instances

A document has no top-level `type`; validate an instance against one definition by its pointer,
`<$id>#/$defs/<Name>`, for example `urn:schemata:shop.orders#/$defs/Order`. Register every
generated document with your validator by its `$id` (most validators take a map from `$id` to
document), because references across namespaces are absolute. `format` keywords (`uuid`, `date`,
`date-time`, `duration`) are assertions only when the validator enables format assertions; `uuid`
also carries a pattern so it is enforced regardless. `int64` is an `integer` with its full range,
`decimal(p, s)` is a string such as `"24.4800"`, and `bytes` is a base64 string.

## 17. The CLI

`schemata` has four commands.

`compile <paths>...` compiles to `--out` (default `out`), for `--target` (a comma-separated list;
default `proto`, `sql`, `xsd`, and `jsonschema`), reporting diagnostics in `--format` (`human`, to
stderr, or `json`, to stdout), colored per `--color` (`auto`, `always`, or `never`), and treating
every warning as an error under `--strict`, which also reports any field, enum value, or union
member left with an implicit ordinal. A path may be a file or a directory, walked recursively for
`.schemata` files.

`check <paths>...` takes the same options except `--out`; it reports every diagnostic `compile`
would, for every target, without writing anything.

`targets` lists the targets, the annotation keys each accepts, and the diagnostic codes each can
report, under `--format` (`human` or `json`).

`schemata fmt PATHS...` rewrites schema files in the canonical layout and names each file it
changed; `schemata fmt --check PATHS...` writes nothing, prints a diff for each file that would
change, and exits 1 if any would, which is how CI keeps a repository formatted. Comments are kept:
one on its own line stays above the element that follows it, and a comment at the end of a line
stays on that line (after the element, or after the opening brace). Long lines are never wrapped.
Doc-comment text keeps its indentation beyond one space after `///`. A file that does not parse is
reported like `check` would and left untouched.

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
  |      ^^^
  = help: keep the synthesized zero value; proto3 reads an unset enum as 0

warning[SCH2105] (lossy) (sql): field 'Contact.tags': refinements on list<string(max = 20)> are not enforced by Postgres
  --> contacts.schemata:15:3
   |
15 |   #7 tags:  list<string(max = 20)>
   |   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
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

`schemata --version` prints the compiler's version and exits.
