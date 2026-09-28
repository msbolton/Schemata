package io.schemata.cli.report

import io.schemata.cli.PipelineResult
import io.schemata.cli.TargetResult
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity

/** One reported diagnostic. [target] is null for parse and analysis diagnostics. */
data class Entry(val diagnostic: Diagnostic, val target: String?, val promoted: Boolean) {
    val severity: Severity
        get() = if (promoted) Severity.ERROR else diagnostic.severity
}

/** [path] is relative to the output root: `<target>/<file path>`. */
data class Written(val target: String, val path: String)

data class Skipped(val target: String, val errors: Int)

/**
 * Everything a run reports, in the order it prints. Entries are sorted by file, line, column, code,
 * then message; equal keys keep emission order. Under strict every warning is promoted to an error
 * here, so emitters never see the promotion.
 */
data class Report(
    val entries: List<Entry>,
    val written: List<Written>,
    val skipped: List<Skipped>,
    val checkOnly: Boolean,
) {
    val errors: Int
        get() = entries.count { it.severity == Severity.ERROR }

    val warnings: Int
        get() = entries.count { it.severity == Severity.WARNING }

    val promotions: Int
        get() = entries.count { it.promoted }

    /** 0 when nothing was reported, 2 when only warnings, 1 when any error. */
    val exitCode: Int
        get() =
            when {
                errors > 0 -> 1
                warnings > 0 -> 2
                else -> 0
            }

    companion object {
        private val order =
            compareBy<Entry>(
                { it.diagnostic.span.file },
                { it.diagnostic.span.startLine },
                { it.diagnostic.span.startColumn },
                { it.diagnostic.code.id },
                { it.diagnostic.message },
            )

        fun of(result: PipelineResult, strict: Boolean, checkOnly: Boolean): Report {
            val entries =
                (result.core.map { entry(it, null, strict) } +
                        result.targets.flatMap { t ->
                            t.diagnostics.map { entry(it, t.name, strict) }
                        })
                    .sortedWith(order)
            val written = mutableListOf<Written>()
            val skipped = mutableListOf<Skipped>()
            if (!checkOnly) {
                result.targets.forEach { target ->
                    val errors = errorsIn(target, strict)
                    if (errors > 0) skipped += Skipped(target.name, errors)
                    else
                        target.files.forEach {
                            written += Written(target.name, "${target.name}/${it.path}")
                        }
                }
            }
            return Report(entries, written, skipped, checkOnly)
        }

        private fun entry(d: Diagnostic, target: String?, strict: Boolean) =
            Entry(d, target, promoted = strict && d.severity == Severity.WARNING)

        private fun errorsIn(target: TargetResult, strict: Boolean): Int =
            target.diagnostics.count { strict || it.severity == Severity.ERROR }
    }
}
