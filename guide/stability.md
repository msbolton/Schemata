# What is stable

Schemata 2.0 is the language's second settled form. It changed the syntax every schema is written
in and made references mean the same thing on every target; nothing else about what a schema means
moved. This page says what a 2.x compiler promises for a schema that compiled under 2.0, how
something is retired when it has to be, and which parts of the project the promise does not cover.

## The language, settled

Seven questions shaped the language. They are settled as follows, and a 2.x release does not
reopen them.

**Absence is one concept.** `T?` is the only way to say a value may be absent; there is no
`required`. Each target maps it to its own notion of presence (section 17 of the reference lists
them): Protobuf field presence, a nullable column, an optional element or attribute, a `null`
alternative. On a list, `T?[]` makes the elements nullable and `T[]?` the list. A default on a
non-null field says what an omitted value means and never makes the field nullable.

**Nesting is nominal.** A model declared inside another is its own named type, written
`Outer.Inner`, and two nested models of the same shape are different types. A shape or an enum
written in place as a field's type is spelled inline but is just as nominal: it is hoisted to a
nested declaration named `<Model><Field>` (`OrderShipping`), or by `@name("…")`, and every target,
`diff`, and `migrate` see that declaration.

**The option vocabulary is fixed.** `id`, `unique`, `index`, `embed`, `min`, `max`, `match`,
`minItems`, and `maxItems` are the whole of it (sections 5 and 9); a decimal's precision and scale
are part of its type, `decimal(p, s)`. There are no user-defined constraints. A target that cannot
enforce a bound or a pattern says so with a warning, never silently.

**Attributes tune, options model.** Options are the language's own facts about a field. A
`@proto`, `@sql`, `@xsd`, `@jsonschema`, or `@openapi` attribute may change a name, a storage
representation, or a mapping strategy of something the schema already says. No attribute adds a
type, a field, or a constraint the language cannot express on its own, with one exception:
`@sql(type)` is written into the DDL verbatim, so the column type it names, and anything else its
text says, is your responsibility and not the compiler's.

**Schemas map by default.** A schema is a Protobuf package, a Postgres schema, an XML namespace,
and a JSON Schema id unless an attribute says otherwise. A schema that declares a service is also
one OpenAPI document.

**Services are a layer on top.** A service names operations whose requests and responses are the
models and unions the data language already declares, each the whole document even when its model
has a key. The data language does not change to host it: the Postgres, XML Schema, and JSON
Schema outputs ignore services, and the Protobuf output writes each one as a gRPC `service` after
the messages, which stay what they are. `operation` is still reserved.

**References mean the key on every target.** A field, a list element, a union member, or a map
value typed as a model with a key holds that model's key, on Protobuf, XML Schema, JSON Schema,
and OpenAPI exactly as in Postgres: one field `<field>_<key>`, or one `<Model>Key` object for a
composite key. A union holds keys wherever it stands, as a service's response too; a model named
as a request or response is the document itself and stays whole. `{ embed }` on a field or a union
member asks for the whole model instead and means a copy on every target, Postgres included. A
key is a non-null scalar or enum, checked for every target. A back-reference, `@relation(field)`,
stores nothing on any target.

A later version that needs a new keyword recognises it only where a keyword can appear, so a name
you chose under 2.0 keeps working.

## The promise

The compiler follows semantic versioning, and the language version is the compiler's major. Take a
schema set that compiles without errors under 2.0. Every 2.x compiler promises:

**Meaning.** The set compiles without errors, and every declaration means the same thing: the same
types, nullability, defaults, options, ordinals, references, and names after overrides.

**Output.** For each target, the output is compatible with what 2.0 produced, in the sense
`schemata diff` uses (section 20): Protobuf messages stay wire-compatible, Postgres rows and DDL
stay loadable, XML Schema and JSON Schema instances stay valid, an OpenAPI client keeps making the
same calls, and a gRPC client keeps calling the same method paths with the same messages. The text
may change between releases, in comments, formatting, ordering, or a fixed bug in a construct that
was wrong. A change `diff` would call a note is listed in the release notes; a change it would call
breaking does not ship in 2.x.

