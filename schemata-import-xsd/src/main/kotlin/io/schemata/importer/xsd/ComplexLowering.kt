package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.xsd.XsdImport.ANY_ATTRIBUTE
import io.schemata.importer.xsd.XsdImport.ANY_ELEMENT
import io.schemata.importer.xsd.XsdImport.Claim
import io.schemata.importer.xsd.XsdImport.ClaimKind
import io.schemata.importer.xsd.XsdImport.EntryShape
import io.schemata.importer.xsd.XsdImport.MIXED_CONTENT
import io.schemata.importer.xsd.XsdImport.MapResult
import io.schemata.importer.xsd.XsdImport.attributeForm
import io.schemata.importer.xsd.XsdImport.elementForm
import io.schemata.importer.xsd.XsdImport.fieldNameFor
import io.schemata.importer.xsd.XsdImport.isMapUnique
import io.schemata.importer.xsd.XsdImport.listRefinements
import io.schemata.importer.xsd.XsdImport.unresolved

/**
 * Lowers complex types, attributes, groups, and elements to record fields, flattening extension and
 * restriction.
 */
internal class ComplexLowering(private val context: ImportContext) {
    /**
     * [ct]'s fields, the declarations nested in its record, and the annotations the record itself
     * takes from its content (`@xsd(all)`).
     */
    internal fun fieldsAndNested(
        ct: XComplexType,
        whereCollision: String,
        recordName: String,
        siblings: MutableList<UnitDecl>,
        enclosing: Set<QName> = emptySet(),
    ): Triple<List<UnitField>, List<UnitDecl>, List<UnitAnnotation>> {
        val nested = mutableListOf<UnitDecl>()
        val claimed = mutableMapOf<String, Claim>()
        val recordAnnotations = mutableListOf<UnitAnnotation>()
        // Seeds the cycle guard with this type's own identity (when it has one), so a direct
        // self-extension is caught on the first hop, not just a longer cycle back to it. The
        // types enclosing an inline type are kept apart, in [enclosing]: they are not on its
        // extension chain, and are only what a recursion cut looks for.
        val visited = mutableSetOf<QName>()
        if (ct.name != null) visited += QName(context.doc.targetNamespace, ct.name)
        val fields =
            allFieldsOf(
                ct,
                whereCollision,
                recordName,
                claimed,
                nested,
                siblings,
                visited,
                enclosing,
                recordAnnotations = recordAnnotations,
            )
        val settled =
            context.renameAttributesBesideElements(context.settleNames(fields, claimed), claimed)
        return Triple(settled, nested, recordAnnotations)
    }

    /**
     * Every one of [ct]'s own fields (elements then attributes), resolved through its own
     * extension/restriction chain recursively; used both as a record's own top-level entry and, for
     * an extension, recursively for its base. [visited] guards that chain against a cycle.
     */
    private fun allFieldsOf(
        ct: XComplexType,
        whereCollision: String,
        recordName: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: MutableSet<QName>,
        enclosing: Set<QName>,
        namespace: String? = context.doc.targetNamespace,
        recordAnnotations: MutableList<UnitAnnotation>? = null,
    ): List<UnitField> {
        // An abstract type with concrete descendants is not dropped: it is a union of them, or,
        // when nothing names it, a plain record, which was reported where it is declared.
        if (
            ct.abstract &&
                ct.name?.let {
                    val name = QName(namespace, it)
                    name in context.heads.types || name in context.heads.unreferenced
                } != true
        ) {
            context.diagnostics +=
                context.lossy(ImportCodes.DROPPED, whereCollision, "abstract dropped", ct.line)
        }
        // The types whose content is being lowered: this one and those it sits inside.
        val here = enclosing + listOfNotNull(ct.name?.let { QName(namespace, it) })
        val elementFields =
            contentFields(
                ct,
                ct.content,
                whereCollision,
                recordName,
                claimed,
                nested,
                siblings,
                visited,
                here,
                recordAnnotations,
            )
        val mixedFields =
            listOfNotNull(if (ct.mixed) mixedText(claimed, whereCollision, ct.line) else null)
        val attributeFields =
            expandAttributeUses(ct.attributes).mapNotNull { use ->
                when (use) {
                    is XAttributeUse.Attribute ->
                        attribute(use.attribute, claimed, whereCollision, nested)
                    is XAttributeUse.GroupRef -> null
                    is XAttributeUse.AnyAttribute -> anyAttributes(use, claimed, whereCollision)
                }
            }
        // A complex restriction keeps the base's attributes it does not redeclare, and so
        // does simple content derived from a complex type, whose base declares attributes too.
        val inheritedFrom =
            when (val c = ct.content) {
                is XContent.Restriction -> c.base.takeIf { !c.simple || isComplexTypeRef(c.base) }
                is XContent.Extension -> c.base.takeIf { c.simple && isComplexTypeRef(c.base) }
                else -> null
            }
        val inheritedFields =
            inheritedFrom
                ?.let { inheritedAttributes(it, ct.attributes) }
                .orEmpty()
                .mapNotNull { (path, a) ->
                    context.at(path) {
                        context.inheriting { attribute(a, claimed, whereCollision, nested) }
                    }
                }
        return elementFields + mixedFields + attributeFields + inheritedFields
    }

    /**
     * The character data of a mixed type as `text: string?` (`mixed_text` when a named field takes
     * `text`, see [settleNames]), marked `@xsd(mixed)`. A record has one, however many types along
     * its extension chain are mixed.
     */
    private fun mixedText(
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        line: Int,
    ): UnitField? {
        if (claimed.values.any { it.kind == ClaimKind.MIXED_TEXT }) return null
        return UnitField(
            context.pendingName(ClaimKind.MIXED_TEXT, MIXED_CONTENT, claimed, whereCollision, line),
            UnitType.Scalar("string", emptyList()),
            true,
            null,
            null,
            listOf(UnitAnnotation("xsd", "mixed", null)),
        )
    }

    /**
     * An attribute wildcard as `attributes: map<string, string>` (`any_attributes` when a named
     * field takes `attributes`, see [settleNames]), marked `@xsd(any_attribute)`. A record has one,
     * however many types along its extension chain declare a wildcard.
     */
    private fun anyAttributes(
        use: XAttributeUse.AnyAttribute,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
    ): UnitField? {
        if (claimed.values.any { it.kind == ClaimKind.WILDCARD_ATTRIBUTE }) return null
        val string = UnitType.Scalar("string", emptyList())
        return UnitField(
            context.pendingName(
                ClaimKind.WILDCARD_ATTRIBUTE,
                ANY_ATTRIBUTE,
                claimed,
                whereCollision,
                use.line,
            ),
            UnitType.MapOf(string, string, false, emptyList()),
            false,
            null,
            null,
            XsdWildcards.anyAttribute(use),
        )
    }

