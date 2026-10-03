# Schemata language reference

A `.schemata` file declares records, enums, unions, and aliases in a namespace. You give the
compiler a set of `.schemata` files, and it compiles that one set to a Protobuf schema, a Postgres
schema, an XML Schema, and a JSON Schema.
What a 1.x release promises about this language, its output, and its diagnostics is on the
[What is stable](stability.md) page.

## 1. Files and namespaces

Every file begins with a namespace declaration: `namespace a.b.c`. Each segment is lower_snake.
Several files may share a namespace; the compilation unit is the whole set of files given on the
command line, with any directories walked recursively. Output paths follow the namespace, so
`namespace shop.orders` writes `shop/orders.proto`, `shop/orders.sql`, `shop/orders.xsd`, and
`shop/orders.schema.json`. A doc comment and any annotations may precede the `namespace` line
itself; section 15 shows
annotations there, and `examples/shop/orders.schemata` shows a doc comment. A UTF-8 byte-order mark
at the start of a file is skipped; `fmt` never writes one.

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

Namespace segments and field names are lower_snake: a lowercase letter, then lowercase letters and
digits, with single underscores between runs and none at the start or the end (`order_line`, `line2`;
not `order__line`, `line_`, or `_line`). Type names (record, enum, union, alias) are
UpperCamel. Enum values are lower_snake. The help suggests a corrected name when it can derive one
from what you wrote. No name in a `.schemata` file, whether a declaration, a field, or an enum
value, may be one of the language's reserved words: `namespace`, `import`, `as`, `record`, `enum`,
`union`, `alias`, `reserved`, `true`, `false`, `service`, `operation`, `stream`. `service`,
`operation`, and `stream` are held for a future version of the language. An annotation key is
exempt, so `@xsd(namespace = "…")` is legal. `null` is not a keyword, since `= null` has to be
read, but a field, an enum value, or a namespace segment may not be called `null` either:
`= null` always means the literal, never an enum value of that name. Where a suggested name would
be a reserved word, the help adds `_value` (`null_value`, `true_value`).

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
to appear only nested inside another.

