# Schemata

![ci](https://github.com/msbolton/Schemata/actions/workflows/ci.yml/badge.svg)

Schemata is a schema language and compiler. You describe a data model once in
`.schemata` files and compile it to Protobuf and to Postgres DDL, with every
lossy decision reported as a warning.

## Install

No release exists yet; once one does, it will appear on the
[releases page](https://github.com/msbolton/Schemata/releases). Until then, build from a checkout
(JDK 21 or later): `./gradlew :schemata-cli:installDist` puts a `schemata` script under
`schemata-cli/build/install/schemata/bin/`. `./gradlew build` also produces a runnable jar at
`schemata-cli/build/libs/schemata-<version>.jar`; run it with `java -jar`.

## Quick start

`contacts.schemata`:

```schemata
namespace contacts

enum Kind { #1 personal, #2 work }

record Contact {
  @sql(key)
  #1 id:    int64
  #2 name:  string(max = 100)
  #3 email: string(max = 254, pattern = "^[^@]+@[^@]+$")
  #4 kind:  Kind = personal
}
```

```text
java -jar schemata-<version>.jar compile --out out contacts.schemata
```

`out/proto/contacts.proto` begins:

```proto
syntax = "proto3";

package contacts;
```

`out/sql/contacts.sql` begins:

```sql
CREATE SCHEMA IF NOT EXISTS "contacts";
```

The compiler prints one warning per thing Protobuf cannot carry (the email
pattern, the default) and exits 2; `--strict` turns those into errors.

## Commands

    schemata compile [--target proto,sql] [--out DIR] [--strict] [--format human|json] [--color auto|always|never] PATHS...
    schemata check   [--target proto,sql]            [--strict] [--format human|json] [--color auto|always|never] PATHS...
    schemata targets [--format human|json]

`compile` writes `--out/<target>/<file>` for every target whose own lowering reported no
error, even when another target failed. `check` reports everything `compile` would and
writes nothing. `targets` lists each target's annotation keys and diagnostic codes.

`--strict` reports implicit ordinals and treats every warning, lossy ones included, as an
error. `--format json` prints one document on stdout and nothing on stderr.
`--color auto` follows the terminal the CLI detects on stdout; when redirecting stderr to
a file, pass `--color never`.

| Exit | Meaning |
|---|---|
| 0 | nothing reported |
| 2 | warnings only |
| 1 | any error (after `--strict` promotion), or a usage error |

## Learn more

- [Language reference](guide/reference.md)
- [Worked examples](guide/examples.md) and the [`examples/`](examples/) directory
- [Diagnostics](guide/diagnostics.md) and [annotations](guide/annotations.md)

## Contributing

### Build

    ./gradlew build          # compile, test, lint, dependency-direction check
    ./gradlew :schemata-cli:installDist
    schemata-cli/build/install/schemata/bin/schemata compile --out out schema.schemata

### Releasing

Tag `main` with `vX.Y.Z` and push the tag. The release workflow builds, runs
the full test suite, and attaches `schemata-X.Y.Z.jar` to a GitHub release.
`java -jar schemata-X.Y.Z.jar --version` prints the version; an untagged
build prints `X.Y.Z-dev+<sha>`. The `native-image spike` workflow can be
dispatched by hand from the Actions tab.

### Modules

Dependencies point strictly downward; the build fails if they do not.

| Module | Owns |
|---|---|
| `schemata-lang` | grammar, parser, AST, parse diagnostics |
| `schemata-core` | IR, analysis, checks |
| `schemata-target-api` | `Target` SPI: `lower` then `render` |
| `schemata-target-proto` | Protobuf model, lowering, renderer |
| `schemata-target-sql` | relational model, lowering, renderer |
| `schemata-cli` | command surface |
| `schemata-testkit` | test-only helpers (golden files, protoc) |

### Testing conventions

- Lowering is tested by asserting on the **model** it produces, never on rendered text.
- Rendered text is golden-tested. Golden files live in `src/test/resources/golden/`.
  Run `SCHEMATA_GOLDEN_UPDATE=1 ./gradlew test` to accept new output, then review the diff.
- Generated `.proto` is validated with a real `protoc`; generated DDL is executed against a
  real Postgres via Testcontainers (needs Docker; the test is skipped without it).
- Every diagnostic code has a fixture under `schemata-cli/src/test/resources/diagnostics/<code>/`
  pinning its message and help; the coverage test fails the build for a code without one.
  Run `SCHEMATA_GOLDEN_UPDATE=1 ./gradlew :schemata-cli:test --tests '*DiagnosticFixturesTest*'`
  after changing a message, then review the diff.