    /**
     * An element wildcard as `any` (then `any_2`, `any_3`… past the first name the record's named
     * fields leave free, see [settleNames]), marked `@xsd(any)`: `list<string>` when it repeats,
     * else `string`, nullable when optional.
     */
    internal fun anyField(
        particle: XParticle.Any,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
    ): UnitField? {
        val string = UnitType.Scalar("string", emptyList())
        val repeated = particle.maxOccurs != 1
        return UnitField(
            context.pendingName(
                ClaimKind.WILDCARD,
                ANY_ELEMENT,
                claimed,
                whereCollision,
                particle.line,
            ),
            if (repeated)
                UnitType.ListOf(
                    string,
                    false,
                    listRefinements(particle.minOccurs, particle.maxOccurs),
                )
            else string,
            !repeated && particle.minOccurs == 0,
            null,
            null,
            XsdWildcards.any(particle),
        )
    }

    /**
     * The attributes a type derived from the complex type [base] (a complex restriction, or simple
     * content) keeps without declaring them: every one [base] has, its own and those it derives,
     * that [own] neither redeclares nor prohibits (a prohibition is itself a redeclaration), each
     * with the file that declares it.
     */
    private fun inheritedAttributes(
        base: QName,
        own: List<XAttributeUse>,
    ): List<Pair<String, XAttribute>> {
        val redeclared =
            expandAttributeUses(own).filterIsInstance<XAttributeUse.Attribute>().mapNotNull {
                it.attribute.name ?: it.attribute.ref?.local
            }
        val result = mutableListOf<Pair<String, XAttribute>>()
        val seen = mutableSetOf<QName>()
        var next: QName? = base
        while (next != null && seen.add(next)) {
            val targetDoc = context.docsByNamespace[next.namespace] ?: break
            val ct = targetDoc.complexTypes.firstOrNull { it.name == next!!.local } ?: break
            val path = ct.path.ifEmpty { targetDoc.path }
            expandAttributeUses(ct.attributes).filterIsInstance<XAttributeUse.Attribute>().forEach {
                val name = it.attribute.name ?: it.attribute.ref?.local
                if (
                    name !in redeclared &&
                        result.none { (_, a) -> (a.name ?: a.ref?.local) == name }
                ) {
                    result += path to it.attribute
                }
            }
            next =
                when (val content = ct.content) {
                    is XContent.Extension -> content.base
                    is XContent.Restriction -> content.base
                    else -> null
                }
        }
        return result
    }

