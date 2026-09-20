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

data class Diagnostic(val code: DiagnosticCode, val message: String, val span: Span) {
    val severity: Severity
        get() = code.severity

    val category: Category
        get() = code.category
}

val List<Diagnostic>.hasErrors: Boolean
    get() = any { it.severity == Severity.ERROR }
