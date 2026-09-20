# Schemata

![ci](https://github.com/msbolton/Schemata/actions/workflows/ci.yml/badge.svg)

A schema language and compiler. Author a data model once in `.schemata`; emit
Protobuf and SQL DDL (more targets to follow).

## Build

    ./gradlew build          # compile, test, lint, dependency-direction check
    ./gradlew :schemata-cli:installDist
    build/install/schemata/bin/schemata compile --target proto,sql --out out schema.schemata

## Modules

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

## Testing conventions

- Lowering is tested by asserting on the **model** it produces, never on rendered text.
- Rendered text is golden-tested. Golden files live in `src/test/resources/golden/`.
  Run `SCHEMATA_GOLDEN_UPDATE=1 ./gradlew test` to accept new output, then review the diff.
- Generated `.proto` is validated with a real `protoc`; generated DDL is executed against a
  real Postgres via Testcontainers (needs Docker; the test is skipped without it).
