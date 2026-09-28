# Schemata language reference

A `.schemata` file declares records, enums, unions, and aliases in a namespace. You give the
compiler a set of `.schemata` files, and it compiles that one set to both a Protobuf schema and a
Postgres schema.

## 1. Files and namespaces

Every file begins with a namespace declaration: `namespace a.b.c`. Each segment is lower_snake.
Several files may share a namespace; the compilation unit is the whole set of files given on the
command line, with any directories walked recursively. Output paths follow the namespace, so
`namespace shop.orders` writes `shop/orders.proto` and `shop/orders.sql`.

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

```schemata error
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

`//` starts a line comment; the parser ignores everything to the end of the line. `///` starts a
doc comment; it attaches to the declaration or field that follows and is carried into the
generated Protobuf and SQL as a comment.

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
UpperCamel. Enum values are lower_snake. Every naming diagnostic's help suggests the corrected
name. No name in a `.schemata` file, whether a declaration, a field, or an enum value, may be one
of the language's reserved words: `namespace`, `import`, `as`, `record`, `enum`, `union`, `alias`,
`reserved`, `true`, `false`, `service`, `operation`, `stream`. `service`, `operation`, and `stream`
are held for a future version of the language.

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

```schemata error
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
to its plain type and reports SCH2001.

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

`T?` means the field's value may be absent. A nullable field lowers to a nullable column in
Postgres and, in Protobuf, to a wrapper or optional field, as the lowering section later in this
reference shows. A map key may not be nullable. A nullable alias may not be marked `?` again where
it is used; section 8 shows an alias declared nullable and a field that uses it bare.

```schemata
namespace shop.orders

record OrderLine {
  @sql(key) #1 id: int64
  #2 note: string?
  #3 tags: map<string, int32>
}
```

```schemata error
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

```schemata error
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

```schemata error
namespace shop.orders

alias Money = decimal(19, 4)

record OrderLine {
  @sql(key) #1 id: int64
  #2 price: Money(max = 5)
}
```

Sections on declarations, ordinals, annotations, lowering, and the CLI follow.
