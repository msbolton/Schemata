package io.schemata.config

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/**
 * What loading `schemata.toml` reports, `SCH29xx`. Every problem stops the run, so all are errors.
 */
object ConfigCodes {
    /** The file is missing, unreadable, or not valid TOML. */
    val UNREADABLE: DiagnosticCode =
        DiagnosticCode(
            "SCH2901",
            Severity.ERROR,
            Category.SEMANTIC,
            "the configuration file cannot be read or parsed",
        )

    /** A table or key the file may not hold. */
    val UNKNOWN_KEY: DiagnosticCode =
        DiagnosticCode(
            "SCH2902",
            Severity.ERROR,
            Category.SEMANTIC,
            "a configuration table or key is unknown",
        )

    /** A value of the wrong TOML type, or a string outside the key's fixed choices. */
    val BAD_VALUE: DiagnosticCode =
        DiagnosticCode(
            "SCH2903",
            Severity.ERROR,
            Category.SEMANTIC,
            "a configuration value has the wrong type or is outside its choices",
        )

    /** A `{name}` in a target default that is unknown, or that the key may not use. */
    val BAD_PLACEHOLDER: DiagnosticCode =
        DiagnosticCode(
            "SCH2904",
            Severity.ERROR,
            Category.SEMANTIC,
            "a configuration value holds a placeholder that is unknown or not accepted there",
        )

    /** A `[sources]` path that does not exist. */
    val MISSING_SOURCE: DiagnosticCode =
        DiagnosticCode(
            "SCH2905",
            Severity.ERROR,
            Category.SEMANTIC,
            "a configured source path names nothing",
        )

    /** The codes the appendix lists: one entry per id. */
    val all: List<DiagnosticCode> =
        listOf(UNREADABLE, UNKNOWN_KEY, BAD_VALUE, BAD_PLACEHOLDER, MISSING_SOURCE)
}
