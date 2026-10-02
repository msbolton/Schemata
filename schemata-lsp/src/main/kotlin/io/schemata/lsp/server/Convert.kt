package io.schemata.lsp.server

import io.schemata.lang.Severity
import io.schemata.lsp.workspace.LineIndex
import io.schemata.lsp.workspace.OutlineKind
import io.schemata.lsp.workspace.OutlineNode
import io.schemata.lsp.workspace.TextPosition
import io.schemata.lsp.workspace.TextRange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal fun Position.toText() = TextPosition(line, character)

internal fun TextPosition.toLsp() = Position(line, character)

internal fun TextRange.toLsp() = Range(start.toLsp(), end.toLsp())

/** The message carries the help line too, since the protocol has no field for it. */
internal fun io.schemata.lang.Diagnostic.toLsp(lines: LineIndex): Diagnostic =
    Diagnostic(
            lines.range(span).toLsp(),
            help?.let { "$message\n\nhelp: $it" } ?: message,
            when (severity) {
                Severity.ERROR -> DiagnosticSeverity.Error
                Severity.WARNING -> DiagnosticSeverity.Warning
            },
            "schemata",
        )
        .also { it.code = Either.forLeft(code.id) }

internal fun OutlineNode.toLsp(): DocumentSymbol =
    DocumentSymbol(
        name,
        when (kind) {
            OutlineKind.NAMESPACE -> SymbolKind.Namespace
            OutlineKind.RECORD -> SymbolKind.Struct
            OutlineKind.ENUM -> SymbolKind.Enum
            OutlineKind.UNION -> SymbolKind.Interface
            OutlineKind.ALIAS -> SymbolKind.TypeParameter
            OutlineKind.FIELD -> SymbolKind.Field
            OutlineKind.VALUE -> SymbolKind.EnumMember
        },
        range.toLsp(),
        selection.toLsp(),
        null,
        children.map { it.toLsp() },
    )