`@jsonschema(id = "…")` sets a document's `$id`; without it, the namespace lowers to
`urn:schemata:<namespace>`. `@jsonschema(name = "…")` renames a record, an enum, a union, a field,
or an enum value (the `$defs` key, the property name, or the enum value string). A name must be
non-empty and must not contain whitespace or any of `/ ~ # % ? " \`, which a `$ref` cannot carry.
Two declarations, fields, union members, or enum values that lower to one JSON name are an error
(SCH2302). `@jsonschema(open)` on a record drops `additionalProperties: false`, so instances may
carry properties the record does not declare.

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
| a union | a message holding a `oneof`; the field holds that message; each member is a field named from the member type's simple name in lower_snake or its `@proto(name)` | a discriminator column plus each member's columns, with a CHECK tying the discriminator to the columns a given member requires | a complexType holding an `xs:choice`, one element per member | an object with `oneOf`, one single-property closed object per member, tagged by the member type's name in lower snake (`bank_transfer`, `int64`), or by its `@jsonschema(name)` as written |
| `list<scalar>` | `repeated <scalar>` | an array column by default; `@sql(strategy = table)` gives it a child table, `json` a `jsonb` column | a repeated element | an `array` with `items`, `minItems`, `maxItems` |
| `list<Record>` | `repeated <Message>` | a child table by default, named `<parent>_<field>`, keyed by the parent's key columns plus `position`; `@sql(strategy = json)` gives it a `jsonb` column | a repeated element of the record's type | an `array` of `$ref` items |
| `map<K, V>` | the native `map<K, V>` type | `jsonb` by default; `@sql(strategy = table)` gives it a child table with `key` and `value` columns | a wrapper element holding `entry` elements keyed by a `key` attribute; a refined scalar value sits in a `value` child element, since an extension cannot carry facets | an `object` with `additionalProperties`; an integer key adds a digit pattern on `propertyNames`, a refined string key its constraints |
| a nullable field (`T?`) | proto3 `optional` for a scalar or enum; a plain `repeated` or `map` for a nullable list or map (SCH2001); a plain message field for a nullable record or union, whose presence is already implicit | the column allows `NULL` | `minOccurs="0"` on an element, or `use="optional"` on an attribute | not `required`; a scalar's `type` becomes `[T, "null"]`, anything else `anyOf` with `{"type": "null"}` |
| a default (`= literal`) | dropped, and kept only as a trailing comment (SCH2001) | a `DEFAULT` clause on the column | `default=` on the element or attribute; on an element, XSD applies it only when the element is present and empty | `default`, an annotation the reader applies; the field is not `required` |
| a refinement (`min`, `max`, `pattern`) | dropped, and kept only as a trailing comment (SCH2001) | a narrower column type, such as `varchar(100)` or `numeric(19, 4)`, or a CHECK constraint; a `pattern` with a construct Postgres regexes lack (`\p{…}`, `\b` as a word boundary, named groups, possessive quantifiers) drops the CHECK with a warning (SCH2105); lookahead and lookbehind are fine | facets on the restriction, such as `xs:maxLength` or `xs:pattern`; a pattern is anchored by wrapping an unanchored side in `.*`, and XSD's `.` excludes newlines | `minimum`/`maximum`, `minLength`/`maxLength`, `pattern` unchanged (both dialects match anywhere); a construct ECMA-262 lacks drops the pattern (SCH2301), including an identity escape such as `\-` outside a class, which the Unicode dialect JSON Schema assumes rejects; `min`/`max` on a decimal are dropped, since a decimal is a string with a precision-and-scale pattern (SCH2301); `bytes` bounds become base64 lengths (SCH2301 for `max`) |
| an alias | transparent: it lowers exactly as its underlying type would | transparent, for the same reason | inlined: the alias itself is not represented | inlined |
| a doc comment (`///`) | a `//` comment above the declaration | `COMMENT ON TABLE` or `COMMENT ON COLUMN` | an `xs:documentation` element inside `xs:annotation` | `description` |
| `@deprecated` | `option deprecated = true` or `[deprecated = true]` | not represented | not represented | `deprecated: true` on the def or property; not on enum values |
| `reserved` | `reserved <n>;` and `reserved "name";` inside the message | nothing | not represented | not represented |

### 16.1 Validating JSON instances

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

## 17. The CLI

`schemata` has seven commands: `compile`, `check`, `import`, `targets`, `fmt`, `diff`, and `lsp`.
This section covers `compile`, `check`, `targets`, `fmt`, and `lsp`; `import` is section 18 and
`diff` is section 19.

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

`lsp` runs the language server for an editor; section 20 describes it. It takes no options and
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

## 18. Importing an XSD

    schemata import --from xsd [--out DIR] [--namespace NAME] [--strict] [--format human|json] [--color auto|always|never] PATHS...

`import --from xsd` reads existing XML Schema and lowers it to `.schemata` source: one output file
per namespace, written to `--out/import/<namespace as a path>.schemata`, so a schema whose namespace
lowers to `shop.orders` writes `out/import/shop/orders.schemata`. A path may be a file or a
directory, walked recursively for `.xsd` files, the same as `compile`. An `xs:import` or
`xs:include` target is resolved by its own `schemaLocation` against the importing file's own
directory; an input itself never needs naming twice just because another input imports it, and an
input that another input includes is merged into that one rather than imported on its own.
`--namespace NAME` renames the single output file's namespace and only applies when exactly one
input file is given; more than one is a usage error, as is a name that is not dotted lower_snake
segments. Exit codes match `compile`: `0` when nothing is reported, `2` when every diagnostic is a
warning, `1` when any is an error, or the command line itself was wrong. `--strict` promotes every
warning below to an error, the same as it does for `compile`.

A schema's `targetNamespace` becomes the output's `namespace`. `urn:schemata:<name>` becomes
`namespace <name>`, matching what the xsd target itself writes for a Schemata namespace. Any other
URI becomes `namespace <file stem>`, with `@xsd(namespace = "<uri>")` on the namespace to keep the
real one and a note, SCH2402, naming the namespace it derived; pass `--namespace` to choose the name
yourself and silence the note. A schema with no `targetNamespace` uses the file stem alone, without
the annotation; the note still appears, and `--namespace` silences it.

