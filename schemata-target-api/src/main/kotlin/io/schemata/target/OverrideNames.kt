package io.schemata.target

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.kindWord
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * A target's `@<target>(name = "…")` overrides, validated once per declaration or enum value
 * however many files refer to them. [problem] returns null for a valid value, else the message
 * tail; an invalid override is reported with [code] and ignored, so the declared name is used.
 */
class OverrideNames(
    private val schema: Schema,
    private val target: String,
    private val code: DiagnosticCode,
    private val sink: MutableList<Diagnostic>,
    private val problem: (String) -> String?,
    private val help: (String) -> String,
) {
    private val declOverrides = mutableMapOf<QualifiedName, String?>()
    private val valueOverrides = mutableMapOf<Pair<QualifiedName, String>, String?>()

    fun nameOverride(decl: TypeDecl): String? =
        declOverrides.memo(decl.qualifiedName) {
            overrideName(decl.annotations, "${decl.kindWord} '${decl.name}'", decl.nameSpan)
        }

    /** [value]'s emitted name: its valid override, else its own name. */
    fun enumValueName(enum: EnumType, value: EnumValue): String =
        valueOverrides.memo(enum.qualifiedName to value.name) {
            overrideName(
                value.annotations,
                "enum value '${enum.name}.${value.name}'",
                value.nameSpan,
            )
        } ?: value.name

    /** The valid override on [annotations], or null (reported once per call) for an invalid one. */
    fun overrideName(annotations: Annotations, where: String, span: Span): String? {
        val value = annotations.string(target, "name") ?: return null
        val tail = problem(value) ?: return value
        sink +=
            Diagnostic(code, "$where: @$target(name = \"$value\") $tail", span, help = help(tail))
        return null
    }

    private fun <K> MutableMap<K, String?>.memo(key: K, compute: () -> String?): String? {
        if (key !in this) this[key] = compute()
        return getValue(key)
    }
}
