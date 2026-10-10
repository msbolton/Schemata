package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnionMember
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.importer.xsd.XsdImport.Claim
import io.schemata.importer.xsd.XsdImport.ClaimKind
import io.schemata.importer.xsd.XsdImport.dropped
import io.schemata.importer.xsd.XsdImport.listRefinements
import io.schemata.target.Names

/**
 * Lowers choices and substitution heads to unions, with the records and enums hoisted out for their
 * members.
 */
internal class ChoiceLowering(private val context: ImportContext) {
    /** How many inline choices each record has had so far, which numbers the next one. */
    private val choiceCounts = mutableMapOf<String, Int>()

    /**
     * An abstract complex type with concrete descendants: nothing when it has exactly one, which
     * every use of it names instead; otherwise a union of them, under the type's own name, which
     * also serves any substitution group headed by an element of this type with the same members.
     */
    internal fun typeHeadDeclaration(
        ct: XComplexType,
        original: String,
        info: TypeNameInfo,
        head: HeadMembers,
    ): List<UnitDecl> {
        val where = "complex type '$original'"
        val n = head.members.size
        if (n == 1) {
            val only = context.typeNames.getValue(head.members[0]).finalName
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "abstract type '$original' imported as its one concrete type '$only'",
                    ct.line,
                )
            return emptyList()
        }
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                where,
                "abstract type '$original' imported as union '${info.finalName}' of $n " +
                    "concrete types; the regenerated XSD uses a choice",
                ct.line,
            )
        val memberElements =
            context.heads.memberElements
                .filterKeys {
                    headElement(it)?.type == QName(context.doc.targetNamespace, original) &&
                        sharesTypeUnion(it)
                }
                .values
                .flatten()
        val union = headUnion(info.finalName, head, where, memberElements, ct.doc)
        return listOf(union.copy(annotations = listOfNotNull(info.annotation)))
    }

    /** The global element [qname] names, in whichever document declares it. */
    private fun headElement(qname: QName): XElement? =
        context.docsByNamespace[qname.namespace]?.elements?.firstOrNull { it.name == qname.local }

    /**
     * Whether the substitution group headed by [head] is served by its type's own union: the head
     * element's type is an abstract type of the same namespace whose union has exactly the group's
     * members.
     */
    private fun sharesTypeUnion(head: QName): Boolean {
        val members = context.heads.elements[head]?.members ?: return false
        val type = headElement(head)?.type ?: return false
        val typeHead = context.heads.types[type] ?: return false
        return type.namespace == head.namespace && members.size >= 2 && typeHead.members == members
    }

    /**
     * The union a substitution group headed by [head] lowers to, unqualified, in the head's own
     * namespace: its type's union when [sharesTypeUnion]; otherwise the head's own name, or that
     * name suffixed `Choice` when a type or a global element's record of that namespace already
     * lowers to it; `null` when that name is taken too, so the head has no union at all (see
     * [headUnions], which reports it). Decided from the namespace's type names alone, never from
     * what has been claimed so far, so that every use site and the declaration agree whatever order
     * they are lowered in.
     */
    private fun elementUnionName(head: QName): String? {
        if (sharesTypeUnion(head)) {
            return context.typeNames.getValue(headElement(head)!!.type!!).finalName
        }
        val plain = ImportNames.upperCamel(head.local)
        fun taken(name: String): Boolean =
            context.typeNames.any { (q, info) ->
                q.namespace == head.namespace && info.finalName == name
            } ||
                context.docsByNamespace[head.namespace]?.elements.orEmpty().any {
                    it.ref == null &&
                        it.type == null &&
                        it.inlineComplex != null &&
                        it.name?.let(ImportNames::upperCamel) == name
                }
        return when {
            !taken(plain) -> plain
            !taken("${plain}Choice") -> "${plain}Choice"
            else -> null
        }
    }

    /**
     * A reference to the type [name] declared in [targetDoc], qualified (and imported) when that is
     * another namespace.
     */
    internal fun headRef(targetDoc: XsdDoc, name: String): UnitType.Ref {
        if (targetDoc === context.doc) return UnitType.Ref(name)
        val namespace = context.namespaceNames.getValue(targetDoc)
        context.extraImports += namespace
        return UnitType.Ref("$namespace.$name")
    }

    /** What a reference to [member], one head's only member, lowers to. */
    private fun memberRef(member: QName): UnitType.Ref =
        headRef(context.heads.typeDoc(member), context.typeNames.getValue(member).finalName)

    /**
     * What a use of the abstract complex type [qname] lowers to: its one concrete type, or its
     * union; `null` when [qname] is not an abstract type with concrete descendants.
     */
    internal fun headType(qname: QName): UnitType? {
        val head = context.heads.types[qname] ?: return null
        if (head.members.size == 1) return memberRef(head.members[0])
        return headRef(head.doc, context.typeNames.getValue(qname).finalName)
    }

    /**
     * What a reference to the substitution-group head element [ref] lowers to: its one member type,
     * or its union; `null` when [ref] heads no group with a member type.
     */
    internal fun elementHeadType(ref: QName): UnitType? {
        val head = context.heads.elements[ref]?.takeIf { it.members.isNotEmpty() } ?: return null
        if (head.members.size == 1) return memberRef(head.members[0])
        // A head whose union name is taken has no union to refer to; it stays its own element.
        return headRef(head.doc, elementUnionName(ref) ?: return null)
    }

    /**
     * The unions this namespace's substitution-group heads declare: one per head with two or more
     * member types, unless its type's own union already serves. A head with exactly one member type
     * declares nothing (every use names that type) and is noted; a head with none stays its own
     * element. A member declared with an inline type cannot be named in place of its head, nor can
     * one of a simple or unresolved type be a union member, so each is reported and left out.
     */
    fun headUnions(): List<UnitDecl> {
        val result = mutableListOf<UnitDecl>()
        // Each document declares the unions of its own heads: documents without a namespace
        // share the empty one, so the namespace alone does not say whose head this is.
        context.heads.elements
            .filter {
                it.key.namespace == context.doc.targetNamespace && it.value.doc === context.doc
            }
            .forEach { (name, head) ->
                val el =
                    context.doc.elements.firstOrNull { it.name == name.local } ?: return@forEach
                val where = "element '${name.local}'"
                val members = context.heads.memberElements[name].orEmpty()
                val n = head.members.size
                val unionName = if (n >= 2) elementUnionName(name) else null
                if (n >= 2 && unionName == null) {
                    // Every name the head could take is some other declaration's: report the
                    // clash, and leave the head as its own element (see [elementHeadType]).
                    val plain = ImportNames.upperCamel(name.local)
                    context.at(el.path) { context.claimTopLevel("${plain}Choice", where, el.line) }
                    return@forEach
                }
                when {
                    unionName != null ->
                        context.at(el.path) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.APPROXIMATED,
                                    where,
                                    "substitution group '${name.local}' imported as union " +
                                        "'$unionName' of $n member types; the regenerated " +
                                        "XSD uses a choice",
                                    el.line,
                                )
                        }
                    n == 1 -> {
                        val only = context.typeNames.getValue(head.members[0]).finalName
                        context.at(el.path) {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.APPROXIMATED,
                                    where,
                                    "substitution group '${name.local}' imported as its one " +
                                        "member type '$only'",
                                    el.line,
                                )
                        }
                    }
                }
                val droppedFrom =
                    unionName?.let { "union '$it'" } ?: "substitution group '${name.local}'"
                // The head is a member of its own group when it is concrete.
                (listOfNotNull(el.takeIf { !it.abstract }) + members).forEach { m ->
                    val what =
                        when {
                            m in head.dropped -> "of ${memberTypeKind(m.type!!)}"
                            m.type == null && (m.inlineComplex != null || m.inlineSimple != null) ->
                                "with an inline type"
                            else -> return@forEach
                        }
                    context.at(m.path) {
                        context.diagnostics +=
                            context.lossy(
                                ImportCodes.DROPPED,
                                "element '${m.name}'",
                                "substitution member $what dropped from $droppedFrom",
                                m.line,
                            )
                    }
                }
                if (unionName == null || sharesTypeUnion(name)) return@forEach
                val claimed =
                    context.at(el.path) { context.claimTopLevel(unionName, where, el.line) }
                if (claimed) result += headUnion(unionName, head, where, members, el.doc)
            }
        return result
    }

    /**
     * What a substitution member's [type], not a complex type of the inputs, is: `xs:anyType` (read
     * as a string, though it is no simple type), a simple type (an XSD builtin or a declared one)
     * or else an unresolved one.
     */
    private fun memberTypeKind(type: QName): String =
        if (type == QName(ImportTypes.XS, "anyType")) "xs:anyType"
        else if (
            type.namespace == ImportTypes.XS ||
                context.docsByNamespace[type.namespace]?.simpleTypes?.any {
                    it.name == type.local
                } == true
        )
            "simple type"
        else "unresolved type"

    /**
     * A head's union of its member types, reporting each member element whose name the regenerated
     * choice will not reproduce.
     */
    private fun headUnion(
        name: String,
        head: HeadMembers,
        where: String,
        memberElements: List<XElement>,
        unionDoc: String?,
    ): UnitUnion {
        val members = head.members.map { UnionMember(memberRef(it)) }
        memberElements.forEach { el ->
            val t = el.type?.takeIf { it in head.members } ?: return@forEach
            val stem = memberStem(t, context.typeNames.getValue(t))
            if (el.name != stem) {
                context.at(el.path) {
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.APPROXIMATED,
                            where,
                            "member element name '${el.name}' has no Schemata equivalent and is dropped; the " +
                                "regenerated element will be named '$stem'",
                            el.line,
                        )
                }
            }
        }
        return UnitUnion(name, members, unionDoc, emptyList())
    }

    /**
     * The element name a union member of the named complex type [type] regenerates as: the type's
     * override when it has one, or the one its root element earns it (see [declarationAt]);
     * otherwise its snake_case name.
     */
    private fun memberStem(type: QName, info: TypeNameInfo): String {
        val overrideText = type.local.removeSuffix("Type")
        if (info.annotation != null) return overrideText
        val root =
            context.docsByNamespace[type.namespace]?.elements?.firstOrNull {
                it.ref == null && it.type == type
            }
        return if (root?.name == overrideText) overrideText else Names.snakeCase(info.finalName)
    }

    /**
     * What a choice-only complex type lowering to a union carries that a union cannot: its
     * abstractness, reported as dropped. (One with attributes or mixed content is a record, see
     * [isUnionType].)
     */
    internal fun unionExtras(ct: XComplexType, where: String) {
        if (ct.abstract) {
            context.diagnostics +=
                context.lossy(ImportCodes.DROPPED, where, "abstract dropped", ct.line)
        }
    }

    /**
     * A bare `xs:choice` found directly inside a sequence, with no wrapping element: a union
     * synthesised as a top-level sibling when every member has a complex type or is itself a model
     * group, named `[recordName]Choice` (numbered past the record's first inline choice, however
     * deeply nested); otherwise every member is flattened to an optional field of the enclosing
     * record.
     */
    internal fun inlineChoiceFields(
        recordName: String,
        choice: XContent.Choice,
        particle: XParticle.Nested,
        whereCollision: String,
        claimed: MutableMap<String, Claim>,
        nested: MutableList<UnitDecl>,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): List<UnitField> {
        val branches = context.complexLowering.expandParticles(choice.particles, whereCollision)
        val onlyWildcards =
            if (branches.isEmpty()) choice.particles.all { it is XParticle.Any }
            else branches.all { it is XParticle.Any }
        if (onlyWildcards) {
            return wildcardChoiceFields(
                branches.filterIsInstance<XParticle.Any>(),
                particle.minOccurs,
                particle.maxOccurs,
                claimed,
                whereCollision,
            )
        }
        val index = choiceCounts.merge(recordName, 1, Int::plus)!!
        val members = branches.filter { it is XParticle.Element || it is XParticle.Nested }
        val allComplex =
            members.isNotEmpty() &&
                members.all { p ->
                    val el = (p as? XParticle.Element)?.element ?: return@all true
                    el.inlineComplex != null ||
                        (el.type != null && context.complexLowering.isComplexTypeRef(el.type))
                }
        val fieldName = if (index == 1) "choice" else "choice_$index"
        if (allComplex) {
            val unionName = if (index == 1) "${recordName}Choice" else "${recordName}Choice$index"
            val claim =
                context.nameAndClaim(
                    fieldName,
                    ClaimKind.CHOICE,
                    whereCollision,
                    claimed,
                    whereCollision,
                    particle.line,
                ) ?: return emptyList()
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "inline choice has no Schemata equivalent; imported as union '$unionName' " +
                        "in field '$fieldName'",
                    particle.line,
                )
            val union =
                unionFromChoice(
                    choice,
                    unionName,
                    "union '$unionName'",
                    null,
                    siblings,
                    false,
                    visited = visited,
                )
            siblings += union
            val (name, annotations) = claim
            val type: UnitType =
                if (particle.maxOccurs != 1) {
                    UnitType.ListOf(
                        UnitType.Ref(unionName),
                        false,
                        listRefinements(particle.minOccurs, particle.maxOccurs),
                    )
                } else UnitType.Ref(unionName)
            val nullable = particle.maxOccurs == 1 && particle.minOccurs == 0
            return listOf(UnitField(name, type, nullable, null, null, annotations))
        }
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                whereCollision,
                "inline choice has no Schemata equivalent; members imported as optional fields",
                particle.line,
            )
        // Each branch, a wildcard among them, is an optional field; a repeated choice repeats
        // every branch, so a branch's occurrence is multiplied by the choice's and a branch
        // that may repeat is a list.
        return branches.flatMap {
            when (it) {
                is XParticle.Element ->
                    listOfNotNull(
                        context.complexLowering.field(
                            it.element.copy(
                                minOccurs = 0,
                                maxOccurs =
                                    context.complexLowering.times(
                                        it.element.maxOccurs,
                                        particle.maxOccurs,
                                    ),
                            ),
                            claimed,
                            whereCollision,
                            nested,
                            siblings,
                            visited,
                        )
                    )
                is XParticle.Any ->
                    listOfNotNull(
                        context.complexLowering.anyField(
                            it.copy(
                                minOccurs = 0,
                                maxOccurs =
                                    context.complexLowering.times(it.maxOccurs, particle.maxOccurs),
                            ),
                            claimed,
                            whereCollision,
                        )
                    )
                is XParticle.Nested ->
                    context.complexLowering.sequenceFields(
                        recordName,
                        listOf(
                            it.copy(
                                minOccurs = 0,
                                maxOccurs =
                                    context.complexLowering.times(it.maxOccurs, particle.maxOccurs),
                            )
                        ),
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                        visited,
                    )
                is XParticle.GroupRef -> emptyList() // only an unresolved ref survives expansion
            }
        }
    }

    /**
     * Whether [ct], whose content is a bare choice, lowers to a union: only when the choice occurs
     * once, has a branch that can be a member (a union member carries no `@xsd(any)`; a group
     * reference counts as the branches it expands to), and the type has no attributes, attribute
     * wildcard, or mixed content for a union to lose. Otherwise the type is a record holding the
     * choice as a sequence would.
     */
    internal fun isUnionType(ct: XComplexType): Boolean {
        val choice = ct.content as? XContent.Choice ?: return false
        return choice.maxOccurs == 1 &&
            ct.attributes.isEmpty() &&
            !ct.mixed &&
            // Only the answer is wanted here: whatever the expansion reports is reported
            // again, once, where the branches are lowered.
            context
                .quietly { context.complexLowering.expandParticles(choice.particles, "") }
                .any { it !is XParticle.Any }
    }

    /**
     * The fields of a choice of nothing but wildcards occurring [minOccurs] to [maxOccurs] times:
     * one `@xsd(any)` field per wildcard, its occurrence the wildcard's times the choice's, as the
     * sequence it amounts to.
     */
    private fun wildcardChoiceFields(
        wildcards: List<XParticle.Any>,
        minOccurs: Int,
        maxOccurs: Int?,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
    ): List<UnitField> =
        wildcards.mapNotNull { any ->
            context.complexLowering.anyField(
                any.copy(
                    minOccurs = any.minOccurs * minOccurs,
                    maxOccurs = context.complexLowering.times(any.maxOccurs, maxOccurs),
                ),
                claimed,
                whereCollision,
            )
        }

    /** A choice's branch on its way to a union member. */
    private sealed interface ChoiceEntry

    /** An element branch: the element, the type it lowers to, and its stem. */
    private class Branch(val el: XElement, val name: String, val type: UnitType, val stem: String) :
        ChoiceEntry

    /** A branch whose member is already settled, such as a sequence branch's record. */
    private class Settled(val member: UnionMember) : ChoiceEntry

    /**
     * A union from [choice]'s members, in order. [checkMismatch] reports a member element whose
     * name doesn't match what the XSD target would regenerate (SCH2403); turned off for a
     * synthesised, never-named inline choice, where there is nothing for a member name to
     * round-trip against. Two or more element branches lowering to one member (the same type)
     * cannot be told apart by type, so each of them is wrapped in a record of its own (see
     * [sharedBranchRecord]) and the union lists those. A choice none of whose branches lowers to a
     * member, each already reported, is an empty record under the same name, as a union must have a
     * member.
     */
    internal fun unionFromChoice(
        choice: XContent.Choice,
        name: String,
        unionWhere: String,
        unionDoc: String?,
        siblings: MutableList<UnitDecl>,
        checkMismatch: Boolean,
        annotations: List<UnitAnnotation> = emptyList(),
        visited: Set<QName> = emptySet(),
    ): UnitDecl {
        val entries = mutableListOf<ChoiceEntry>()
        fun member(particle: XParticle) {
            when (particle) {
                is XParticle.Element -> {
                    val el =
                        withHeadType(
                            if (particle.element.ref != null) {
                                context.complexLowering.resolveElementRef(
                                    particle.element,
                                    unionWhere,
                                ) ?: return
                            } else particle.element
                        )
                    val elementName = el.name ?: "member"
                    val (ownType, stem) =
                        memberTypeAndStem(el, unionWhere, siblings, visited) ?: return
                    val type = particle.element.ref?.let(::elementHeadType) ?: ownType
                    entries += Branch(el, elementName, type, stem)
                }
                is XParticle.Any ->
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.DROPPED,
                            unionWhere,
                            "xs:any dropped",
                            particle.line,
                        )
                is XParticle.Nested ->
                    when (val content = particle.content) {
                        // A choice inside a choice offers its branches as the outer one's.
                        is XContent.Choice ->
                            context.complexLowering
                                .expandParticles(content.particles, unionWhere)
                                .forEach(::member)
                        is XContent.Sequence,
                        is XContent.All -> {
                            val ref =
                                branchRecord(content, particle, unionWhere, siblings, visited)
                                    ?: return
                            entries += Settled(UnionMember(ref, null))
                        }
                        else -> Unit
                    }
                is XParticle.GroupRef -> Unit // only an unresolved ref survives expansion
            }
        }
        context.complexLowering.expandParticles(choice.particles, unionWhere).forEach(::member)
        val shared =
            entries.filterIsInstance<Branch>().groupBy { it.stem }.filterValues { it.size > 1 }
        val members =
            entries.map { e ->
                if (e !is Branch) return@map (e as Settled).member
                val group = shared[e.stem]
                val (type, stem) =
                    if (group == null) e.type to e.stem
                    else {
                        val ref = sharedBranchRecord(e, group, unionWhere, siblings)
                        ref to Names.snakeCase(ref.name)
                    }
                if (checkMismatch && e.name != stem) {
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.APPROXIMATED,
                            unionWhere,
                            "member element name '${e.name}' has no Schemata equivalent and is dropped; the " +
                                "regenerated element will be named '$stem'",
                            e.el.line,
                        )
                }
                UnionMember(type, e.el.doc)
            }
        if (members.isEmpty()) {
            return UnitRecord(name, emptyList(), emptyList(), unionDoc, annotations)
        }
        return UnitUnion(name, members, unionDoc, annotations)
    }

    /**
     * [branch], one of the [group] of a choice's branches sharing a member type, as a top-level,
     * non-root record named after its element (numbered past a name already taken, as a branch
     * record is) holding the type in a `value` field, and noted.
     */
    private fun sharedBranchRecord(
        branch: Branch,
        group: List<Branch>,
        unionWhere: String,
        siblings: MutableList<UnitDecl>,
    ): UnitType.Ref {
        val base = ImportNames.upperCamel(branch.name)
        var n = 1
        while (context.complexLowering.numbered(base, n, "") in context.topLevelNames) n++
        val name = context.complexLowering.numbered(base, n, "")
        context.claimTopLevel(name, "element '${branch.name}'", branch.el.line)
        val other = if (branch === group[0]) group[1] else branch
        val typeName =
            when (val t = branch.type) {
                is UnitType.Ref -> t.name
                is UnitType.Scalar -> t.builtin
                else -> branch.stem
            }
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                unionWhere,
                "members '${group[0].name}' and '${other.name}' share type '$typeName'; each " +
                    "imported as a model holding it",
                branch.el.line,
            )
        siblings +=
            UnitRecord(
                name,
                listOf(UnitField("value", branch.type, false, null, null, emptyList())),
                emptyList(),
                null,
                listOf(UnitAnnotation("xsd", "root", "false")),
            )
        return UnitType.Ref(name)
    }

    /**
     * A choice branch that is a sequence or an `xs:all`: a top-level, non-root record named for its
     * first element (`WGroup`), referenced as the union's member. `null` when that name is already
     * a top-level declaration's, which is reported.
     */
    private fun branchRecord(
        content: XContent,
        particle: XParticle.Nested,
        unionWhere: String,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): UnitType.Ref? {
        val particles =
            when (content) {
                is XContent.Sequence -> content.particles
                is XContent.All -> content.particles
                else -> return null
            }
        val first =
            particles.firstNotNullOfOrNull {
                (it as? XParticle.Element)?.element?.let { el -> el.name ?: el.ref?.local }
            }
        // A second branch record of the same name, in this union or another, is numbered.
        val base = (first?.let(ImportNames::upperCamel) ?: "") + "Group"
        var n = 1
        while (context.complexLowering.numbered(base, n, "") in context.topLevelNames) n++
        val name = context.complexLowering.numbered(base, n, "")
        if (!context.claimTopLevel(name, "choice branch of $unionWhere", particle.line)) return null
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                unionWhere,
                "choice branch imported as model '$name'",
                particle.line,
            )
        val ct =
            XComplexType(
                name = null,
                doc = null,
                content = content,
                attributes = emptyList(),
                mixed = false,
                abstract = false,
                line = particle.line,
            )
        siblings += context.complexLowering.buildHoistedRecord(ct, name, siblings, visited)
        return UnitType.Ref(name)
    }

    /** A union member's type and its regenerated-name stem (the XSD target's own element name). */
    private fun memberTypeAndStem(
        el: XElement,
        unionWhere: String,
        siblings: MutableList<UnitDecl>,
        visited: Set<QName>,
    ): Pair<UnitType, String>? {
        if (el.type != null) {
            val qname = el.type
            if (qname.namespace == ImportTypes.XS) {
                // A builtin member is typed as a field of it would be, a bare decimal taking
                // the default precision and scale.
                val type =
                    context.simpleTypes.resolveTypeRef(qname, unionWhere, el.line)
                        as? UnitType.Scalar
                if (type == null) {
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.UNRESOLVED,
                            unionWhere,
                            "type '${qname.local}' cannot be resolved",
                            el.line,
                        )
                    return null
                }
                return type to type.builtin
            }
            val targetDoc = context.docsByNamespace[qname.namespace]
            if (targetDoc == null) {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.UNRESOLVED,
                        unionWhere,
                        "type '${qname.local}' cannot be resolved",
                        el.line,
                    )
                return null
            }
            val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
            if (ct != null) {
                val info = context.typeNames.getValue(QName(targetDoc.targetNamespace, qname.local))
                val type =
                    headType(qname)
                        ?: UnitType.Ref(
                            context.simpleTypes.qualifiedTypeName(targetDoc, qname.local)
                        )
                return type to regeneratedElementName(qname.local, info)
            }
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local }
            if (st != null && context.isEnum(st)) {
                val info = context.typeNames.getValue(QName(targetDoc.targetNamespace, qname.local))
                return UnitType.Ref(
                    context.simpleTypes.qualifiedTypeName(targetDoc, qname.local)
                ) to regeneratedElementName(qname.local, info)
            }
            if (st != null) {
                val type =
                    context.simpleTypes.resolveNamedSimpleType(
                        st,
                        targetDoc.path,
                        unionWhere,
                        setOf(qname),
                    )
                return simpleMember(type, unionWhere, el.line)
            }
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    unionWhere,
                    "type '${qname.local}' cannot be resolved",
                    el.line,
                )
            return null
        }
        if (el.inlineComplex != null) {
            val elementName = el.name ?: "member"
            val hoistedName = ImportNames.upperCamel(elementName)
            if (!context.claimTopLevel(hoistedName, "element '$elementName'", el.line)) return null
            siblings +=
                context.complexLowering.buildHoistedRecord(
                    el.inlineComplex,
                    hoistedName,
                    siblings,
                    visited,
                )
            return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
        }
        if (el.inlineSimple != null) {
            if (context.isEnum(el.inlineSimple)) {
                val elementName = el.name ?: "member"
                val hoistedName = ImportNames.upperCamel(elementName)
                if (!context.claimTopLevel(hoistedName, "element '$elementName'", el.line))
                    return null
                if (el.inlineSimple.variety is XVariety.Union) {
                    context.simpleTypes.unionNote(unionWhere, "enum '$hoistedName'", el.line)
                }
                siblings +=
                    context.simpleTypes.buildInlineEnum(el.inlineSimple, hoistedName, unionWhere)
                return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
            }
            val type =
                context.simpleTypes.resolveNamedSimpleType(
                    el.inlineSimple,
                    context.doc.path,
                    unionWhere,
                )
            return simpleMember(type, unionWhere, el.line)
        }
        // An element with no type holds anything, as one typed xs:anyType does; a union
        // member has nowhere to carry the field's `@xsd(any_type)`, so it is a plain string.
        val string = context.simpleTypes.implicitAnyType(unionWhere, el.line)
        return string to string.builtin
    }

    /**
     * A union member of a simple type and its stem: a scalar is named for its builtin; a list,
     * which a union member cannot be, is reported and imported as a string.
     */
    private fun simpleMember(
        type: UnitType,
        unionWhere: String,
        line: Int,
    ): Pair<UnitType, String> =
        when (type) {
            is UnitType.Scalar -> type to type.builtin
            is UnitType.Ref -> type to Names.snakeCase(type.name.substringAfterLast('.'))
            else -> {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.DROPPED,
                        unionWhere,
                        "list simple type imported as string",
                        line,
                    )
                UnitType.Scalar("string", emptyList()) to "string"
            }
        }

    /**
     * [original]'s regenerated element or union-member name, exactly as the XSD target writes it.
     */
    private fun regeneratedElementName(original: String, info: TypeNameInfo): String =
        if (info.annotation != null) original.removeSuffix("Type")
        else Names.snakeCase(info.finalName)

    /**
     * [el] with its substitution-group head's type when it declares none of its own, as XSD gives
     * it, following the chain of heads until one has a type.
     */
    internal fun withHeadType(el: XElement): XElement {
        var head = el.substitutionGroup
        val seen = mutableSetOf<QName>()
        while (
            el.type == null &&
                el.inlineComplex == null &&
                el.inlineSimple == null &&
                head != null &&
                seen.add(head)
        ) {
            val h = headElement(head) ?: return el
            if (h.type != null || h.inlineComplex != null || h.inlineSimple != null) {
                return el.copy(
                    type = h.type,
                    inlineComplex = h.inlineComplex,
                    inlineSimple = h.inlineSimple,
                )
            }
            head = h.substitutionGroup
        }
        return el
    }
}
