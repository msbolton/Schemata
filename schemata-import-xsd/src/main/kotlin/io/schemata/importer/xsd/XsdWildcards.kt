package io.schemata.importer.xsd

import io.schemata.importer.UnitAnnotation

/** The `@xsd` keys a wildcard or mixed-content field needs so the XSD target writes it back. */
internal object XsdWildcards {
    /**
     * `@xsd(process)` for a wildcard's `processContents`: XSD's own default when it is absent is
     * `strict`, while the target writes `lax` unless told otherwise, so only `lax` needs no key.
     */
    fun processAnnotation(processContents: String?): UnitAnnotation? {
        val value = processContents ?: "strict"
        return if (value == "lax") null else UnitAnnotation("xsd", "process", "\"$value\"")
    }

    /** `@xsd(wildcard)` for a namespace constraint other than XSD's default, `##any`. */
    fun wildcardAnnotation(namespace: String?): UnitAnnotation? =
        namespace?.takeIf { it != "##any" }?.let { UnitAnnotation("xsd", "wildcard", "\"$it\"") }

    fun any(p: XParticle.Any): List<UnitAnnotation> =
        listOfNotNull(
            UnitAnnotation("xsd", "any", null),
            processAnnotation(p.processContents),
            wildcardAnnotation(p.namespace),
        )

    fun anyAttribute(a: XAttributeUse.AnyAttribute): List<UnitAnnotation> =
        listOfNotNull(
            UnitAnnotation("xsd", "any_attribute", null),
            processAnnotation(a.processContents),
            wildcardAnnotation(a.namespace),
        )

    /** `any`, then `any_2`, `any_3`… for the second and later wildcards of one record. */
    fun anyName(index: Int): String = if (index == 1) "any" else "any_$index"
}
