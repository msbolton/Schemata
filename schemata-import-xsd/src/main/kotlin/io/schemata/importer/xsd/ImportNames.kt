package io.schemata.importer.xsd

import io.schemata.target.Names

/**
 * Converts XSD names to Schemata identifiers and back, and derives a namespace from a file path.
 */
object ImportNames {
    private val lowerSnakePattern = Regex("^[a-z][a-z0-9_]*$")
    private val invalidRun = Regex("[^a-z0-9_]+")
    private val underscoreRun = Regex("_+")
    private val nonAlnumRun = Regex("[^A-Za-z0-9]+")
    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    // Matches the xsd target's own NCName check: an `@xsd(name)` override it would reject as not a
    // valid XML name (for example, one starting with a digit, as a bare enumeration value may) can
    // never regenerate the original text, so the importer must not offer it as an override.
    private val ncName = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")

    // Every Schemata keyword lexes as its own token, never as an identifier, so a name spelled like
    // one would not parse where a declared name is expected.
    private val keywords =
        setOf(
            "namespace",
            "import",
            "as",
            "record",
            "enum",
            "union",
            "alias",
            "reserved",
            "true",
            "false",
            "service",
            "operation",
            "stream",
        )

    fun isLowerSnake(s: String): Boolean = lowerSnakePattern.matches(s) && s !in keywords

    fun isNamespaceSegment(s: String): Boolean = isLowerSnake(s)

    fun isValidOverride(s: String): Boolean = ncName.matches(s)

    /**
     * `full-name` → `full_name`, `fullName` → `full_name`, `1x` → `v1x`, `true` → `true_`. A result
     * starting with a digit is prefixed with a letter rather than `_`: lower_snake, like every
     * Schemata identifier, must start with a letter, so a leading underscore would only trade one
     * invalid identifier for another. A keyword takes a trailing `_`, since it cannot be a name.
     */
    fun lowerSnake(s: String): String {
        var out = Names.snakeCase(s).replace(invalidRun, "_").replace(underscoreRun, "_").trim('_')
        if (out.isEmpty()) out = "v"
        if (out.first().isDigit()) out = "v$out"
        if (out in keywords) out += "_"
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

    /**
     * The Schemata name for an XSD type named [xsdName], and, when regenerating that name exactly
     * requires an `@xsd(name)` override, the override value: `OrderType` → (`Order`, `null`), since
     * the default regeneration (`<name>Type`) already reproduces it; `gpxType` → (`Gpx`, `"gpx"`),
     * since only `@xsd(name = "gpx")` regenerates `gpxType` exactly; `Address` → (`Address`,
     * `null`), since there's no `Type` suffix to give back — `<name>Type` always ends in `Type`, so
     * no override can ever make the regenerated type exactly `Address`. Collisions between two
     * types that would otherwise land on the same Schemata name, or on the same regenerated type
     * name, are the caller's job.
     */
    fun typeOverride(xsdName: String): Pair<String, String?> {
        val hasTypeSuffix = xsdName.length > 4 && xsdName.endsWith("Type")
        val remainder = if (hasTypeSuffix) xsdName.removeSuffix("Type") else xsdName
        val name = upperCamel(remainder)
        if (!hasTypeSuffix || name + "Type" == xsdName) return name to null
        return name to remainder
    }

    /** `GPX-1.1.xsd` → `gpx_1_1`: the file stem, lower-snaked. */
    fun namespaceStem(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val stem = fileName.substringBeforeLast('.', fileName)
        return lowerSnake(stem)
    }
}
