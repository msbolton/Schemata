package io.schemata.importer.xsd

/**
 * A head's concrete member types, in document order, and where the head itself is declared.
 * [dropped] are the concrete elements of a substitution group, the head itself among them, whose
 * named type is not a complex type of the set (a simple type, or one that cannot be resolved), left
 * out of [members].
 */
internal data class HeadMembers(
    val members: List<QName>,
    val line: Int,
    val doc: XsdDoc,
    val dropped: List<XElement> = emptyList(),
)

/**
 * Every polymorphic head in the input set and its closed set of concrete members, computed over all
 * documents before any one is lowered: an abstract complex type's members are the non-abstract
 * complex types deriving from it, however deep and in whatever document; a substitution-group head
 * element's members are the named complex types of the global elements whose substitution chain
 * reaches it, plus the head's own type when the head is concrete. An abstract member stands in for
 * its own members. A member whose type is not a named complex type of the set (an inline type, a
 * simple type, an unresolved one) is left out, a named one recorded as dropped. Order is document
 * order (input order, then line). A name declared twice (an invalid schema, but one an include can
 * produce) means its first declaration, as the lowering reads it.
 */
internal class Heads(docs: List<XsdDoc>) {
    private val complexTypesByName: Map<QName, Pair<XComplexType, XsdDoc>> =
        firstWins(
            docs.flatMap { d ->
                d.complexTypes.mapNotNull { ct ->
                    ct.name?.let { QName(d.targetNamespace, it) to (ct to d) }
                }
            }
        )
    private val elementsByName: Map<QName, Pair<XElement, XsdDoc>> =
        firstWins(
            docs.flatMap { d ->
                d.elements.mapNotNull { el ->
                    el.name?.let { QName(d.targetNamespace, it) to (el to d) }
                }
            }
        )
    private val order: Map<QName, Pair<Int, Int>> =
        firstWins(
            docs.flatMapIndexed { i, d ->
                d.complexTypes.mapNotNull { ct ->
                    ct.name?.let { QName(d.targetNamespace, it) to (i to ct.line) }
                }
            }
        )

    /** The document that declares the complex type [name]; the first one that does. */
    fun typeDoc(name: QName): XsdDoc = complexTypesByName.getValue(name).second

    /**
     * Abstract complex types with at least one concrete descendant that some element names as its
     * type, by qualified name. A type nothing names is never a field's or an element's type, so a
     * union of its descendants would be one nothing can use.
     */
    val types: Map<QName, HeadMembers>

    /** The abstract complex types with concrete descendants that no element names: no union. */
    val unreferenced: Set<QName>

    /**
     * Substitution-group heads with at least one member element, by the head element's qualified
     * name; the members are the member elements' types.
     */
    val elements: Map<QName, HeadMembers>

    /** Each head's member elements, every one whose substitution chain reaches it. */
    val memberElements: Map<QName, List<XElement>>

