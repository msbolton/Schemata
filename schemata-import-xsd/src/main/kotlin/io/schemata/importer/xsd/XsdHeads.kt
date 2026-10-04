package io.schemata.importer.xsd

/**
 * A head's concrete member types, in document order, and where the head itself is declared.
 * [dropped] are the member elements of a substitution group whose named type is not a complex type
 * of the set (a simple type, or one that cannot be resolved), left out of [members].
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
 * order (input order, then line).
 */
internal class Heads(docs: List<XsdDoc>) {
    private val complexTypesByName: Map<QName, Pair<XComplexType, XsdDoc>> =
        docs
            .flatMap { d ->
                d.complexTypes.mapNotNull { ct ->
                    ct.name?.let { QName(d.targetNamespace, it) to (ct to d) }
                }
            }
            .toMap()
    private val elementsByName: Map<QName, Pair<XElement, XsdDoc>> =
        docs
            .flatMap { d ->
                d.elements.mapNotNull { el ->
                    el.name?.let { QName(d.targetNamespace, it) to (el to d) }
                }
            }
            .toMap()
    private val elementNames: Map<XElement, QName> =
        elementsByName.entries.associate { it.value.first to it.key }
    private val order: Map<QName, Pair<Int, Int>> =
        docs
            .flatMapIndexed { i, d ->
                d.complexTypes.mapNotNull { ct ->
                    ct.name?.let { QName(d.targetNamespace, it) to (i to ct.line) }
                }
            }
            .toMap()

    /** Abstract complex types with at least one concrete descendant, by qualified name. */
    val types: Map<QName, HeadMembers>

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
        types =
            complexTypesByName
                .filter { it.value.first.abstract }
                .mapNotNull { (name, pair) ->
                    val members = sorted(concrete(name, mutableSetOf()).distinct())
                    if (members.isEmpty()) null
                    else name to HeadMembers(members, pair.first.line, pair.second)
                }
                .toMap()

        val substitutes = mutableMapOf<QName, MutableList<XElement>>()
        elementsByName.values.forEach { (el, _) ->
            el.substitutionGroup?.let { substitutes.getOrPut(it) { mutableListOf() } += el }
        }
        fun chain(head: QName, seen: MutableSet<QName>): List<XElement> =
            substitutes[head].orEmpty().flatMap { el ->
                val name = elementNames.getValue(el)
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
                        concreteElements.filter { el ->
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
}