### Types and facets

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
use. A `list` or `union` simple type has no Schemata equivalent and imports as plain `string`
(SCH2405).

### Records, unions, and enums

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
named type already owns, such as `gpx` beside `gpxType`, is an error (SCH2401) and dropped. The xsd
target writes one global element per record of its own namespace, so a second global element of the
same type, and one of a simple type or of a type in another namespace, are dropped (SCH2405).

A `sequence`'s children become fields in order; `xs:all` and a nested `sequence` are flattened into
the same list of fields (SCH2403). A complex type whose whole content model is a `choice` becomes a
`union` instead of a record, one member per branch's type; the branch elements' own names are not
kept, which is reported (SCH2403) when one differs from what its type's name would lower to. A union
has nowhere to put attributes, mixed content, or `abstract`, so a choice-only type's are dropped
(SCH2405). An element whose type is such a choice with `maxOccurs` greater than one becomes
`list<Union>`, with `min`/`max` from the choice's own occurrences (SCH2403). An inline `choice`
nested inside a `sequence` becomes, when every branch is a complex type, a synthesized union named
`<Record>Choice` held in a field called `choice` (SCH2403); otherwise each branch becomes its own
optional field (SCH2403). An element with `maxOccurs` greater than one becomes `list<T>`, with
`min`/`max` from `minOccurs`/`maxOccurs`; `nillable="true"` adds `?` to the element type, giving
`list<T?>`; a `default` on a repeated element is dropped (SCH2403), since a list has no default. An
element with `maxOccurs="0"` can never appear and is dropped (SCH2405). A single element with
`minOccurs="0"` becomes `T?`, unless it carries a `default`, which already implies optional
presence. An element with both a `type` and an inline type keeps the `type` (SCH2403).

An attribute becomes an `@xsd(attribute)` field, placed after the element fields; a required
attribute (`use="required"`) is non-nullable, any other is `?`. A `fixed` value is imported as a
Schemata default (SCH2405), since Schemata has no equivalent of a value XML forces on every
instance. A default carries over only when Schemata can write it as a literal: a number as a plain
decimal (`.5` becomes `0.5`, `1e5` becomes `100000`), a boolean's `1` and `0` as `true` and `false`,
a string quoted, and an enum value by its imported name. One with no Schemata literal, such as
`INF`, a `dateTime`, a value of a complex type, or a value its enum does not have, is dropped
(SCH2403). The xsd target's own rendering of `map<K, V>` is recognized on the way back in and
becomes `map<K, V>` again, not a record. An anonymous complex type becomes a record nested under the
element that uses it, named after that element. A complex type never used as a global element
becomes `@xsd(root = false)`, for one meant to appear only nested inside another.

An `extension` flattens the base type's fields in first, ahead of its own (SCH2403), since Schemata
has no base-record relationship to preserve. A `restriction` of a complex type keeps only its own
content (SCH2403). A `simpleContent` extension or restriction becomes a record with a `value` field
of the base's simple type, beside the type's attributes (SCH2403); when the base is itself a complex
type, `value` imports as `string` (SCH2403). A named `group` or `attributeGroup` expands in place
wherever it is referenced. `xs:documentation` becomes a `///` doc comment, each line without the
indentation the schema gave it; `xs:appinfo` is dropped silently.

An element, attribute, or enum value name that is not a valid Schemata identifier lowers to
lower_snake with `@xsd(name = "…")` restoring the original, silently; a value that cannot be an XML
name at all, such as `2d`, is prefixed (`v2d`) and reported (SCH2403). A name that is a Schemata
keyword, such as `true`, `stream`, or `import`, or the reserved name `null`, takes a `_value` suffix
the same way: an enumeration value `true` becomes `true_value` with `@xsd(name = "true")`, and a
default naming it follows. A record named after an element whose name starts with a digit is
prefixed with `V`, so `3d` gives `record V3d`.

### What is dropped

