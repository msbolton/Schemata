package io.schemata.core

import io.schemata.core.annotations.AnnotationRegistry

/**
 * [strictOrdinals]: implicit ordinals are errors; the CLI's `--strict`. [annotations]: the keys the
 * compilation accepts; core's own by default, every known target's when the CLI runs.
 */
data class AnalysisOptions(
    val strictOrdinals: Boolean = false,
    val annotations: AnnotationRegistry = AnnotationRegistry.CORE,
) {
    companion object {
        val DEFAULT = AnalysisOptions()
    }
}
