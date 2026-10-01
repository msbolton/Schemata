package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations

/** The raw `@<target>(<key> = "…")` text, before any validity check. */
fun Annotations.string(target: String, key: String): String? =
    (this[target][key] as? AnnotationValue.Str)?.value

fun Annotations.flag(target: String, key: String): Boolean =
    this[target][key] is AnnotationValue.Flag

fun Annotations.bool(target: String, key: String): Boolean? =
    (this[target][key] as? AnnotationValue.Bool)?.value

/** Whether the element carries the core `@deprecated`. */
val Annotations.deprecated: Boolean
    get() = "deprecated" in this[""]
