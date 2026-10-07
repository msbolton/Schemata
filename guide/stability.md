# What is stable

Schemata 1.0 freezes the data language. This page says what that means: what a 1.x compiler
promises for a schema that compiled under 1.0, how something is retired when it has to be, and
which parts of the project the promise does not cover.

## The language, settled

Six questions shaped the language. They are settled as follows, and a 1.x release does not reopen
them.

**Absence is one concept.** `T?` is the only way to say a value may be absent. Each target maps it
to its own notion of presence (section 17 of the reference lists them): Protobuf field presence, a
nullable column, an optional element or attribute, a `null` alternative. A default on a non-null
field says what an omitted value means and never makes the field nullable.

**Nested records are nominal.** A record declared inside another is its own named type, written
`Outer.Inner`. Two nested records of the same shape are different types.

**The refinement vocabulary is fixed.** `min`, `max`, `pattern`, and the collection bounds are the
whole of it (section 5); a decimal's precision and scale are part of its type, `decimal(p, s)`.
There are no user-defined constraints. A target that cannot enforce a refinement says so with a
warning, never silently.

**Annotations tune, they do not model.** A `@proto`, `@sql`, `@xsd`, `@jsonschema`, or `@openapi`
key may change a name, a storage representation, or a mapping strategy of something the schema
already says. No annotation adds a type, a field, or a constraint the language cannot express on its
own, with one exception: `@sql(type)` is written into the DDL verbatim, so the column type it names,
and anything else its text says, is your responsibility and not the compiler's.

**Namespaces map by default.** A namespace is a Protobuf package, a Postgres schema, an XML
namespace, and a JSON Schema id unless an annotation says otherwise. A namespace that declares a
service is also one OpenAPI document.

**Services are a layer on top.** A service, added in 1.2, names operations whose requests and
responses are the records and unions the data language already declares. The data language did not
change to host it: a schema without a service means what it meant under 1.0, and the Postgres, XML
Schema, and JSON Schema outputs ignore services. Since 1.3 the Protobuf output writes each service
as a gRPC `service` after the messages, which stay what they were. `operation` is still reserved.

A later version that needs a new keyword recognises it only where a keyword can appear, so a name
you chose under 1.0 keeps working.

## The promise

The compiler follows semantic versioning, and the language version is the compiler's major. Take a
schema set that compiles without errors under 1.0. Every 1.x compiler promises:

**Meaning.** The set compiles without errors, and every declaration means the same thing: the same
types, nullability, defaults, refinements, ordinals, and names after overrides.

**Output.** For each target, the output is compatible with what 1.0 produced, in the sense
`schemata diff` uses (section 20): Protobuf messages stay wire-compatible, Postgres rows and DDL
stay loadable, XML Schema and JSON Schema instances stay valid. The text may change between
releases, in comments, formatting, ordering, or a fixed bug in a construct that was wrong. A change
`diff` would call a note is listed in the release notes; a change it would call breaking does not
ship in 1.x.

**Diagnostics.** A code keeps its number, its severity, and its meaning, and a retired code is never
reused. Every family stays where it is: SCH0 for syntax, SCH1 for the language and core checks,
SCH20, SCH21, SCH22, and SCH23 for the Protobuf, Postgres, XML Schema, and JSON Schema targets,
SCH24 for import, SCH25 for evolution, SCH26 for the OpenAPI target, SCH27 for migration. A minor release may add
warnings, so a `--strict` build can fail after an upgrade; every new code is in that release's
notes. A set that compiled without errors keeps compiling without errors, unless it compiled only
because of a compiler bug, which the notes name.

**The command line.** Every command, flag, and exit code stays. The JSON report shapes of
`check`, `compile`, `fmt`, `diff`, `import`, and `targets` stay; a minor may add fields. Not
promised: `fmt`'s layout (a change is listed in the notes and `fmt --check` may fail after an
upgrade), the `.schemata` files `import` writes, and the wording of messages and of the human
report.

**The editor.** `schemata lsp` keeps every capability it advertises in 1.0 and the shape of its
initialization and configuration options.

**Services.** Services and the OpenAPI target are a 1.2 addition, and carry the same promise from
1.2 on: a schema set with services that compiles without errors under 1.2 means the same thing in
every later 1.x, and its OpenAPI documents stay compatible with what 1.2 wrote, in the sense
`schemata diff` uses, so a client generated from one keeps making the same calls. The gRPC services
the Protobuf target writes are a 1.3 addition, with the same promise from 1.3 on: a gRPC client
generated from one keeps calling the same method paths with the same messages.

A change that cannot keep one of these waits for 2.0.

## What the promise does not cover

The Kotlin modules are internal: the IR, the target interface, the language server's workspace
layer, and the test kit. They change without notice in a minor. If a plugin story needs a public
API, a later minor publishes one with its own promise.