    init {
        val derived = mutableMapOf<QName, MutableList<QName>>()
        complexTypesByName.forEach { (name, pair) ->
            val base =
                when (val content = pair.first.content) {
                    is XContent.Extension -> content.base.takeIf { !content.simple }
                    is XContent.Restriction -> content.base.takeIf { !content.simple }
                    else -> null
                }
            if (base != null) derived.getOrPut(base) { mutableListOf() } += name
        }
        fun concrete(type: QName, seen: MutableSet<QName>): List<QName> {
            if (!seen.add(type)) return emptyList()
            return derived[type].orEmpty().flatMap { d ->
                val ct = complexTypesByName[d]?.first ?: return@flatMap emptyList()
                if (ct.abstract) concrete(d, seen) else listOf(d) + concrete(d, seen)
            }
        }
        val referenced = elementTypes(docs)
        val withMembers =
            complexTypesByName
                .filter { it.value.first.abstract }
                .mapNotNull { (name, pair) ->
                    val members = sorted(concrete(name, mutableSetOf()).distinct())
                    if (members.isEmpty()) null
                    else name to HeadMembers(members, pair.first.line, pair.second)
                }
                .toMap()
        types = withMembers.filterKeys { it in referenced }
        unreferenced = withMembers.keys - types.keys

        // Keyed by qualified name, not by the element: two elements that read alike are still two.
        val substitutes = mutableMapOf<QName, MutableList<Pair<QName, XElement>>>()
        elementsByName.forEach { (name, pair) ->
            pair.first.substitutionGroup?.let {
                substitutes.getOrPut(it) { mutableListOf() } += name to pair.first
            }
        }
        fun chain(head: QName, seen: MutableSet<QName>): List<XElement> =
            substitutes[head].orEmpty().flatMap { (name, el) ->
                if (!seen.add(name)) emptyList() else listOf(el) + chain(name, seen)
            }
        val memberElementsByHead = mutableMapOf<QName, List<XElement>>()
        elements =
            elementsByName
                .mapNotNull { (name, pair) ->
                    val chainElements = chain(name, mutableSetOf(name))
                    if (chainElements.isEmpty()) return@mapNotNull null
                    memberElementsByHead[name] = chainElements
                    val head = pair.first
                    val own = if (head.abstract) emptyList() else listOfNotNull(head.type)
                    val concreteElements = chainElements.filter { !it.abstract }
                    val raw = own + concreteElements.mapNotNull { it.type }
                    val expanded = raw.flatMap { t -> types[t]?.members ?: listOf(t) }
                    val members = expanded.distinct().filter { it in complexTypesByName }
                    val dropped =
                        (listOfNotNull(head.takeIf { !it.abstract }) + concreteElements).filter { el
                            ->
                            el.type.let { it != null && it !in complexTypesByName }
                        }
                    name to HeadMembers(sorted(members), head.line, pair.second, dropped)
                }
                .toMap()
        memberElements = memberElementsByHead
    }

    private fun sorted(names: List<QName>): List<QName> =
        names.sortedWith(
            compareBy({ order[it]?.first ?: Int.MAX_VALUE }, { order[it]?.second ?: Int.MAX_VALUE })
        )

    private companion object {
        /** [entries] as a map in which a key's first entry stays. */
        fun <K, V> firstWins(entries: List<Pair<K, V>>): Map<K, V> {
            val result = LinkedHashMap<K, V>()
            entries.forEach { (k, v) -> result.putIfAbsent(k, v) }
            return result
        }
    }
}

/** Every `type` any element declares, anywhere in [docs]: a derivation's base is not one. */
internal fun elementTypes(docs: List<XsdDoc>): Set<QName> =
    allElements(docs).mapNotNullTo(linkedSetOf()) { it.type }

/** Every `ref` any element makes, anywhere in [docs]. */
internal fun elementRefs(docs: List<XsdDoc>): Set<QName> =
    allElements(docs).mapNotNullTo(linkedSetOf()) { it.ref }

/** Every element in [docs]: global ones, and those in types and groups, inline types included. */
private fun allElements(docs: List<XsdDoc>): List<XElement> {
    val result = mutableListOf<XElement>()
    docs.forEach { d ->
        d.elements.forEach { collect(it, result) }
        d.complexTypes.forEach { collect(it.content, result) }
        d.groups.forEach { collect(it.content, result) }
    }
    return result
}

private fun collect(el: XElement, into: MutableList<XElement>) {
    into += el
    el.inlineComplex?.let { collect(it.content, into) }
}

private fun collect(content: XContent, into: MutableList<XElement>) {
    val particles =
        when (content) {
            is XContent.Sequence -> content.particles
            is XContent.Choice -> content.particles
            is XContent.All -> content.particles
            is XContent.Extension -> content.particles
            is XContent.Restriction -> content.particles
            XContent.Empty -> emptyList()
        }
    particles.forEach { p ->
        when (p) {
            is XParticle.Element -> collect(p.element, into)
            is XParticle.Nested -> collect(p.content, into)
            is XParticle.Any,
            is XParticle.GroupRef -> Unit
        }
    }
}
