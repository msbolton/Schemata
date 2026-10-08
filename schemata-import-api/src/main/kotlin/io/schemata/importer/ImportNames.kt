package io.schemata.importer

import io.schemata.lang.Names as LangNames
import io.schemata.target.Names as TargetNames

/**
 * Converts names read from an imported format to Schemata identifiers, and derives a namespace from
 * a file path.
 */
object ImportNames {
    private val lowerSnakePattern = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
    private val invalidRun = Regex("[^a-z0-9_]+")
    private val underscoreRun = Regex("_+")
    private val nonAlnumRun = Regex("[^A-Za-z0-9]+")
    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    // Every keyword lexes as its own token, never as an identifier, so a name spelled like one
    // would not parse where a declared name is expected; `null` is not a keyword, but the language
    // reserves it as a field, enum value, and namespace segment name all the same.
    private val keywords: Set<String> = LangNames.keywords + "null"

    fun isLowerSnake(s: String): Boolean = lowerSnakePattern.matches(s) && s !in keywords

    fun isNamespaceSegment(s: String): Boolean = isLowerSnake(s)

    /**
     * `full-name` → `full_name`, `fullName` → `full_name`, `1x` → `v1x`, `true` → `true_value`. A
     * result starting with a digit is prefixed with a letter rather than `_`: lower_snake, like
     * every Schemata identifier, must start with a letter, so a leading underscore would only trade
     * one invalid identifier for another. A keyword takes a `_value` suffix, since it cannot be a
     * name and a trailing underscore is not a name either.
     */
    fun lowerSnake(s: String): String {
        var out =
            TargetNames.snakeCase(s).replace(invalidRun, "_").replace(underscoreRun, "_").trim('_')
        if (out.isEmpty()) out = "v"
        if (out.first().isDigit()) out = "v$out"
        if (out in keywords) out += "_value"
        return out
    }

    /**
     * `gpxType` → `GpxType`, `wpt-point` → `WptPoint`, `3d` → `V3d`. A result starting with a digit
     * (or with nothing left at all) is prefixed with `V`, since a type name must start with a
     * letter; it can never be a keyword, since every keyword is lowercase.
     */
    fun upperCamel(s: String): String {
        val out =
            s.split(nonAlnumRun)
                .filter { it.isNotEmpty() }
                .flatMap { it.split(camelBoundary) }
                .filter { it.isNotEmpty() }
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return if (out.isEmpty() || out.first().isDigit()) "V$out" else out
    }

    /** `GPX-1.1.xsd` → `gpx_1_1`: the file stem, lower-snaked. */
    fun namespaceStem(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val stem = fileName.substringBeforeLast('.', fileName)
        return lowerSnake(stem)
    }
}
