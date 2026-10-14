package io.schemata.importer.openapi

/**
 * A JSON pointer into a document, written as a URI fragment: `#` for the whole document, then one
 * `/segment` per step, with `~` escaped as `~0` and `/` as `~1`, so the path `/orders` under
 * `paths` is `#/paths/~1orders`. swagger-parser keeps no line numbers, so every importer message
 * names its place with one of these.
 */
@JvmInline
value class Pointer(val text: String) {
    operator fun div(segment: String): Pointer =
        Pointer("$text/" + segment.replace("~", "~0").replace("/", "~1"))

    operator fun div(index: Int): Pointer = Pointer("$text/$index")

    /** The steps after `#`, unescaped; `~1` is undone before `~0`, so `~01` reads back as `~1`. */
    val segments: List<String>
        get() =
            text.removePrefix("#").split('/').drop(1).map {
                it.replace("~1", "/").replace("~0", "~")
            }

    override fun toString(): String = text

    companion object {
        val ROOT = Pointer("#")
    }
}