**Diagnostics.** A code keeps its number, its severity, and its meaning, and a retired code is never
reused. Every family stays where it is: SCH0 for syntax, SCH1 for the language and core checks,
SCH20, SCH21, SCH22, and SCH23 for the Protobuf, Postgres, XML Schema, and JSON Schema targets,
SCH24 for import, SCH25 for evolution, SCH26 for the OpenAPI target, SCH27 for migration. A minor
release may add warnings, so a `--strict` build can fail after an upgrade; every new code is in that
release's notes. A set that compiled without errors keeps compiling without errors, unless it
compiled only because of a compiler bug, which the notes name.

**The command line.** Every command, flag, and exit code stays. The JSON report shapes of
`check`, `compile`, `fmt`, `upgrade`, `diff`, `migrate`, `import`, and `targets` stay, the `kind`
values of `diff` and `migrate` included; a minor may add fields. Not promised: `fmt`'s layout (a
change is listed in the notes and `fmt --check` may fail after an upgrade), the `.schemata` files
`import` writes, and the wording of messages and of the human report.

**Upgrading.** `schemata upgrade` keeps converting every schema a 1.x compiler accepted, except
those it reports, for the whole of 2.x. Section 23 of the reference lists them: a positional
refinement on anything but a decimal (`string(3)`), a refinement given as a bare name or written
on a service payload, a keyword rename onto a name already declared in the same scope (a field
`model` beside `model_value`), and a schema renamed onto one another file declares
(`shop.schema` beside `shop.schema_value`). A later 2.x may convert more, never fewer.

**The editor.** `schemata lsp` keeps every capability it advertises in 2.0 and the shape of its
initialization and configuration options.

A change that cannot keep one of these waits for 3.0.

## What the promise does not cover

The Kotlin modules are internal: the IR, the target interface, the language server's workspace
layer, and the test kit. They change without notice in a minor. If a plugin story needs a public
API, a later minor publishes one with its own promise.

The grammar repositories for editors (`tree-sitter-schemata`, the VS Code and Zed extensions)
follow the language; they have their own version numbers, and their 2.x releases go with a 2.x
compiler.

## Corners, written down

Some behaviours are easy to miss. Each is part of the language as 2.0 defines it.

- An attribute attaches by line: one that trails a `schema` header or a field starts on the line
  of what it trails, and one that starts on a later line leads the next member instead. `fmt` never
  wraps a trailing attribute onto a line of its own.
- An option name is not a keyword, so `index int32 { index }` is a field named `index` with an
  index on it.
- An inline shape or enum is legal only as a field's type, directly or as its list's element; a
  union member, an alias, a payload, or a type argument names a declared type.
- `{ unique }` and `{ index }` may sit on an embedded model, a union, or an inline shape, covering
  every column the field produces, but never on a list or a map, except `{ unique }` on a list of
  a keyed model, which makes it a set: each parent holds each key once.
- `{ embed }` on a reference to a keyed model copies the model on every target: Postgres writes
  its columns, the key among them, under the field's prefix with no foreign key, as 1.x's
  `@sql(strategy = embed)` did.
- An inline shape has no key: `{ id }` on one of its fields or `@@id` in its body is an error;
  declare a nested model to give it one. When a model writes `@@id(…)`, `{ id }` sits on exactly
  the fields it names or on none.
- A hoisted name may not hide a name the model can already see, at the schema's top level or in a
  model it is nested in (SCH1053): `status enum { … }` in `Order` beside a top-level
  `OrderStatus` needs `@name("…")`.
