package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.NoteText
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitOperation
import io.schemata.importer.UnitPayload
import io.schemata.importer.UnitReserved
import io.schemata.importer.UnitService
import io.schemata.lang.SchemataText
import io.schemata.target.Names

/**
 * The ordinals a service's operations have taken so far, beside its [reserved] ranges, which none
 * may take: Schemata refuses two operations of one ordinal and an operation on a reserved one.
 */
private class RpcOrdinals(private val reserved: List<IntRange>) {
    private val used = mutableSetOf<Int>()

    private fun free(n: Int): Boolean = n !in used && reserved.none { n in it }

    /** [wanted] when it is free, else the next free ordinal above it; either is now taken. */
    fun claim(wanted: Int): Int {
        var n = maxOf(wanted, 1)
        while (!free(n)) n++
        used += n
        return n
    }
}

/** Lowers proto services and rpcs to Schemata services, with the bindings their notes carry. */
internal class ServiceLowering(private val lowering: FileLowering) {
    /**
     * The file's services in file order, each named in the namespace's top-level scope beside the
     * messages and enums. A service keeps its name as a message does; its `// schemata: reserved`
     * lines become its `reserved` statement.
     */
    fun services(): List<UnitService> =
        lowering.file.services.mapNotNull { s ->
            val name = typeName(s.name)
            if (!lowering.claim(lowering.topLevel, name, "service", "service", s.name, s.pos))
                return@mapNotNull null
            val where = "service '${s.name}'"
            // Read before the rpcs, whose ordinals must avoid the reserved ones, but reported
            // after them, where the notes stand.
            val notes = s.reservedNotes.map { (text, _) -> NoteText.parseReservedNote(text) }
            val reserved = notes.flatMap { it.orEmpty() }
            val ordinals =
                RpcOrdinals(
                    reserved.filterIsInstance<UnitReserved.Ordinals>().map { it.from..it.to }
                )
            val claimed = mutableMapOf<String, String>()
            val operations = mutableListOf<UnitOperation>()
            s.rpcs.forEach { rpc ->
                operation(rpc, where, claimed, operations.size + 1, ordinals)?.let {
                    operations += it
                }
            }
            s.reservedNotes.zip(notes).forEach { (note, parsed) ->
                if (parsed == null) {
                    lowering.report(
                        ImportCodes.APPROXIMATED,
                        "$where: note '${note.first}' cannot be read; ignored",
                        note.second,
                    )
                }
            }
            s.strayNotes.forEach { (text, pos) ->
                lowering.report(
                    ImportCodes.APPROXIMATED,
                    "$where: note '$text' stands after the last rpc; ignored",
                    pos,
                )
            }
            UnitService(
                name = name,
                operations = operations,
                doc = s.doc,
                annotations = nameAnnotation(name, s.name),
                reserved = reserved,
                deprecated = s.options.flag("deprecated"),
            )
        }

    /**
     * An rpc as an operation, or null when it is dropped: a payload must be a message, or
     * `google.protobuf.Empty` for none. The target writes the UpperCamel form of the operation's
     * name, so a name that form does not give back keeps its proto spelling in `@proto(name)`. A
     * note's ordinal wins; otherwise the operation takes [position], its place among the rpcs kept.
     * An ordinal [ordinals] already holds is replaced by the next free one.
     */
    private fun operation(
        rpc: ProtoRpc,
        service: String,
        claimed: MutableMap<String, String>,
        position: Int,
        ordinals: RpcOrdinals,
    ): UnitOperation? {
        val where = "$service: rpc '${rpc.name}'"
        val scope = lowering.context.symbols.scopeOf(lowering.file)
        val resolved =
            listOf("request" to rpc.request, "response" to rpc.response).map { (role, t) ->
                fun drop(
                    reason: String,
                    help: String = ImportCodes.helpFor(ImportCodes.DROPPED),
                ): Nothing? {
                    lowering.report(
                        ImportCodes.DROPPED,
                        "$where: $role $reason; rpc dropped",
                        rpc.pos,
                        help,
                    )
                    return null
                }
                val full = t.name.removePrefix(".")
                if (full == EMPTY) {
                    if (t.stream) return drop("stream of $EMPTY has no Schemata form")
                    return@map null
                }
                // A well-known type is a message, but Schemata reads it as a scalar or not at all,
                // never as a record an operation could carry.
                if (full.startsWith("google.protobuf.")) {
                    return drop("type '$full' has no Schemata model")
                }
                val symbol = lowering.context.symbols.resolve(t.name, scope, lowering.file)
                if (symbol == null) {
                    lowering.unimported(t.name, scope)?.let { (text, help) ->
                        return drop("type '${t.name}' cannot be resolved; $text", help)
                    }
                }
                if (symbol?.message == null) return drop("type '${t.name}' is not a message")
                t to symbol
            }
        val name = ImportNames.lowerSnake(rpc.name)
        val other = claimed.putIfAbsent(name, rpc.name)
        if (other != null) {
            lowering.report(
                ImportCodes.UNRESOLVED,
                "$where and rpc '$other' both lower to '$name'",
                rpc.pos,
                ImportCodes.RENAME_HELP,
            )
            return null
        }
        val annotations =
            if (Names.upperCamel(name) == rpc.name) emptyList()
            else {
                lowering.report(
                    ImportCodes.RENAMED,
                    "$where: renamed to '$name'",
                    rpc.pos,
                    RPC_RENAME_HELP,
                )
                listOf(UnitAnnotation("proto", "name", SchemataText.string(rpc.name)))
            }
        val note =
            rpc.note?.let { text ->
                NoteText.parseOperationNote(text)
                    ?: null.also {
                        lowering.report(
                            ImportCodes.APPROXIMATED,
                            "$where: note '$text' cannot be read; ignored",
                            rpc.pos,
                        )
                    }
            }
        val (request, response) =
            resolved.map { r ->
                r?.let { (t, symbol) ->
                    UnitPayload(lowering.reference(symbol, emptyList()), t.stream)
                }
            }
        val wanted = note?.ordinal ?: position
        val ordinal = ordinals.claim(wanted)
        if (ordinal != wanted) {
            lowering.report(
                ImportCodes.APPROXIMATED,
                "$where: ordinal #$wanted is already used; the next free ordinal is taken",
                rpc.pos,
            )
        }
        return UnitOperation(
            name = name,
            request = request,
            response = response,
            binding = note?.binding,
            doc = rpc.doc,
            annotations = annotations,
            ordinal = ordinal,
            deprecated = rpc.options.flag("deprecated"),
        )
    }

    private companion object {
        const val EMPTY = "google.protobuf.Empty"
        const val RPC_RENAME_HELP = "keep @proto(name) so the regenerated rpc keeps its proto name"
    }
}
