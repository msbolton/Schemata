package io.schemata.importer.proto

/**
 * A message or enum by its fully qualified name, without a leading dot. [path] holds the names of
 * the messages enclosing it, outermost first, then its own.
 */
data class Symbol(
    val fullName: String,
    val file: ProtoFile,
    val message: ProtoMessage?,
    val enum: ProtoEnum?,
    val path: List<String> = listOf(fullName.substringAfterLast('.')),
)

/**
 * Every message and enum in the input set by fully qualified name, and protoc's scoped lookup. A
 * name declared twice keeps its first declaration.
 */
class ProtoSymbols(files: List<ProtoFile>) {
    private val byName = LinkedHashMap<String, Symbol>()

    /** Every proper prefix of a symbol's name: packages and enclosing messages alike. */
    private val prefixes = HashSet<String>()

    init {
        files.forEach { f ->
            val prefix = f.pkg?.let { "$it." } ?: ""
            fun add(symbol: Symbol) {
                byName.putIfAbsent(symbol.fullName, symbol)
                var name = symbol.fullName
                while ('.' in name) {
                    name = name.substringBeforeLast('.')
                    prefixes += name
                }
            }
            fun message(m: ProtoMessage, scope: String, path: List<String>) {
                val full = scope + m.name
                val here = path + m.name
                add(Symbol(full, f, m, null, here))
                m.messages.forEach { message(it, "$full.", here) }
                m.enums.forEach { add(Symbol("$full.${it.name}", f, null, it, here + it.name)) }
            }
            f.messages.forEach { message(it, prefix, emptyList()) }
            f.enums.forEach { add(Symbol(prefix + it.name, f, null, it, listOf(it.name))) }
        }
    }

    operator fun get(fullName: String): Symbol? = byName[fullName]

    /** Every symbol, in declaration order. */
    val all: Collection<Symbol>
        get() = byName.values

    /**
     * [name] as written at a use site inside [scope] (package segments, then enclosing messages): a
     * leading dot is fully qualified; otherwise the first segment is looked up from the innermost
     * scope outward, as a symbol or as a package, and the rest resolves inside what it found, as
     * protoc does.
     */
    fun resolve(name: String, scope: List<String>): Symbol? {
        if (name.startsWith(".")) return byName[name.substring(1)]
        val first = name.substringBefore('.')
        val rest = name.substringAfter('.', "")
        for (depth in scope.size downTo 0) {
            val candidate = (scope.take(depth) + first).joinToString(".")
            if (candidate in byName || candidate in prefixes) {
                return byName[if (rest.isEmpty()) candidate else "$candidate.$rest"]
            }
        }
        return null
    }

    /** The file-level scope for [file]: its package segments. */
    fun scopeOf(file: ProtoFile): List<String> = file.pkg?.split('.') ?: emptyList()
}
