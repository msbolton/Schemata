package io.schemata.lang

enum class Severity {
    ERROR,
    WARNING,
}

/** LOSSY marks a lowering that dropped information; targets report it, never hide it. */
enum class Category {
    SYNTAX,
    SEMANTIC,
    LOSSY,
}

data class Diagnostic(
    val severity: Severity,
    val category: Category,
    val message: String,
    val span: Span,
)

val List<Diagnostic>.hasErrors: Boolean
    get() = any { it.severity == Severity.ERROR }