The grammar repositories for editors (`tree-sitter-schemata`, the VS Code and Zed extensions)
follow the language; they have their own version numbers.

## Corners, written down

Some behaviours are easy to miss. Each is part of the language as 1.0 defines it.

- A string may not hold a control character below U+0020 other than tab, newline, and carriage
  return, nor U+FFFE or U+FFFF, written as itself or as `\u{…}` (SCH0005); a tab typed between
  the quotes means a tab. A doc comment follows the same rule.
- `pattern` accepts whatever Java's `java.util.regex` compiles; the compiler never rejects a
  pattern Java accepts, and a target that cannot carry a construct warns (SCH2105, SCH2201,
  SCH2301).
- A number keeps the scale it was written with: `= 1.50` stays `1.50` in every output.
- Leading zeros are decimal, never octal: `= 007` and `#007` both mean 7.
- Some spellings are accepted and rewritten by `fmt`: an ordinal written against its name (`#1x`),
  spaces or comments inside a dotted name, and enum values without commas.
- Positional refinements may follow named ones (`decimal(max = 5, 19, 4)`); they are accepted and
  kept as written.
- A line starting `////` is a doc comment whose text starts with `/`; a doc comment with no
  declaration after it is a syntax error (SCH0001).
- A record may hold itself through a required field (`record A { a: A }`); no instance of it can
  be finite until the cycle passes through `?`, a list, a map, or a union.
- `@deprecated` on an alias is accepted and has no effect on any output.
- `null` is reserved: no field, enum value, or namespace segment may be called `null`, and
  `= null` always means the literal.
- An import alias is lower_snake (SCH1045), and a file imports a namespace once and gives an alias
  to one import only (SCH1046).
- Since 1.1, the Protobuf target ends a nullable message-typed field, a nullable record, union,
  `instant`, or `duration`, with a `// schemata: T?` comment, which is what lets
  `import --from proto` read the field back as nullable. It is a comment only: the field, its
  number, and the wire format are what 1.0 wrote.
- Since 1.3, a schema with services gets a `service` block at the end of its `.proto`, and an
  `import "google/protobuf/empty.proto"` when an operation has no request or no response. Its
  messages and enums are what 1.2 wrote, and a schema without a service gets the same `.proto`
  byte for byte. A service or rpc name now claims a Protobuf name where 1.2 wrote nothing, so a
  service that shares its proto name with a message (one renamed by `@proto(name)`, say), or two
  operations whose UpperCamel forms coincide (`list_v2` and `list_v_2`), are an error under the
  Protobuf target (SCH2004); `@proto(name)` on one of them clears it.

## Upgrading from 0.x

Two changes in 0.9 alter what an existing schema means without any message, so look for them
before upgrading.

- In an ordinary string, `\n`, `\t`, and `\r` were two characters each and are now a line feed, a
  tab, and a carriage return: `"C:\temp\new"` no longer means a path.
- In a `pattern`, `\\` was one backslash and is now two, since a pattern is taken as written:
  `pattern = "^\\d+$"` was the regex `^\d+$` and now matches a backslash followed by `d`s. Every
  pattern the 0.6 to 0.8 importer wrote that holds a backslash is like this.

This finds the strings and patterns to look at. It over-matches, so read each line it prints:

```sh
grep -nE '\\[ntr]|pattern *= *".*\\\\' -r --include='*.schemata' .
```

Re-import a schema that came from XSD with the new version, or halve the doubled backslashes in
its patterns by hand.

Imported names change too, but loudly. The 0.x importer wrote a keyword-named element with a
trailing underscore (`true_`), which is no longer lower_snake, and kept an element named `null`,
which is now reserved; the importer now writes `true_value` and `null_value`, and the help on the
old names suggests the same. The other tightenings of 0.9 also report themselves as errors: an
unknown escape (SCH0004), a control character (SCH0005), a doubled or trailing underscore in a
name, an alias or import the rules above forbid, a `float32` or `float64` default or bound beyond
the type's finite range (SCH1042, SCH1038), and two fields of a record whose Protobuf JSON names
collide (SCH2008).

## Retiring something

When a construct or a behaviour has to go, it goes in three steps across versions.

1. A minor release deprecates it. The compiler reports a warning with its own code that names the
   replacement, the reference marks it, and the release notes list it. Under `--strict` the warning
   is an error, like any warning.
2. Every later 1.x release keeps accepting it with the same warning. Where the replacement is
   mechanical, `schemata fmt` rewrites the old form to the new one, so one run clears the warning
   across a project.
3. The next major removes it, and that release's notes say which version last accepted it.

The same steps apply to a target's output: a representation a target stops producing is produced
alongside a warning first, then dropped at the next major.
