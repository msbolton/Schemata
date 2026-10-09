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
class ProtoSymbols(
    files: List<ProtoFile>,
    /** Each file's path to the files its `import` statements resolved to. */
    imports: Map<String, List<ProtoFile>> = emptyMap(),
) {
    private val byName = LinkedHashMap<String, Symbol>()

    /** Every proper prefix of a symbol's name: packages and enclosing messages alike. */
    private val prefixes = HashSet<String>()

    /** The paths of the files that declare a symbol under each prefix. */
    private val prefixOwners = HashMap<String, MutableSet<String>>()

    private val importedBy: Map<String, List<ProtoFile>> = imports
    private val publicOf: Map<String, Set<String>> =
        files.associate { f -> f.path to f.imports.filter { it.public }.map { it.path }.toSet() }
    private val visible = HashMap<String, Set<String>>()

    init {
        files.forEach { f ->
            val prefix = f.pkg?.let { "$it." } ?: ""
            fun add(symbol: Symbol) {
                byName.putIfAbsent(symbol.fullName, symbol)
                var name = symbol.fullName
                while ('.' in name) {
                    name = name.substringBeforeLast('.')
                    prefixes += name
                    prefixOwners.getOrPut(name) { mutableSetOf() } += f.path
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

    /** The file that declares [fullName], or null. */
    fun declaringFile(fullName: String): ProtoFile? = byName[fullName]?.file

    /**
     * The paths of the files a name in [file] can refer to, as protoc sees them: the file itself,
     * each file it imports, and whatever those re-export through `import public`, transitively.
     * Nothing else the run read is visible, so an unrelated package cannot capture a name.
     */
    fun visibleFrom(file: ProtoFile): Set<String> =
        visible.getOrPut(file.path) {
            val out = LinkedHashSet<String>()
            out += file.path
            importedBy[file.path].orEmpty().forEach { out += publicClosure(it, HashSet()) }
            out
        }

    /** [file], then the files it imports with `import public`, and theirs in turn. */
    private fun publicClosure(file: ProtoFile, seen: MutableSet<String>): Set<String> {
        if (!seen.add(file.path)) return emptySet()
        val out = LinkedHashSet<String>()
        out += file.path
        val reexported = publicOf[file.path].orEmpty()
        importedBy[file.path]
            .orEmpty()
            .filter { it.path in reexported }
            .forEach { out += publicClosure(it, seen) }
        return out
    }

    /**
     * [name] as written at a use site inside [scope] (package segments, then enclosing messages): a
     * leading dot is fully qualified; otherwise the first segment is looked up from the innermost
     * scope outward, as a symbol or as a package, and the rest resolves inside what it found, as
     * protoc does. With [from], only what that file can see counts: a package that exists only in a
     * file it does not import cannot capture a segment, and the symbol found must be declared in a
     * visible file. Without it, every file read is visible.
     */
    fun resolve(name: String, scope: List<String>, from: ProtoFile? = null): Symbol? {
        val seen = from?.let { visibleFrom(it) }
        fun hit(full: String): Symbol? =
            byName[full]?.takeIf { seen == null || it.file.path in seen }
        if (name.startsWith(".")) return hit(name.substring(1))
        val first = name.substringBefore('.')
        val rest = name.substringAfter('.', "")
        for (depth in scope.size downTo 0) {
            val candidate = (scope.take(depth) + first).joinToString(".")
            val counts =
                if (seen == null) candidate in byName || candidate in prefixes
                else
                    byName[candidate]?.file?.path in seen ||
                        prefixOwners[candidate].orEmpty().any { it in seen }
            if (counts) return hit(if (rest.isEmpty()) candidate else "$candidate.$rest")
        }
        return null
    }

    /**
     * The paths of the files, other than [file], that declare a message or enum one of its field
     * types (map keys and values included) or rpc payloads names.
     */
    fun referencedFiles(file: ProtoFile): Set<String> {
        val out = LinkedHashSet<String>()
        fun use(name: String, scope: List<String>) {
            resolve(name, scope, file)?.let { if (it.file.path != file.path) out += it.file.path }
        }
        fun message(m: ProtoMessage, scope: List<String>) {
            val here = scope + m.name
            m.fields.forEach { f ->
                if (f.type == "map") {
                    f.mapKey?.let { use(it, here) }
                    f.mapValue?.let { use(it, here) }
                } else use(f.type, here)
            }
            m.messages.forEach { message(it, here) }
        }
        val scope = scopeOf(file)
        file.messages.forEach { message(it, scope) }
        file.services.forEach { s ->
            s.rpcs.forEach {
                use(it.request.name, scope)
                use(it.response.name, scope)
            }
        }
        return out
    }

    /** The file-level scope for [file]: its package segments. */
    fun scopeOf(file: ProtoFile): List<String> = file.pkg?.split('.') ?: emptyList()
}
