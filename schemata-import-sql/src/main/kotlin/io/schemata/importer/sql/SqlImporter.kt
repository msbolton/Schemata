package io.schemata.importer.sql

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.Roots
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.importResult
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span

/**
 * Runs the reader, the lowering, the emitter, and the formatter over a set of `.sql` inputs. Every
 * input is read into one catalog, so a constraint, index, or comment may name a table declared in
 * another file, as the SQL target writes a foreign key between two files into the later one.
 *
 * Each schema becomes one namespace. A schema that is the only one its first file declares takes
 * that file's path under its root, or, for a file named on its own, the schema's name when that is
 * a namespace name and the file stem otherwise; a schema sharing its file with others takes its own
 * name, or the stem. The implicit `public` schema never names a namespace. `@sql(schema)` carries
 * the schema whenever it is not the namespace's last segment, which is where the target takes it
 * from.
 */
object SqlImporter : Importer {
    override fun import(
        inputs: List<ImportInput>,
        namespace: String?,
        locate: (String) -> ImportInput?,
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val files = inputs.map { SqlReader.read(it.path, it.content) }
        files.forEach { f ->
            f.errors.forEach { e ->
                report(
                    diagnostics,
                    f,
                    ImportCodes.UNRESOLVED,
                    "${f.path}:${e.pos.line}:${e.pos.col}: cannot parse: ${e.message}",
                    e.pos,
                    "give the importer a file Postgres accepts",
                )
            }
            f.statements.filterIsInstance<SqlStatement.Dropped>().forEach {
                report(diagnostics, f, ImportCodes.DROPPED, "${f.path}: ${it.kind} dropped", it.pos)
            }
        }

        // A schema created but given no table lowers to nothing, so it neither takes a namespace
        // nor makes its file one of several schemas.
        val withTables =
            files
                .flatMap { it.statements.filterIsInstance<SqlStatement.CreateTable>() }
                .map { it.table.schema ?: PUBLIC }
                .toSet()
        val owners = LinkedHashMap<String, Int>()
        val schemasOf = files.map { f -> schemasDeclared(f).filter { it in withTables } }
        schemasOf.forEachIndexed { index, schemas ->
            schemas.forEach { owners.putIfAbsent(it, index) }
        }

        val namespaces = LinkedHashMap<String, SchemaNamespace>()
        val claimed = mutableMapOf<String, String>()
        owners.entries.forEachIndexed { order, (schema, index) ->
            val input = inputs[index]
            val file = files[index]
            val declared = schema.takeUnless { it == PUBLIC }
            val stem = input.path.replace('\\', '/').substringAfterLast('/')
            val (name, derived) =
                when {
                    order == 0 && namespace != null -> namespace to false
                    schemasOf[index].size == 1 -> Roots.namespaceFor(input, declared, stem)
                    else -> Roots.namespaceFor(ImportInput(input.path, "", null), declared, stem)
                }
            if (derived) {
                report(
                    diagnostics,
                    file,
                    ImportCodes.RENAMED,
                    "${file.path}: namespace '$name' was derived from the file name",
                    SqlPos(1, 1),
                )
            }
            claimed.putIfAbsent(name, schema)?.let { other ->
                report(
                    diagnostics,
                    file,
                    ImportCodes.UNRESOLVED,
                    "${file.path}: schema '$schema' and schema '$other' both lower to namespace '$name'",
                    SqlPos(1, 1),
                    ImportCodes.RENAME_HELP,
                )
            }
            val annotations =
                if (name.substringAfterLast('.') == schema) emptyList()
                else listOf(UnitAnnotation("sql", "schema", SchemataText.string(schema)))
            namespaces[schema] = SchemaNamespace(name, annotations, file.path)
        }

        val units = SqlImport.lower(files, namespaces, diagnostics)
        return importResult(units, diagnostics)
    }

    /** The schemas [file] creates or puts a table in, in the order it first names them. */
    private fun schemasDeclared(file: SqlFile): List<String> =
        file.statements
            .mapNotNull {
                when (it) {
                    is SqlStatement.CreateSchema -> it.name
                    is SqlStatement.CreateTable -> it.table.schema ?: PUBLIC
                    else -> null
                }
            }
            .distinct()
}

/** The schema Postgres puts an unqualified table in. */
internal const val PUBLIC = "public"

/** The namespace a schema lowers to, its annotations, and the file that first declares it. */
internal data class SchemaNamespace(
    val name: String,
    val annotations: List<UnitAnnotation>,
    val sourcePath: String,
)

internal fun report(
    diagnostics: MutableList<Diagnostic>,
    file: SqlFile,
    code: DiagnosticCode,
    message: String,
    pos: SqlPos,
    help: String = ImportCodes.helpFor(code),
) {
    diagnostics +=
        Diagnostic(code, message, Span(file.path, pos.line, pos.col, pos.line, pos.col), help)
}
