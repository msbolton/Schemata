package io.schemata.importer

/**
 * [relative] joined to [basePath]'s directory, `/`-separated whichever separator either argument is
 * spelled with, with `.` segments dropped and each `..` taking back the segment before it, so
 * `maindoc/a.xsd` and `../common/b.xsd` name `common/b.xsd`; a `..` with nothing left to take back
 * is kept. A path the reader of a schema writes with `/` (an XSD `schemaLocation`, a proto
 * `import`) resolves to the key the importer's inputs are filed under on every platform.
 */
fun resolvePath(basePath: String, relative: String): String {
    val base = basePath.replace('\\', '/')
    val rel = relative.replace('\\', '/')
    val dir = base.substringBeforeLast('/', "")
    val joined = if (dir.isEmpty()) rel else "$dir/$rel"
    val out = ArrayDeque<String>()
    joined.split('/').forEach { seg ->
        when (seg) {
            "",
            "." -> Unit
            ".." ->
                if (out.isNotEmpty() && out.last() != "..") out.removeLast() else out.addLast(seg)
            else -> out.addLast(seg)
        }
    }
    return (if (joined.startsWith("/")) "/" else "") + out.joinToString("/")
}