`xs:any`, `xs:anyAttribute`, mixed content, a substitution group, an identity constraint other than
the map form above, `redefine`, `override`, `notation`, and `abstract` all have no Schemata
equivalent and are dropped, reported SCH2405.

An unresolved import, include, or type reference is an error (SCH2401), as are two inputs declaring
the same namespace without one including the other, two elements lowering to the same field, and two
union members of the same type. So is a simple type, group, or attribute group whose references lead
back to itself, a construct missing an attribute it cannot be read without (a `group` with no
`name`, an `extension` with no `base`), and a document with a `DOCTYPE`, which the importer refuses
to read.

### Codes

| Code | Meaning |
|---|---|
| SCH2401 | error: an xsd reference, import, or include cannot be resolved, or two constructs lower to one name |
| SCH2402 | warning: a namespace name was derived from the file name |
| SCH2403 | warning: an xsd construct was approximated |
| SCH2404 | warning: an xsd type or facet was widened or dropped |
| SCH2405 | warning: an xsd construct was dropped |

The diagnostics appendix lists the exact message and help text for each.

An imported record never carries `@sql(key)`, so compiling the result under the sql target reports
SCH2106 for every record until you add one by hand; the proto, xsd, and jsonschema targets compile
the import straight away. Importing the xsd target's own output regenerates it byte for byte, with
no diagnostics at all, which is how the round trip is tested.

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

## 19. Evolving a schema

    schemata diff    [--target proto,sql,xsd,jsonschema] [--strict] [--format human|json] [--color auto|always|never] OLD NEW

`diff OLD NEW` compares two versions of a schema set — each a file or a directory, loaded the same
way `compile` loads one — and judges every change against each target's compatibility rulebook:
whether data produced under OLD stays valid, or readable, under NEW, on that target. `--target`
restricts which rulebooks judge (default all four); `--strict` promotes every note to a breaking
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

`reserved` changes how a removal reads on proto only. Removing a field is compatible on the wire
regardless, but reported as a note unless the removed ordinal and name are both still `reserved` in
NEW — reserve both to clear the note, since a reserved number or name can no longer be handed to
something else by accident. Removing an enum value is breaking unless both are reserved, and then a
note, since old senders can still send the value. `@deprecated` never changes a verdict on any
target; it shapes the report instead: a change to something OLD had deprecated reads `deprecated
field 'created' removed` (or renamed, retyped, and so on) in the human report, and carries
`deprecatedInOld` in the JSON one.

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

## 20. Editor support

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
| Hover | The declaration's kind and qualified name, the rest of a field or alias as written, and the doc comment; on a builtin type name such as `int32` or `list`, a one-line description |
| Find references | Every use of a declaration, field, enum value, namespace, or import alias in its set |
| Rename | The declaration and every use, in one edit |
| Format document | The same result as `schemata fmt` |
| Outline | The namespace, its declarations, and their fields and values |

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
`@sql(column)`, or any other override, so the emitted names change with it; `schemata diff` tells
you what that breaks on each target (section 19). A reserved name string and an existing override
are text, not uses of the name, and stay as they are. A namespace cannot be renamed. Rename
refuses a name that is not an identifier, is a keyword, or is already taken where the old name
lives, and a declaration may not take a builtin type name, `list`, or `map`. It also tries the
rename before it answers, and refuses one that would:

- change what another name refers to, as when a nested record renamed to `Item` would capture the
  uses of a top-level `Item`;
- add an error, as when an import without an alias makes the new name ambiguous;
- leave a use behind, because a type that mentions the old name has an error of its own (as in
  `map<Strng, Customer>`) and so was never looked up; fix that type first;
- edit a file that is not open and has changed on disk since the server read it; try again.

**Settings.** `schemata.path` is the `schemata` binary to run (default: the one on `PATH`).
`schemata.roots` lists schema-set roots. `schemata.strict` reports every field, enum value, or
union member left with an implicit ordinal as an error, as `--strict` does; unlike `--strict`, it
does not turn other warnings into errors.

In Zed the same options are `binary.path` and the `roots` and `strict` entries of
`initialization_options`, as shown above.
