package io.schemata.cli.report

import io.schemata.core.annotations.AnnotationSpec
import io.schemata.lang.Span
import io.schemata.target.Target

object JsonRenderer {
    fun report(report: Report, out: String): String =
        Json.document(
            Json.Obj(
                "diagnostics" to report.entries.map { entry(it) },
                "written" to
                    report.written.map {
                        Json.Obj("target" to it.target, "path" to "$out/${it.path}")
                    },
                "skipped" to
                    report.skipped.map { Json.Obj("target" to it.target, "errors" to it.errors) },
                "exitCode" to report.exitCode,
            )
        )

    fun targets(targets: List<Target<*>>): String =
        Json.document(
            Json.Obj(
                "targets" to
                    targets.map { t ->
                        Json.Obj(
                            "name" to t.name,
                            "annotations" to t.annotationSpecs.map { spec(it) },
                            "codes" to
                                t.codes.map {
                                    Json.Obj(
                                        "id" to it.id,
                                        "severity" to it.severity.name.lowercase(),
                                        "category" to it.category.name.lowercase(),
                                    )
                                },
                        )
                    }
            )
        )

    private fun entry(e: Entry): Json.Obj {
        val d = e.diagnostic
        return Json.Obj(
            "code" to d.code.id,
            "severity" to e.severity.name.lowercase(),
            "category" to d.category.name.lowercase(),
            "promoted" to e.promoted,
            "target" to e.target,
            "message" to d.message,
            "help" to d.help,
            "span" to span(d.span),
        )
    }

    private fun span(s: Span) =
        Json.Obj(
            "file" to s.file,
            "startLine" to s.startLine,
            "startColumn" to s.startColumn,
            "endLine" to s.endLine,
            "endColumn" to s.endColumn,
        )

    private fun spec(s: AnnotationSpec) =
        Json.Obj(
            "key" to s.key,
            "elements" to s.elements.sortedBy { it.ordinal }.map { it.displayName },
            "value" to s.valueKind.name.lowercase(),
            "choices" to s.choices?.sorted(),
            "optional" to s.optional,
        )
}