- A reference sent by key as `<field>_<key>` may not land on a name the model reserves (SCH1020).
- `@@timestamps` numbers its two fields after the last explicit ordinal, so a field added to the
  model later moves both, and `diff` reports that as renames and type changes. Where the ordinals
  must stay put, write `created_at instant` and `updated_at instant?` as fields instead.
- `fmt` and `upgrade` write `list<T>` as `T[]`, except where `T[]` cannot say it: a list of lists,
  `list<T[]>`, since a type takes one `[]`, and a list of maps that bound their own size,
  `list<map<K, V> { maxItems 3 }> { maxItems 10 }`, whose two bounds could not share one block.
- A string may not hold a control character below U+0020 other than tab, newline, and carriage
  return, nor U+FFFE or U+FFFF, written as itself or as `\u{…}` (SCH0005); a tab typed between
  the quotes means a tab. A doc comment follows the same rule.
- A `match` pattern is taken as written apart from `\"`, and accepts whatever Java's
  `java.util.regex` compiles; the compiler never rejects a pattern Java accepts, and a target that
  cannot carry a construct warns (SCH2105, SCH2201, SCH2301).
- A number keeps the scale it was written with: `= 1.50` stays `1.50` in every output.
- Leading zeros are decimal, never octal: `= 007` and `#007` both mean 7.
- Some spellings are accepted and rewritten by `fmt`: an ordinal written against its name (`#1x`),
  spaces or comments inside a dotted name, commas between enum values, and `list<T>` where `T[]`
  says the same.
- A line starting `////` is a doc comment whose text starts with `/`; a doc comment with no
  declaration after it is a syntax error (SCH0001).
- A model may hold itself through a required field (`model A { a A }`, A without a key); no
  instance of it can be finite until the cycle passes through `?`, a list, a map, a union, or a
  key.
- `@deprecated` on an alias is accepted and has no effect on any output.
- `null` is reserved: no field, enum value, or segment of a schema name may be called `null`, and
  `= null` always means the literal.
- An import alias is lower_snake (SCH1045), and a file imports a schema once and gives an alias
  to one import only (SCH1046).
- The Protobuf target ends a nullable message-typed field, a nullable model, union, `instant`, or
  `duration`, with a `// schemata: T?` comment, which is what lets `import --from proto` read the
  field back as nullable. It is a comment only.
- A service or rpc name claims a Protobuf name, so a service that shares its proto name with a
  message, or two operations whose UpperCamel forms coincide (`list_v2` and `list_v_2`), are an
  error under the Protobuf target (SCH2004); `@proto(name)` on one of them clears it.

## Upgrading from 1.x

Section 23 of the reference covers the move: what `schemata upgrade` rewrites, the one change of
meaning (a reference to a keyed model holds its key on Protobuf, XML Schema, JSON Schema, and
OpenAPI, where 1.x copied the model, and `{ embed }` brings the copy back), and the JSON `kind`
values and message wording that changed with it. It also lists what `upgrade` reports: the names
it renames because 2.0 keeps them as keywords (SCH0009), with the Postgres `UPDATE` a renamed enum
value needs, and the few schemas it cannot convert. A 1.x file given to any 2.0 command is one
error, SCH0008, until `upgrade` rewrites it. 1.x stays installable from its release tags.

## Retiring something

When a construct or a behaviour has to go, it goes in three steps across versions.

1. A minor release deprecates it. The compiler reports a warning with its own code that names the
   replacement, the reference marks it, and the release notes list it. Under `--strict` the warning
   is an error, like any warning.
2. Every later 2.x release keeps accepting it with the same warning. Where the replacement is
   mechanical, `schemata fmt` rewrites the old form to the new one, so one run clears the warning
   across a project.
3. The next major removes it, and that release's notes say which version last accepted it. Where
   the removal is mechanical, as the 1.x syntax was, a command like `schemata upgrade` does the
   rewrite.

The same steps apply to a target's output: a representation a target stops producing is produced
alongside a warning first, then dropped at the next major.
