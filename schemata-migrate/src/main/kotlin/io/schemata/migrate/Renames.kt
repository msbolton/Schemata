package io.schemata.migrate

import io.schemata.target.sql.Naming

/**
 * One rename among the names of [scope] (a table's columns or constraints, a schema's tables or
 * indexes). [step] builds the statement from the name it moves from and the name it moves to, which
 * differ from [from] and [to] when the rename goes through a temporary name.
 */
internal class Rename(
    val scope: Any,
    val from: String,
    val to: String,
    val step: (String, String) -> Step,
)

/**
 * Orders renames so none moves onto a name another rename in its scope has yet to vacate: a chain
 * (`a → b`, `b → c`) runs from its free end. A cycle (two names swapping) has no free end, so one
 * of its names steps aside to `<name>__schemata_tmp` first, the others follow, and it moves to its
 * target last.
 */
internal fun ordered(renames: List<Rename>): List<Step> =
    renames
        .groupBy { it.scope }
        .values
        .flatMap { scope ->
            val pending = scope.map { it to it.from }.toMutableList()
            val out = mutableListOf<Step>()
            while (pending.isNotEmpty()) {
                val held = pending.map { it.second }.toSet()
                val free = pending.indexOfFirst { it.first.to !in held }
                if (free >= 0) {
                    val (rename, from) = pending.removeAt(free)
                    out += rename.step(from, rename.to)
                } else {
                    val (rename, from) = pending[0]
                    val temporary = Naming.identifier("${from}__schemata_tmp")
                    out += rename.step(from, temporary)
                    pending[0] = rename to temporary
                }
            }
            out
        }
