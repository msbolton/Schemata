package io.schemata.importer.xsd

import io.schemata.importer.ImportNames

/** The naming rules that exist only because of how the XSD target names what it writes. */
object XsdNames {
    // Matches the xsd target's own NCName check: an `@xsd(name)` override it would reject as not a
    // valid XML name (for example, one starting with a digit, as a bare enumeration value may) can
    // never regenerate the original text, so the importer must not offer it as an override.
    private val ncName = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")

    fun isValidOverride(s: String): Boolean = ncName.matches(s)

    /**
     * The Schemata name for an XSD type named [xsdName], and, when regenerating that name exactly
     * requires an `@xsd(name)` override, the override value: `OrderType` → (`Order`, `null`), since
     * the default regeneration (`<name>Type`) already reproduces it; `gpxType` → (`Gpx`, `"gpx"`),
     * since only `@xsd(name: "gpx")` regenerates `gpxType` exactly; `Address` → (`Address`,
     * `null`), since there's no `Type` suffix to give back.
     */
    fun typeOverride(xsdName: String): Pair<String, String?> {
        val hasTypeSuffix = xsdName.length > 4 && xsdName.endsWith("Type")
        val remainder = if (hasTypeSuffix) xsdName.removeSuffix("Type") else xsdName
        val name = ImportNames.upperCamel(remainder)
        if (!hasTypeSuffix || name + "Type" == xsdName) return name to null
        return name to remainder
    }
}
