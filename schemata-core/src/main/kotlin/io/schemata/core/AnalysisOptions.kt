package io.schemata.core

/** [strictOrdinals]: implicit ordinals are errors; the CLI's `--strict`. */
data class AnalysisOptions(val strictOrdinals: Boolean = false) {
    companion object {
        val DEFAULT = AnalysisOptions()
    }
}
