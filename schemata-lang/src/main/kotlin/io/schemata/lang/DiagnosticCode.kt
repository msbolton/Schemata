package io.schemata.lang

/**
 * A stable identity for a kind of diagnostic. Codes are allocated from per-module catalogs —
 * [LangCodes] here, `CoreCodes`, `ProtoCodes`, `SqlCodes` downstream — in disjoint ranges: SCH0xxx
 * lang, SCH1xxx core, SCH20xx proto, SCH21xx sql. A test in the CLI module checks uniqueness across
 * all of them.
 */
data class DiagnosticCode(val id: String, val severity: Severity, val category: Category)