    /**
     * The fields [content] declares. An `xs:all` that is the record's own content (when
     * [recordAnnotations] collects the record's annotations) gives the record `@xsd(all)`; one
     * reached through an extension base is imported as a sequence and noted.
     */
    private fun contentFields(
        ct: XComplexType,
        content: XContent,
        whereCollision: String,
        recordName: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: MutableSet<QName>,
        enclosing: Set<QName>,
        recordAnnotations: MutableList<UnitAnnotation>?,
    ): List<UnitField> =
        when (content) {
            is XContent.Sequence ->
                sequenceFields(
                    recordName,
                    content.particles,
                    whereCollision,
                    claimed,
                    nested,
                    siblings,
                    enclosing,
                )
            is XContent.All -> {
                if (recordAnnotations != null) {
                    recordAnnotations += UnitAnnotation("xsd", "all", null)
                } else {
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.APPROXIMATED,
                            whereCollision,
                            "xs:all imported as a sequence",
                            ct.line,
                        )
                }
                reportRepeatsInAll(content.particles)
                // xs:all holds each element at most once, so an element is required or not;
                // one that repeats (XSD 1.1) keeps its own occurrence.
                val particles =
                    content.particles.map { p ->
                        if (p !is XParticle.Element || p.element.maxOccurs != 1) p
                        else
                            XParticle.Element(
                                p.element.copy(minOccurs = if (p.element.minOccurs == 0) 0 else 1)
                            )
                    }
                sequenceFields(
                    recordName,
                    particles,
                    whereCollision,
                    claimed,
                    nested,
                    siblings,
                    enclosing,
                )
            }
            is XContent.Empty -> emptyList()
            is XContent.Choice ->
                if (context.choiceLowering.isUnionType(ct)) {
                    // An extension base that is itself a union: not supported, so its own
                    // content is simply dropped rather than flattened.
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.DROPPED,
                            whereCollision,
                            "extension of a choice-shaped type dropped",
                            ct.line,
                        )
                    emptyList()
                } else {
                    // A record whose content is a bare choice holds it as a sequence would.
                    sequenceFields(
                        recordName,
                        listOf(
                            XParticle.Nested(content, content.minOccurs, content.maxOccurs, ct.line)
                        ),
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                        enclosing,
                    )
                }
            is XContent.Extension ->
                extensionFields(
                    ct,
                    content,
                    whereCollision,
                    recordName,
                    claimed,
                    nested,
                    siblings,
                    visited,
                    enclosing,
                )
            is XContent.Restriction ->
                if (content.simple) {
                    simpleContentFields(
                        "restriction",
                        content.base,
                        content.facets,
                        content.line,
                        whereCollision,
                        claimed,
                    )
                } else {
                    restrictionFields(
                        content,
                        recordName,
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                        enclosing,
                    )
                }
        }

    /**
     * Notes each element of an `xs:all` that may occur more than once, which only XSD 1.1 allows:
     * it is imported as a list, but the XSD 1.0 the target writes cannot hold it.
     */
    private fun reportRepeatsInAll(particles: List<XParticle>) {
        particles.forEach { p ->
            val el = (p as? XParticle.Element)?.element ?: return@forEach
            if (el.maxOccurs != 1 && el.maxOccurs != 0) {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.APPROXIMATED,
                        "element '${el.name ?: el.ref?.local}'",
                        "repeats inside xs:all, which only XSD 1.1 allows; imported as a list",
                        el.line,
                    )
            }
        }
    }

    internal fun sequenceFields(
        recordName: String,
        particles: List<XParticle>,
        whereCollision: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): List<UnitField> {
        val result = mutableListOf<UnitField>()
        expandParticles(particles, whereCollision).forEach { particle ->
            when (particle) {
                is XParticle.Element ->
                    field(particle.element, claimed, whereCollision, nested, siblings, visited)
                        ?.let { result += it }
                is XParticle.Any ->
                    anyField(particle, claimed, whereCollision)?.let { result += it }
                is XParticle.GroupRef -> Unit // only an unresolved ref survives expandParticles
                is XParticle.Nested ->
                    context.at(particle.path) {
                        val content = particle.content
                        when (content) {
                            is XContent.Choice ->
                                result +=
                                    context.choiceLowering.inlineChoiceFields(
                                        recordName,
                                        content,
                                        particle,
                                        whereCollision,
                                        claimed,
                                        nested,
                                        siblings,
                                        visited,
                                    )
                            is XContent.Sequence ->
                                if (particle.minOccurs == 1 && particle.maxOccurs == 1) {
                                    result +=
                                        sequenceFields(
                                            recordName,
                                            content.particles,
                                            whereCollision,
                                            claimed,
                                            nested,
                                            siblings,
                                            visited,
                                        )
                                } else {
                                    groupField(
                                            content,
                                            particle,
                                            whereCollision,
                                            claimed,
                                            nested,
                                            siblings,
                                            visited,
                                        )
                                        ?.let { result += it }
                                }
                            is XContent.All -> {
                                context.diagnostics +=
                                    context.lossy(
                                        ImportCodes.APPROXIMATED,
                                        whereCollision,
                                        "xs:all flattened into the model",
                                        particle.line,
                                    )
                                reportRepeatsInAll(content.particles)
                                result +=
                                    sequenceFields(
                                        recordName,
                                        content.particles,
                                        whereCollision,
                                        claimed,
                                        nested,
                                        siblings,
                                        visited,
                                    )
                            }
                            else -> Unit
                        }
                    }
            }
        }
        return result
    }

    /**
     * A sequence nested in another that occurs other than exactly once, or a repeated reference to
     * a sequence group: a record nested in the enclosing one, named for the group or for the
     * sequence's first element (`LatGroup`), held by a field of the same stem (`lat_group`) that is
     * a list, optional, or plain as the sequence occurs. `null` when the field's name is already
     * taken, which is reported.
     */
    private fun groupField(
        content: XContent.Sequence,
        particle: XParticle.Nested,
        whereCollision: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitField? {
        val first =
            content.particles.firstNotNullOfOrNull {
                (it as? XParticle.Element)?.element?.let { el -> el.name ?: el.ref?.local }
            }
        val base =
            particle.name?.let(ImportNames::upperCamel)
                ?: ((first?.let(ImportNames::upperCamel) ?: "") + "Group")
        val stem =
            particle.name?.let(ImportNames::lowerSnake)
                ?: (ImportNames.lowerSnake(first ?: "group") + "_group")
        // A second group of the same stem is numbered, record and field together.
        var n = 1
        while (
            nested.any { it.name == numbered(base, n, "") } ||
                fieldNameFor(numbered(stem, n, "_")).name in claimed
        ) n++
        val recordName = numbered(base, n, "")
        val original = numbered(stem, n, "_")
        val construct = particle.name?.let { "group '$it'" } ?: "nested sequence"
        val (name, annotations) =
            context.nameAndClaim(
                original,
                ClaimKind.GROUP,
                construct,
                claimed,
                whereCollision,
                particle.line,
            ) ?: return null
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                whereCollision,
                (particle.name?.let { "repeated group '$it'" } ?: "nested sequence") +
                    " imported as model '$recordName' in field '$name'",
                particle.line,
            )
        val ownClaimed = mutableMapOf<String, Claim>()
        val ownNested = mutableListOf<UnitDecl>()
        val fields =
            sequenceFields(
                recordName,
                content.particles,
                whereCollision,
                ownClaimed,
                ownNested,
                siblings,
                visited,
            )
        nested +=
            UnitRecord(
                recordName,
                context.settleNames(fields, ownClaimed),
                ownNested,
                null,
                emptyList(),
            )
        val ref = UnitType.Ref(recordName)
        return if (particle.maxOccurs != 1) {
            UnitField(
                name,
                UnitType.ListOf(
                    ref,
                    false,
                    listRefinements(particle.minOccurs, particle.maxOccurs),
                ),
                false,
                null,
                null,
                annotations,
            )
        } else UnitField(name, ref, particle.minOccurs == 0, null, null, annotations)
    }

    /** [base] for the first of a kind, then [base] numbered from 2 after [separator]. */
    internal fun numbered(base: String, n: Int, separator: String): String =
        if (n == 1) base else "$base$separator$n"

    /** Two occurrence bounds multiplied, `null` (unbounded) when either is. */
    internal fun times(a: Int?, b: Int?): Int? = if (a == null || b == null) null else a * b

    internal fun isComplexTypeRef(qname: QName): Boolean {
        if (qname.namespace == ImportTypes.XS) return false
        val targetDoc = context.docsByNamespace[qname.namespace] ?: return false
        return targetDoc.complexTypes.any { it.name == qname.local }
    }

    private fun extensionFields(
        ct: XComplexType,
        ext: XContent.Extension,
        whereCollision: String,
        recordName: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: MutableSet<QName>,
        enclosing: Set<QName>,
    ): List<UnitField> {
        if (ext.simple) {
            return simpleContentFields(
                "extension",
                ext.base,
                ext.facets,
                ext.line,
                whereCollision,
                claimed,
            )
        }
        // A base already on the chain (a direct self-extension, or a longer cycle back to it):
        // reported once, here, where the cycle closes; the type still imports with its own
        // content only, as if the (unresolvable) extension weren't there.
        if (!visited.add(ext.base)) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    whereCollision,
                    "extension of '${ext.base.local}' cannot be resolved; the base chain is cyclic",
                    ext.line,
                )
            return sequenceFields(
                recordName,
                ext.particles,
                whereCollision,
                claimed,
                nested,
                siblings,
                enclosing,
            )
        }
        val anyType = ext.base == QName(ImportTypes.XS, "anyType")
        val baseFields =
            resolveExtensionBase(
                ext.base,
                whereCollision,
                ext.line,
                claimed,
                nested,
                siblings,
                visited,
                enclosing,
            )
        if (!anyType) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "extension of '${ext.base.local}' has no Schemata equivalent; base fields " +
                        "flattened into the model",
                    ext.line,
                )
        }
        val ownFields =
            sequenceFields(
                recordName,
                ext.particles,
                whereCollision,
                claimed,
                nested,
                siblings,
                enclosing,
            )
        return baseFields + ownFields
    }

    /**
     * A `simpleContent` [kind] (`extension` or `restriction`) of [base]: a record with one `value`
     * field of the base's simple type narrowed by [facets], beside the type's own attributes (and,
     * for a complex base, those it inherits; see [allFieldsOf]). A complex base is followed down
     * its own simple content to the simple type at the root of the chain, see [simpleContentValue].
     */
    private fun simpleContentFields(
        kind: String,
        base: QName,
        facets: List<XFacet>,
        line: Int,
        whereCollision: String,
        claimed: MutableMap<String, Claim>,
    ): List<UnitField> {
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                whereCollision,
                "simpleContent $kind of '${base.local}' has no Schemata equivalent; " +
                    "imported as a model with a 'value' field",
                line,
            )
        val refined = simpleContentValue(kind, base, facets, line, whereCollision)
        val claim =
            context.nameAndClaim(
                "value",
                ClaimKind.FIELD,
                whereCollision,
                claimed,
                whereCollision,
                line,
            ) ?: return emptyList()
        val (name, annotations) = claim
        val list = listOfNotNull(listAnnotation(refined))
        return listOf(UnitField(name, refined, false, null, null, annotations + list))
    }

    /**
     * The `value` type of a simple content [kind] of [base] narrowed by [facets]. A complex [base]
     * with simple content of its own is followed, through as many types as the chain has, to the
     * simple type at its root; the facets along the way all apply, the nearest type's winning where
     * two levels set the same one. A chain that reaches a complex type with element content, or a
     * cycle, is noted and imported as a string.
     */
    private fun simpleContentValue(
        kind: String,
        base: QName,
        facets: List<XFacet>,
        line: Int,
        whereCollision: String,
    ): UnitType {
        val string = UnitType.Scalar("string", emptyList())
        val levels = mutableListOf(facets)
        val seen = mutableSetOf<QName>()
        var next = base
        while (isComplexTypeRef(next)) {
            val ct =
                context.docsByNamespace.getValue(next.namespace).complexTypes.first {
                    it.name == next.local
                }
            val (nextBase, nextFacets) =
                when (val c = ct.content) {
                    is XContent.Extension -> if (c.simple) c.base to c.facets else null
                    is XContent.Restriction -> if (c.simple) c.base to c.facets else null
                    else -> null
                } ?: (null to emptyList())
            if (nextBase == null || !seen.add(next)) {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "simpleContent $kind of complex type '${base.local}' imported as string",
                        line,
                    )
                return context.simpleTypes.restrict(
                    string,
                    facets,
                    line,
                    context.sourcePath,
                    whereCollision,
                )
            }
            levels += nextFacets
            next = nextBase
        }
        val root =
            context.simpleTypes.resolveSimpleTypeByQName(next, whereCollision, line) ?: string
        // Each facet kind from the nearest level that sets it.
        val combined =
            levels
                .flatMap { level -> level.map { it.name }.distinct().map { it to level } }
                .distinctBy { it.first }
                .flatMap { (name, level) -> level.filter { it.name == name } }
        return context.simpleTypes.restrict(
            root,
            combined,
            line,
            context.sourcePath,
            whereCollision,
        )
    }

    /** `@xsd(list)` for a field whose value is a list simple type's; `null` otherwise. */
    private fun listAnnotation(type: UnitType): UnitAnnotation? =
        if (type is UnitType.ListOf) UnitAnnotation("xsd", "list", null) else null

    private fun resolveExtensionBase(
        baseQName: QName,
        whereCollision: String,
        line: Int,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: MutableSet<QName>,
        enclosing: Set<QName>,
    ): List<UnitField> {
        // xs:anyType, the root of every derivation, contributes no fields of its own.
        if (baseQName == QName(ImportTypes.XS, "anyType")) return emptyList()
        val targetDoc = context.docsByNamespace[baseQName.namespace]
        val baseCt = targetDoc?.complexTypes?.firstOrNull { it.name == baseQName.local }
        if (baseCt == null) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    whereCollision,
                    "type '${baseQName.local}' cannot be resolved",
                    line,
                )
            return emptyList()
        }
        // The base's own lines are in its own document, so its diagnostics point there.
        return context.at(baseCt.path.ifEmpty { targetDoc.path }) {
            context.inheriting {
                allFieldsOf(
                    baseCt,
                    whereCollision,
                    baseQName.local,
                    claimed,
                    nested,
                    siblings,
                    visited,
                    enclosing,
                    baseQName.namespace,
                )
            }
        }
    }

    private fun restrictionFields(
        res: XContent.Restriction,
        recordName: String,
        whereCollision: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): List<UnitField> {
        // A restriction of xs:anyType is exactly its own content.
        if (res.base != QName(ImportTypes.XS, "anyType")) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "restriction of '${res.base.local}' has no Schemata equivalent; its own " +
                        "content is used",
                    res.line,
                )
        }
        return sequenceFields(
            recordName,
            res.particles,
            whereCollision,
            claimed,
            nested,
            siblings,
            visited,
        )
    }

    /** A plain anonymous complex type, nested inside its declaring record. */
    private fun buildNestedRecord(
        ct: XComplexType,
        name: String,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitRecord {
        val (fields, nested, annotations) =
            fieldsAndNested(ct, "complex type '$name'", name, siblings, visited)
        return UnitRecord(name, fields, nested, ct.doc, annotations)
    }

    /**
     * An element's anonymous complex type, nested in the enclosing record under the element's
     * UpperCamel name: a union when its content is a bare choice, a record otherwise.
     */
    private fun inlineDeclaration(
        ct: XComplexType,
        elementName: String,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitType.Ref {
        val name = ImportNames.upperCamel(elementName)
        val content = ct.content
        if (content is XContent.Choice && context.choiceLowering.isUnionType(ct)) {
            context.choiceLowering.unionExtras(ct, "union '$name'")
            nested +=
                context.choiceLowering.unionFromChoice(
                    content,
                    name,
                    "union '$name'",
                    ct.doc,
                    siblings,
                    checkMismatch = true,
                    visited = visited,
                )
        } else {
            nested += buildNestedRecord(ct, name, siblings, visited)
        }
        return UnitType.Ref(name)
    }

    /** An anonymous complex type hoisted out of a union member, as a top-level, non-root record. */
    internal fun buildHoistedRecord(
        ct: XComplexType,
        name: String,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName> = emptySet(),
    ): UnitRecord {
        val (fields, nested, annotations) =
            fieldsAndNested(ct, "complex type '$name'", name, siblings, visited)
        return UnitRecord(
            name,
            fields,
            nested,
            ct.doc,
            listOf(UnitAnnotation("xsd", "root", "false")) + annotations,
        )
    }

    /**
     * Groups referenced by `xs:group ref` expand into their own particles in place, recursively; an
     * unresolved group is reported and dropped. A reference to a sequence group that occurs other
     * than exactly once keeps its occurrence on the `Nested` particle it becomes, named for the
     * group, which lowers to a record of its own; so does a reference to a choice or `xs:all`
     * group, whatever its occurrence.
     */
    internal fun expandParticles(particles: List<XParticle>, where: String): List<XParticle> =
        particles.flatMap { p ->
            when (p) {
                is XParticle.GroupRef -> {
                    val group =
                        context.docsByNamespace[p.ref.namespace]?.groups?.firstOrNull {
                            it.name == p.ref.local
                        }
                    if (group == null) {
                        context.diagnostics +=
                            unresolved(
                                context.sourcePath,
                                p.line,
                                "group '${p.ref.local}' cannot be resolved",
                            )
                        emptyList()
                    } else if (p.ref in context.cycles.groups) {
                        context.diagnostics +=
                            unresolved(
                                context.sourcePath,
                                p.line,
                                "group '${p.ref.local}' cannot be resolved; the reference " +
                                    "chain is cyclic",
                            )
                        emptyList()
                    } else {
                        when (val c = group.content) {
                            is XContent.Sequence ->
                                if (p.minOccurs == 1 && p.maxOccurs == 1) {
                                    // Lines inside the group are the group's file's.
                                    context.at(group.path) { expandParticles(c.particles, where) }
                                } else {
                                    listOf(
                                        XParticle.Nested(
                                            c,
                                            p.minOccurs,
                                            p.maxOccurs,
                                            p.line,
                                            name = p.ref.local,
                                            path = group.path,
                                        )
                                    )
                                }
                            else ->
                                listOf(
                                    XParticle.Nested(
                                        c,
                                        p.minOccurs,
                                        p.maxOccurs,
                                        p.line,
                                        path = group.path,
                                    )
                                )
                        }
                    }
                }
                else -> listOf(p)
            }
        }

    /**
     * Attribute groups referenced by `xs:attributeGroup ref` expand into their own attribute uses
     * in place, recursively; an unresolved group is reported and dropped. The xml namespace's
     * `specialAttrs` group is built in; any other group in that namespace is unresolved.
     */
    private fun expandAttributeUses(uses: List<XAttributeUse>): List<XAttributeUse> =
        uses.flatMap { use ->
            when (use) {
                is XAttributeUse.GroupRef -> {
                    if (use.ref == QName(XsdReader.XML, "specialAttrs")) {
                        return@flatMap xmlSpecialAttrs(use.line)
                    }
                    val group =
                        context.docsByNamespace[use.ref.namespace]?.attributeGroups?.firstOrNull {
                            it.name == use.ref.local
                        }
                    if (group == null) {
                        context.diagnostics +=
                            unresolved(
                                context.sourcePath,
                                use.line,
                                "attribute group '${use.ref.local}' cannot be resolved",
                            )
                        emptyList()
                    } else if (use.ref in context.cycles.attributeGroups) {
                        context.diagnostics +=
                            unresolved(
                                context.sourcePath,
                                use.line,
                                "attribute group '${use.ref.local}' cannot be resolved; the " +
                                    "reference chain is cyclic",
                            )
                        emptyList()
                    } else context.at(group.path) { expandAttributeUses(group.attributes) }
                }
                else -> listOf(use)
            }
        }

    /**
     * The xml namespace's `specialAttrs` group, which the xml namespace has no file to look up: a
     * reference to each of `xml:base`, `xml:lang`, `xml:space` and `xml:id`, in that order, each
     * optional, resolved as a reference written out would be.
     */
    private fun xmlSpecialAttrs(line: Int): List<XAttributeUse> =
        listOf("base", "lang", "space", "id").map {
            XAttributeUse.Attribute(
                XAttribute(
                    name = null,
                    ref = QName(XsdReader.XML, it),
                    type = null,
                    inlineSimple = null,
                    use = "optional",
                    default = null,
                    fixed = null,
                    doc = null,
                    line = line,
                    path = context.sourcePath,
                )
            )
        }

    /**
     * The global element [el] refers to, merged with [el]'s own occurrence, nillability, and
     * documentation (when it carries its own); `null` (reported) when the reference can't be
     * resolved.
     */
    internal fun resolveElementRef(el: XElement, whereCollision: String): XElement? {
        val qname = el.ref ?: return el
        val target =
            context.docsByNamespace[qname.namespace]?.elements?.firstOrNull {
                it.name == qname.local
            }
        if (target == null) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    whereCollision,
                    "element '${qname.local}' cannot be resolved",
                    el.line,
                )
            return null
        }
        return target.copy(
            minOccurs = el.minOccurs,
            maxOccurs = el.maxOccurs,
            nillable = el.nillable || target.nillable,
            doc = el.doc ?: target.doc,
        )
    }

    /** The top-level attribute [a] refers to, merged with [a]'s own use, default, and docs. */
    private fun resolveAttributeRef(a: XAttribute, whereCollision: String): XAttribute? {
        val qname = a.ref ?: return a
        // xml:lang, xml:base, xml:space and xml:id are built in: they are string attributes, and
        // the xml namespace has no file to look them up in.
        if (qname.namespace == XsdReader.XML)
            return XAttribute(
                name = qname.local,
                ref = null,
                type = QName(ImportTypes.XS, "string"),
                inlineSimple = null,
                use = a.use,
                default = a.default,
                fixed = a.fixed,
                doc = a.doc,
                line = a.line,
                path = a.path,
            )
        val target =
            context.docsByNamespace[qname.namespace]?.attributes?.firstOrNull {
                it.name == qname.local
            }
        if (target == null) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    whereCollision,
                    "attribute '${qname.local}' cannot be resolved",
                    a.line,
                )
            return null
        }
        return target.copy(
            use = a.use,
            default = a.default ?: target.default,
            fixed = a.fixed ?: target.fixed,
            doc = a.doc ?: target.doc,
        )
    }

    private data class Resolved(val type: UnitType, val nullable: Boolean, val default: String?)

    /**
     * The field for [el0], an element or a reference to one. What is reported about it points into
     * the file that declares it, which a group or an include can make other than the type's own.
     */
    internal fun field(
        el0: XElement,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitField? {
        val resolved =
            context.at(el0.path) { resolveElementRef(el0, whereCollision) } ?: return null
        return context.at(resolved.path) {
            fieldOf(el0, resolved, claimed, whereCollision, nested, siblings, visited)
        }
    }

    private fun fieldOf(
        el0: XElement,
        resolved: XElement,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitField? {
        val el = context.choiceLowering.withHeadType(resolved)
        val original = el.name ?: return null
        val where = "element '$original'"
        if (el.maxOccurs == 0) {
            context.diagnostics +=
                context.lossy(ImportCodes.DROPPED, where, "maxOccurs 0 dropped", el.line)
            return null
        }
        // A global element's constraints are reported once, where it is declared.
        if (el0.ref == null) context.reportIdentityConstraints(el, where)
        // An abstract head of a substitution group is not dropped: it is a union of its
        // members.
        val elementQName = el0.ref ?: QName(context.doc.targetNamespace, original)
        val memberless = context.heads.elements[elementQName]?.members.isNullOrEmpty()
        // A typeless abstract element nothing substitutes for is an augmentation point: it
        // has no content to carry, so the reference to it is dropped.
        if (
            el.abstract &&
                el.type == null &&
                el.inlineComplex == null &&
                el.inlineSimple == null &&
                memberless
        ) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.DROPPED,
                    where,
                    "abstract element '$original' has no type and no substituting element; " +
                        "dropped",
                    el.line,
                )
            return null
        }
        if (el.abstract && memberless) {
            context.diagnostics +=
                context.lossy(ImportCodes.DROPPED, where, "abstract dropped", el.line)
        }
        val headType = el0.ref?.let(context.choiceLowering::elementHeadType)
        el.form?.let { checkForm(it, elementForm(formDoc()), where, el.line) }
        // An element typed xs:anyType, or not typed at all, holds any content: a string the
        // xsd target writes back as xs:anyType.
        val anyType =
            headType == null &&
                (el.type == QName(ImportTypes.XS, "anyType") ||
                    (el.type == null && el.inlineComplex == null && el.inlineSimple == null))

        val globalModel =
            el0.ref?.takeIf { resolved.type == null && resolved.inlineComplex != null }
        val recursiveBase = if (globalModel == null) recursiveBaseOf(el, visited) else null

        val resolved: Resolved? =
            when {
                globalModel != null && headType == null -> modelResolved(modelRef(globalModel), el)
                recursiveBase != null && headType == null -> {
                    noteRecursion(recursiveBase, whereCollision, el.line)
                    modelResolved(recursiveRef(recursiveBase), el)
                }
                headType != null ->
                    if (el.maxOccurs != 1) {
                        Resolved(
                            UnitType.ListOf(
                                headType,
                                el.nillable,
                                listRefinements(el.minOccurs, el.maxOccurs),
                            ),
                            false,
                            null,
                        )
                    } else Resolved(headType, el.minOccurs == 0 || el.nillable, null)
                el.maxOccurs == 1 && el.type == null && el.inlineComplex != null -> {
                    (el.fixed ?: el.default)?.let { noLiteral(it, where, el.line) }
                    if (
                        (el.inlineComplex.content as? XContent.Choice)?.let {
                            context.choiceLowering.isUnionType(el.inlineComplex)
                        } == true
                    ) {
                        val ref =
                            inlineDeclaration(el.inlineComplex, original, nested, siblings, visited)
                        Resolved(ref, el.minOccurs == 0, null)
                    } else {
                        when (val m = mapWrapper(el, where, nested)) {
                            is MapResult.AsMap -> Resolved(m.type, el.minOccurs == 0, null)
                            is MapResult.Fallback -> {
                                val wrapperName = ImportNames.upperCamel(original)
                                val entry =
                                    UnitRecord(
                                        "Entry",
                                        m.entryFields,
                                        emptyList(),
                                        null,
                                        emptyList(),
                                    )
                                val listField =
                                    UnitField(
                                        "entry",
                                        UnitType.ListOf(
                                            UnitType.Ref("Entry"),
                                            m.nullableElement,
                                            m.listRefinements,
                                        ),
                                        false,
                                        null,
                                        m.entryDoc,
                                        emptyList(),
                                    )
                                nested +=
                                    UnitRecord(
                                        wrapperName,
                                        listOf(listField),
                                        listOf(entry),
                                        null,
                                        emptyList(),
                                    )
                                Resolved(UnitType.Ref(wrapperName), el.minOccurs == 0, null)
                            }
                            MapResult.NotAMap -> {
                                val ref =
                                    inlineDeclaration(
                                        el.inlineComplex,
                                        original,
                                        nested,
                                        siblings,
                                        visited,
                                    )
                                Resolved(ref, el.minOccurs == 0, null)
                            }
                        }
                    }
                }
                el.maxOccurs != 1 -> {
                    // resolveParticleType sees el's own maxOccurs != 1 and wraps the per-
                    // occurrence type in the ListOf itself, recursing through an `item` wrapper
                    // for a nested list or map (`list<list<T>>`, `list<map<K, V>>`, …).
                    // A repeated element of an anonymous complex type that is neither the
                    // `item` nor the map wrapper the xsd target writes is a list of the record
                    // (or union) nested under the element's name, as a single one would be.
                    val type =
                        if (anyType)
                            UnitType.ListOf(
                                UnitType.Scalar("string", emptyList()),
                                el.nillable,
                                listRefinements(el.minOccurs, el.maxOccurs),
                            )
                        else
                            resolveParticleType(el, where, nested)
                                ?: el.inlineComplex
                                    ?.takeIf { el.type == null }
                                    ?.let { ic ->
                                        UnitType.ListOf(
                                            inlineDeclaration(
                                                ic,
                                                original,
                                                nested,
                                                siblings,
                                                visited,
                                            ),
                                            el.nillable,
                                            listRefinements(el.minOccurs, el.maxOccurs),
                                        )
                                    }
                    if (type == null) {
                        context.diagnostics +=
                            context.lossy(
                                ImportCodes.UNRESOLVED,
                                where,
                                "type '${el.type?.local}' cannot be resolved",
                                el.line,
                            )
                        null
                    } else {
                        if (el.default != null) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.APPROXIMATED,
                                    where,
                                    "default on repeated element '$original' dropped",
                                    el.line,
                                )
                        }
                        if (el.fixed != null) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.DROPPED,
                                    where,
                                    "fixed value dropped",
                                    el.line,
                                )
                        }
                        if (
                            !anyType &&
                                (type as? UnitType.ListOf)?.element is UnitType.ListOf &&
                                isListValued(el)
                        ) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.APPROXIMATED,
                                    where,
                                    "repeated element of a list simple type imported as a " +
                                        "list of lists",
                                    el.line,
                                )
                        }
                        Resolved(type, false, null)
                    }
                }
                else -> {
                    val type =
                        if (anyType) UnitType.Scalar("string", emptyList())
                        else
                            context.simpleTypes.resolveElementScalarOrRef(
                                el,
                                original,
                                where,
                                nested,
                            )
                    if (type == null) {
                        context.diagnostics +=
                            context.lossy(
                                ImportCodes.UNRESOLVED,
                                where,
                                "type '${el.type?.local}' cannot be resolved",
                                el.line,
                            )
                        null
                    } else {
                        val rawDefault = el.fixed ?: el.default
                        if (el.fixed != null) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.DROPPED,
                                    where,
                                    "fixed value imported as a default",
                                    el.line,
                                )
                        }
                        val default =
                            rawDefault?.let {
                                defaultLiteralFor(
                                    type,
                                    it,
                                    el.type,
                                    el.inlineSimple,
                                    where,
                                    el.line,
                                )
                            }
                        val nullable = (el.minOccurs == 0 || el.nillable) && default == null
                        Resolved(type, nullable, default)
                    }
                }
            }
        if (resolved == null) return null
        val claim =
            context.nameAndClaim(
                original,
                ClaimKind.ELEMENT,
                where,
                claimed,
                whereCollision,
                el.line,
            ) ?: return null
        val (name, nameAnnotations) = claim
        val annotations =
            when {
                anyType -> nameAnnotations + UnitAnnotation("xsd", "any_type", null)
                el.maxOccurs == 1 && isListValued(el) ->
                    nameAnnotations + listOfNotNull(listAnnotation(resolved.type))
                else -> nameAnnotations
            }
        return UnitField(
            name,
            resolved.type,
            resolved.nullable,
            resolved.default,
            el.doc,
            annotations,
        )
    }

    /**
     * A field of [el] that refers to a record by [ref]: a list when [el] repeats, optional when it
     * may be absent.
     */
    private fun modelResolved(ref: UnitType.Ref, el: XElement): Resolved =
        if (el.maxOccurs != 1) {
            Resolved(
                UnitType.ListOf(ref, el.nillable, listRefinements(el.minOccurs, el.maxOccurs)),
                false,
                null,
            )
        } else Resolved(ref, el.minOccurs == 0, null)

    /**
     * The reference to the record of the global element [target], which has an anonymous type: the
     * type is declared once, at the top level, so every reference to the element names that record
     * instead of declaring the type again, which would never end for an element that contains
     * itself. Qualified, and imported, when it is in another namespace.
     */
    private fun modelRef(target: QName): UnitType.Ref {
        val doc = context.docsByNamespace[target.namespace] ?: context.doc
        val namespace =
            if (doc === context.doc) null
            else context.namespaceNames.getValue(doc).also { context.extraImports += it }
        // The record's name is settled once its namespace's elements have claimed theirs.
        return context.elementRecords.reference(QName(doc.targetNamespace, target.local), namespace)
    }

    /**
     * The base of [el]'s anonymous type when that type extends a type already being expanded
     * ([visited]): lowering it inline would expand that type again inside itself, for ever.
     */
    private fun recursiveBaseOf(el: XElement, visited: Set<QName>): QName? {
        if (el.type != null) return null
        val extension = el.inlineComplex?.content as? XContent.Extension ?: return null
        if (extension.simple || extension.base !in context.typeNames) return null
        return extension.base.takeIf { chainReaches(it, visited) }
    }

    /**
     * Whether [base], or a type it extends further up, is in [visited]: expanding it would expand
     * one of those again, even though no chain is cyclic.
     */
    private fun chainReaches(base: QName, visited: Set<QName>): Boolean {
        val seen = mutableSetOf<QName>()
        var current = base
        while (seen.add(current)) {
            if (current in visited) return true
            val ct =
                context.docsByNamespace[current.namespace]?.complexTypes?.firstOrNull {
                    it.name == current.local
                } ?: return false
            val extension = ct.content as? XContent.Extension ?: return false
            if (extension.simple) return false
            current = extension.base
        }
        return false
    }

    /**
     * A reference to the record of the complex type [base], qualified when it is in another
     * namespace.
     */
    private fun recursiveRef(base: QName): UnitType.Ref =
        context.choiceLowering.headRef(
            context.docsByNamespace[base.namespace] ?: context.doc,
            context.typeNames.getValue(base).finalName,
        )

    private fun noteRecursion(base: QName, where: String, line: Int) {
        if (!context.recursionNoted.add(context.sourcePath to line)) return
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                where,
                "recursive content model; '${context.typeNames.getValue(base).finalName}' " +
                    "referenced by name",
                line,
            )
    }

    /**
     * Whether [el]'s own type is a simple type that may be a list: an inline simple type, or a
     * named one other than a builtin.
     */
    private fun isListValued(el: XElement): Boolean {
        if (el.inlineSimple != null) return true
        val type = el.type ?: return false
        if (type.namespace == ImportTypes.XS) {
            return ImportTypes.builtin(type.local)?.type is UnitType.ListOf
        }
        return context.docsByNamespace[type.namespace]?.simpleTypes?.any {
            it.name == type.local
        } == true
    }

    /**
     * The document whose form defaults govern what is being lowered: the one [sourcePath] names, or
     * [doc] for a component an include brought in.
     */
    private fun formDoc(): XsdDoc =
        context.docsByNamespace.values.firstOrNull { it.path == context.sourcePath } ?: context.doc

    /**
     * A local element's or attribute's own `form`, which Schemata cannot say per field: noted when
     * it differs from [default], the document's.
     */
    private fun checkForm(form: String, default: String, where: String, line: Int) {
        if (form != default) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "form '$form' differs from the schema default; dropped",
                    line,
                )
        }
    }

    /** The field for [a0], an attribute or a reference to one; see [field] for where it points. */
    private fun attribute(
        a0: XAttribute,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        nested: MutableList<UnitDecl>,
    ): UnitField? {
        // A prohibited attribute is one a restriction removes from its base.
        if (a0.use == "prohibited") return null
        val a = context.at(a0.path) { resolveAttributeRef(a0, whereCollision) } ?: return null
        return context.at(a.path) { attributeOf(a, claimed, whereCollision, nested) }
    }

    private fun attributeOf(
        a: XAttribute,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        nested: MutableList<UnitDecl>,
    ): UnitField? {
        val original = a.name ?: return null
        val where = "attribute '$original'"
        a.form?.let { checkForm(it, attributeForm(formDoc()), where, a.line) }
        val type = context.simpleTypes.resolveAttributeType(a, where, nested)
        if (type == null) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    where,
                    "type '${a.type?.local}' cannot be resolved",
                    a.line,
                )
            return null
        }
        val rawDefault = a.fixed ?: a.default
        if (a.fixed != null) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.DROPPED,
                    where,
                    "fixed value imported as a default",
                    a.line,
                )
        }
        val default =
            rawDefault?.let { defaultLiteralFor(type, it, a.type, a.inlineSimple, where, a.line) }
        val nullable = a.use != "required" && default == null
        val claim =
            context.nameAndClaim(
                original,
                ClaimKind.ATTRIBUTE,
                where,
                claimed,
                whereCollision,
                a.line,
            ) ?: return null
        val (name, nameAnnotations) = claim
        val annotations =
            nameAnnotations +
                listOfNotNull(listAnnotation(type)) +
                UnitAnnotation("xsd", "attribute", null)
        return UnitField(name, type, nullable, default, a.doc, annotations)
    }

    /**
     * [raw] as the default literal for a field of [type]: a scalar's own literal; for an enum,
     * named by [sourceType] or declared inline as [inlineSimple], the imported value's name, looked
     * up by the XSD enumeration text it was declared with. `null`, reported, when no Schemata
     * literal can stand for it (a scalar with no literal form, a complex type, a list, or a value
     * the enum does not have).
     */
    private fun defaultLiteralFor(
        type: UnitType,
        raw: String,
        sourceType: QName?,
        inlineSimple: XSimpleType?,
        where: String,
        line: Int,
    ): String? {
        val literal =
            when (type) {
                is UnitType.Scalar -> ImportTypes.defaultLiteral(type.builtin, raw)
                is UnitType.Ref ->
                    when {
                        inlineSimple != null && context.isEnum(inlineSimple) ->
                            context.simpleTypes.enumValueName(inlineSimple, raw)
                        sourceType != null -> {
                            val named = context.simpleTypes.namedEnum(sourceType)
                            if (named != null) return enumDefault(named, raw, where, line)
                            null
                        }
                        else -> null
                    }
                is UnitType.ListOf,
                is UnitType.MapOf -> null
            }
        return literal ?: noLiteral(raw, where, line)
    }

    private fun noLiteral(raw: String, where: String, line: Int): String? {
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                where,
                "default '$raw' has no Schemata literal; dropped",
                line,
            )
        return null
    }

    /**
     * The imported name of the named enum's value [raw]: `null` with [ImportCodes.APPROXIMATED] at
     * [where] when [raw] matches none of its values.
     */
    private fun enumDefault(
        named: Pair<XsdDoc, XSimpleType>,
        raw: String,
        where: String,
        line: Int,
    ): String? {
        val (targetDoc, st) = named
        context.simpleTypes.enumValueName(st, raw)?.let {
            return it
        }
        val enumName =
            st.name?.let { context.typeNames[QName(targetDoc.targetNamespace, it)]?.finalName }
                ?: st.name
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                where,
                "default '$raw' is not a value of enum '$enumName'; dropped",
                line,
            )
        return null
    }

    /**
     * An element's own type, treating any `maxOccurs != 1` on it as one level of `list<…>` (the
     * most common case: a plain field, where `el` is the field's own element). Recurses through an
     * anonymous complex type matching the `item`-wrapper shape the XSD target writes for a
     * collection nested inside another (`list<list<T>>`, `list<map<K, V>>`); falls through to the
     * `entry`/`@key` map-wrapper shape otherwise (`map<K, list<V>>`, `map<K, map<K2, V2>>`).
     */
    private fun resolveParticleType(
        el: XElement,
        where: String,
        nested: MutableList<UnitDecl>,
    ): UnitType? {
        if (el.maxOccurs != 1) {
            val itemType =
                resolveParticleType(el.copy(minOccurs = 1, maxOccurs = 1), where, nested)
                    ?: return null
            return UnitType.ListOf(
                itemType,
                el.nillable,
                listRefinements(el.minOccurs, el.maxOccurs),
            )
        }
        if (el.type != null || el.inlineSimple != null)
            return context.simpleTypes.resolveElementItemType(el, where, nested)
        val ic = el.inlineComplex ?: return context.simpleTypes.implicitAnyType(where, el.line)
        singleItemElement(ic)?.let {
            return resolveParticleType(it, "element 'item'", nested)
        }
        val shape = recognizeMapShape(el, nested) ?: return null
        val hasUnique = el.uniques.any(::isMapUnique)
        // No missing-xs:unique fallback at this depth (only the top-level field gets the
        // Counts/Entry nested-record treatment); an unverifiable map nested this deep is simply
        // unresolved.
        if (!hasUnique) return null
        val keyType =
            context.simpleTypes
                .resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested)
                ?.takeIf { it !is UnitType.ListOf } ?: return null
        val refinements = listRefinements(shape.entry.minOccurs, shape.entry.maxOccurs)
        return UnitType.MapOf(keyType, shape.valueType, shape.entry.nillable, refinements)
    }

    /**
     * The lone `item` particle of an otherwise-empty `item`-wrapper complex type: a sequence of
     * exactly one element named `item`, no attributes, that is itself a collection. `null` when
     * [ct] doesn't match (so the caller can try the `entry`/`@key` map-wrapper shape instead): a
     * record that happens to hold a single field called `item` is still a record.
     */
    private fun singleItemElement(ct: XComplexType): XElement? {
        if (ct.attributes.isNotEmpty()) return null
        val seq = ct.content as? XContent.Sequence ?: return null
        if (seq.particles.size != 1) return null
        val particle = seq.particles[0] as? XParticle.Element ?: return null
        return particle.element.takeIf { it.name == "item" && it.ref == null && isCollection(it) }
    }

    /**
     * Whether [item] is the element a nested list or map is written as: one that repeats (a list),
     * or the map wrapper's own `xs:unique` over `entry`.
     */
    private fun isCollection(item: XElement): Boolean =
        item.maxOccurs != 1 || item.uniques.any(::isMapUnique)

    /**
     * Recognises the `entry`/`@key` map wrapper shapes: a simpleContent or complexContent extension
     * for a plain value, a `value` child for a value with its own refinements, or an `item` child
     * for a value that is itself a nested list or map. `null` when the shape doesn't match at all,
     * in which case the caller falls back to an ordinary nested record.
     */
    private fun recognizeMapShape(el: XElement, nested: MutableList<UnitDecl>): EntryShape? {
        val wrapper = el.inlineComplex ?: return null
        if (wrapper.attributes.isNotEmpty()) return null
        val seq = wrapper.content as? XContent.Sequence ?: return null
        if (seq.particles.size != 1) return null
        val entryParticle = seq.particles[0] as? XParticle.Element ?: return null
        val entry = entryParticle.element
        if (entry.name != "entry" || entry.ref != null || entry.type != null) return null
        val ec = entry.inlineComplex ?: return null
        return when (val content = ec.content) {
            is XContent.Extension -> {
                if (content.particles.isNotEmpty()) return null
                val key = singleKeyAttribute(ec) ?: return null
                val valueType =
                    context.simpleTypes.resolveTypeRef(
                        content.base,
                        "element 'entry'",
                        content.line,
                    ) ?: return null
                EntryShape(key, valueType, entry)
            }
            is XContent.Sequence -> {
                if (content.particles.size != 1) return null
                val valueParticle = content.particles[0] as? XParticle.Element ?: return null
                val valueEl = valueParticle.element
                val key = singleKeyAttribute(ec) ?: return null
                val valueType =
                    when (valueEl.name) {
                        "value" ->
                            context.simpleTypes.resolveElementScalarOrRef(
                                valueEl,
                                "value",
                                "element 'value'",
                                nested,
                            )
                        "item" ->
                            valueEl.takeIf(::isCollection)?.let {
                                resolveParticleType(it, "element 'item'", nested)
                            }
                        else -> null
                    } ?: return null
                EntryShape(key, valueType, entry)
            }
            else -> null
        }
    }

    private fun singleKeyAttribute(ec: XComplexType): XAttribute? {
        val use = ec.attributes.singleOrNull() as? XAttributeUse.Attribute ?: return null
        return use.attribute.takeIf { it.name == "key" }
    }

    private fun mapWrapper(el: XElement, where: String, nested: MutableList<UnitDecl>): MapResult {
        val shape = recognizeMapShape(el, nested) ?: return MapResult.NotAMap
        val keyType =
            context.simpleTypes
                .resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested)
                ?.takeIf { it !is UnitType.ListOf } ?: return MapResult.NotAMap
        val hasUnique = el.uniques.any(::isMapUnique)
        val refinements = listRefinements(shape.entry.minOccurs, shape.entry.maxOccurs)
        if (!hasUnique) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "map wrapper without xs:unique imported as a nested model",
                    el.line,
                )
            val keyField =
                UnitField(
                    "key",
                    keyType,
                    shape.keyAttribute.use != "required",
                    null,
                    shape.keyAttribute.doc,
                    listOf(UnitAnnotation("xsd", "attribute", null)),
                )
            val valueField = UnitField("value", shape.valueType, false, null, null, emptyList())
            return MapResult.Fallback(
                listOf(valueField, keyField),
                shape.entry.doc,
                refinements,
                shape.entry.nillable,
            )
        }
        return MapResult.AsMap(
            UnitType.MapOf(keyType, shape.valueType, shape.entry.nillable, refinements)
        )
    }
}
