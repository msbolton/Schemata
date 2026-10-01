package io.schemata.importer.xsd

import io.schemata.target.Names

/**
 * Converts XSD names to Schemata identifiers and back, and derives a namespace from a file path.
 */
object ImportNames {
    private val lowerSnakePattern = Regex("^[a-z][a-z0-9_]*$")
    private val upperCamelPattern = Regex("^[A-Z][A-Za-z0-9]*$")
    private val invalidRun = Regex("[^a-z0-9_]+")
    private val underscoreRun = Regex("_+")
    private val nonAlnumRun = Regex("[^A-Za-z0-9]+")
    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    fun isLowerSnake(s: String): Boolean = lowerSnakePattern.matches(s)

    fun isUpperCamel(s: String): Boolean = upperCamelPattern.matches(s)

    fun isNamespaceSegment(s: String): Boolean = isLowerSnake(s)

    /** `full-name` → `full_name`, `fullName` → `full_name`, `1x` → `_1x`. */
    fun lowerSnake(s: String): String {
        var out = Names.snakeCase(s).replace(invalidRun, "_").replace(underscoreRun, "_").trim('_')
        if (out.isEmpty()) out = "_"
        if (out.first().isDigit()) out = "_$out"
        return out
    }

    /** `gpxType` → `GpxType`, `wpt-point` → `WptPoint`. */
    fun upperCamel(s: String): String =
        s.split(nonAlnumRun)
            .filter { it.isNotEmpty() }
            .flatMap { it.split(camelBoundary) }
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /** `OrderType` → `Order` when the remainder is UpperCamel; null otherwise. */
    fun stripType(s: String): String? {
        if (!s.endsWith("Type") || s.length <= 4) return null
        val remainder = s.removeSuffix("Type")
        return if (isUpperCamel(remainder)) remainder else null
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
