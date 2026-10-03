# What is stable

Schemata 1.0 freezes the data language. This page says what that means: what a 1.x compiler
promises for a schema that compiled under 1.0, how something is retired when it has to be, and
which parts of the project the promise does not cover.

## The language, settled

Six questions shaped the language. They are settled as follows, and a 1.x release does not reopen
them.

**Absence is one concept.** `T?` is the only way to say a value may be absent. Each target maps it
to its own notion of presence (section 16 of the reference lists them): Protobuf field presence, a
nullable column, an optional element or attribute, a `null` alternative. A default on a non-null
field says what an omitted value means and never makes the field nullable.

**Nested records are nominal.** A record declared inside another is its own named type, written
`Outer.Inner`. Two nested records of the same shape are different types.

**The refinement vocabulary is fixed.** `min`, `max`, `pattern`, `precision`, `scale`, and the
collection bounds are the whole of it (section 5). There are no user-defined constraints. A target
that cannot enforce a refinement says so with a warning, never silently.

**Annotations tune, they do not model.** A `@proto`, `@sql`, `@xsd`, or `@jsonschema` key may
change a name, a storage representation, or a mapping strategy of something the schema already
says. No annotation adds a type, a field, or a constraint the language cannot express on its own.

**Namespaces map by default.** A namespace is a Protobuf package, a Postgres schema, an XML
namespace, and a JSON Schema id unless an annotation says otherwise.

**Services are a later layer.** `service`, `operation`, and `stream` are reserved for it. The data
language does not change to host it.

A later version that needs a new keyword recognises it only where a keyword can appear, so a name
you chose under 1.0 keeps working.

## The promise

The compiler follows semantic versioning, and the language version is the compiler's major. Take a
schema set that compiles without errors under 1.0. Every 1.x compiler promises:

**Meaning.** The set compiles without errors, and every declaration means the same thing: the same
types, nullability, defaults, refinements, ordinals, and names after overrides.

**Output.** For each target, the output is compatible with what 1.0 produced, in the sense
`schemata diff` uses (section 19): Protobuf messages stay wire-compatible, Postgres rows and DDL
stay loadable, XML Schema and JSON Schema instances stay valid. The text may change between
releases, in comments, formatting, ordering, or a fixed bug in a construct that was wrong. A change
`diff` would call a note is listed in the release notes; a change it would call breaking does not
ship in 1.x.

**Diagnostics.** A code keeps its number, its severity, and its meaning, and a retired code is never
reused. Every family stays where it is: SCH0 for syntax, SCH1 for the language and core checks,
SCH20, SCH21, SCH22, and SCH23 for the Protobuf, Postgres, XML Schema, and JSON Schema targets,
SCH24 for import, SCH25 for evolution. A minor release may add warnings, so a `--strict` build can fail after an
upgrade; every new code is in that release's notes. A set that compiled without errors keeps
compiling without errors, unless it compiled only because of a compiler bug, which the notes name.

**The command line.** Commands, flags, exit codes, and the JSON report shapes of `check`,
`compile`, `diff`, `import`, and `targets` stay. A minor may add a flag or a field, never remove or
rename one.

**The editor.** `schemata lsp` keeps every capability it advertises in 1.0 and the shape of its
initialization and configuration options.

A change that cannot keep one of these waits for 2.0.

## What the promise does not cover

The Kotlin modules are internal: the IR, the target interface, the language server's workspace
layer, and the test kit. They change without notice in a minor. If a plugin story needs a public
API, a later minor publishes one with its own promise.

The grammar repositories for editors (`tree-sitter-schemata`, the VS Code and Zed extensions)
follow the language; they have their own version numbers.

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
