# Schemata

![ci](https://github.com/msbolton/Schemata/actions/workflows/ci.yml/badge.svg)

Schemata is a schema language and compiler. You describe a data model once in
`.schemata` files and compile it to Protobuf, Postgres DDL, XML Schema, and JSON Schema, and the
services that use it to OpenAPI 3.1 and to gRPC `service` blocks in the Protobuf output, with every
lossy decision reported as a warning, or import one from an existing XML Schema, Protobuf, or
Postgres DDL schema. A reference to a model with a key means that key on every target.

This is Schemata 2.0. Coming from 1.x, run `schemata upgrade` over your schemas; the reference's
[Upgrading from 1.x](guide/reference.md#23-upgrading-from-1x) section lists every rewrite and the
one change of meaning.

## Install

On macOS (Apple silicon or Intel) and Linux x64 (glibc 2.34 or later), Homebrew is the short way:

```text
brew install msbolton/schemata/schemata
```

The formula installs the same binary the releases page attaches and is bumped by every release.

Each release on the [releases page](https://github.com/msbolton/Schemata/releases) attaches a
native binary per platform and the jar:

| Asset | Runs on |
|---|---|
| `schemata-<version>-linux-x64.tar.gz` | Linux, x86-64 (glibc 2.34 or later) |
| `schemata-<version>-macos-arm64.tar.gz` | macOS 12 or later, Apple silicon |
| `schemata-<version>-macos-x64.tar.gz` | macOS 12 or later, Intel |
| `schemata-<version>-windows-x64.zip` | Windows, x86-64 (`schemata.exe`) |
| `schemata-<version>.jar` | any JDK 21 or later, with `java -jar` |

For example, on an Apple silicon Mac:

```text
curl -LO https://github.com/msbolton/Schemata/releases/download/v<version>/SHA256SUMS
curl -LO https://github.com/msbolton/Schemata/releases/download/v<version>/schemata-<version>-macos-arm64.tar.gz
tar -xzf schemata-<version>-macos-arm64.tar.gz
./schemata --version
```

`SHA256SUMS` on the same release page lists every asset; check a download against it with:

```text
sha256sum -c SHA256SUMS --ignore-missing          # Linux
shasum -a 256 -c SHA256SUMS --ignore-missing      # macOS
certutil -hashfile schemata-<version>-windows-x64.zip SHA256   # Windows, compare by eye
```

The binaries need no JDK. A binary downloaded through a browser on macOS is quarantined by
Gatekeeper; `xattr -d com.apple.quarantine schemata` clears it, and the `curl` route above does
not trigger it.

To build from a checkout (JDK 21 or later): `./gradlew :schemata-cli:installDist` puts a `schemata`
script under `schemata-cli/build/install/schemata/bin/`, and `./gradlew build` produces a runnable
jar at `schemata-cli/build/libs/schemata-<version>.jar`. With `JAVA_HOME` (or `GRAALVM_HOME`)
pointing at a GraalVM JDK 21, `./gradlew :schemata-cli:nativeCompile` builds the native binary at
`schemata-cli/build/native/nativeCompile/schemata`. To check the native binary against the jar, run:

```text
./gradlew :schemata-cli:test --tests 'io.schemata.cli.NativeImageTest' -Pschemata.nativeBinary=<that path>
```

## Quick start

`contacts.schemata`:

```schemata
schema contacts

enum Kind { #1 personal #2 work }

model Contact {
  #1 id      int64  { id }
  #2 name    string { max 100 }
  #3 email   string { unique, max 254, match "^[^@]+@[^@]+$" }
  #4 kind    Kind   = personal
  #5 company Company?
}

model Company {
  #1 id   int64  { id }
  #2 name string { max 200 }
}
```

A field is `name Type`, then options in braces for what the language itself knows (a key, a
bound, a pattern, a unique constraint), then any `@target(key: value)` attributes that tune one
output. `company` refers to a model with a key, so every target stores the company's key,
`company_id`, rather than a copy of the company.

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

`out/xsd/contacts.xsd` begins:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
           xmlns:tns="urn:schemata:contacts"
           targetNamespace="urn:schemata:contacts"
           elementFormDefault="qualified"
           attributeFormDefault="unqualified">
```

`out/jsonschema/contacts.schema.json` begins:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:schemata:contacts",
  "title": "contacts",
```

The compiler prints one warning per thing Protobuf cannot carry (the email
pattern, the default) and exits 2; `--strict` turns those into errors.

## Commands

    schemata compile [--target proto,sql,xsd,jsonschema,openapi] [--out DIR] [--strict] [--format human|json] [--color auto|always|never] PATHS...
    schemata check   [--target proto,sql,xsd,jsonschema,openapi]             [--strict] [--format human|json] [--color auto|always|never] PATHS...
    schemata import  --from xsd|proto|sql [--out DIR] [--namespace NAME] [--strict] [--format human|json] [--color auto|always|never] PATHS...
    schemata targets [--format human|json]
    schemata fmt     [--check] [--format human|json] [--color auto|always|never] PATHS...
    schemata upgrade [--check] [--format human|json] [--color auto|always|never] PATHS...
    schemata diff    [--target proto,sql,xsd,jsonschema,openapi] [--strict] [--format human|json] [--color auto|always|never] OLD NEW
    schemata migrate [--out DIR] [--allow-destructive] [--strict] [--format human|json] [--color auto|always|never] OLD NEW
    schemata lsp

`compile` writes `--out/<target>/<file>` for every target whose own lowering reported no error, even
when another target failed; the `openapi` target writes one document per schema that declares a
service, and nothing for a schema without one, and the `proto` target writes each service as a gRPC
`service` in its schema's `.proto`. `check` reports everything `compile` would and writes nothing.
`import --from xsd|proto|sql` reads existing `.xsd`, `.proto`, or Postgres `.sql` files and writes
`--out/import/<file>`, one `.schemata` file per schema. `targets` lists each target's attribute keys
and diagnostic codes. `upgrade` rewrites 1.x schema files in the 2.0 syntax, in place; `--check`
writes nothing and fails while any file would change. `diff OLD NEW` judges every change between two
schema versions against each target's compatibility rulebook, so a breaking change is caught before
it ships. `migrate OLD NEW` writes the Postgres DDL that carries a database from one schema version
to the next, refusing to write a step that loses data unless `--allow-destructive` says so. `lsp`
runs the language server an editor starts; the guide's Editor support section covers the VS Code and
Zed extensions and what the server does.

`--strict` reports implicit ordinals and treats every warning, lossy ones included, as an
error. `--format json` prints one document on stdout and nothing on stderr.
`--color auto` follows the terminal the CLI detects on stdout; when redirecting stderr to
a file, pass `--color never`.

| Exit | Meaning |
|---|---|
| 0 | nothing reported |
| 2 | warnings only |
| 1 | any error (after `--strict` promotion), or a usage error |

`fmt` and `upgrade` follow the same codes: 0 when rewritten or already current, 1 when `--check`
finds a difference or a file does not parse. Under `--format json`, `fmt --check` sends the diff to
stderr so stdout holds only JSON. The names of the files plain `fmt` or `upgrade` rewrote go to
stderr too.

## Learn more

- [What is stable](guide/stability.md)
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
A tag of the form `vX.Y.Z-rc.N` publishes a prerelease with the same assets
and leaves the Homebrew formula on the last stable release. The pushed tag
sets the version, even when another tag points at the same commit.
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
| `schemata-target-xsd` | XML Schema model, lowering, renderer |
| `schemata-target-jsonschema` | JSON Schema model, lowering, renderer |
| `schemata-target-openapi` | OpenAPI model, lowering, renderer |
| `schemata-import-api` | the importer interface, the shared model and `SCH24` codes, the Schemata emitter |
| `schemata-import-xsd` | XSD reader and importer |
| `schemata-import-proto` | Protobuf reader and importer |
| `schemata-import-sql` | Postgres DDL reader and importer |
| `schemata-evolution` | the differ and the per-target compatibility rulebooks |
| `schemata-migrate` | the migration planner and renderer over the relational model's provenance |
| `schemata-lsp` | the language server: workspace model, reference index, lsp4j protocol layer |
| `schemata-cli` | command surface |
| `schemata-testkit` | test-only helpers (golden files, protoc, the JDK's XSD validator, a JSON Schema validator, an OpenAPI 3.1 validator) |

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
- `scripts/check-envoy-import <envoy-checkout>` runs the Protobuf importer over a real Envoy
  tree and checks that every target, `protoc` and a proto round trip accept the result. It is a
  local check, not part of CI.
