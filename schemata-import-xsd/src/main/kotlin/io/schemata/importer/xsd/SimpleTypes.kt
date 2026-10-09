package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitType
import io.schemata.importer.xsd.XsdImport.FieldName
import io.schemata.importer.xsd.XsdImport.enumFacets
import io.schemata.importer.xsd.XsdImport.fieldNameFor
import io.schemata.importer.xsd.XsdImport.spellSigns

/** Resolves simple types: restrictions with their facets, lists, unions, and enumerations. */
internal class SimpleTypes(private val context: ImportContext) {
    /** A named, enumerated simple type: an enum. */
    fun enumDeclaration(st: XSimpleType): UnitEnum = context.at(st.path) { enumDeclarationAt(st) }

    private fun enumDeclarationAt(st: XSimpleType): UnitEnum {
        val original = st.name ?: error("an enumerated top-level simple type always has a name")
        val info = context.typeNames.getValue(QName(context.doc.targetNamespace, original))
        when (val note = info.note) {
            is TypeNote.Unfixable ->
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.APPROXIMATED,
                        "simple type '$original'",
                        "simple type '$original' has no Schemata equivalent; the regenerated " +
                            "type will be named '${note.regeneratedType}'",
                        st.line,
                    )
            is TypeNote.Blocked ->
                context.diagnostics +=
                    context.typeCollision(original, note.other, st.line, "simple type")
            null -> Unit
        }
        val where = "simple type '$original'"
        if (st.variety is XVariety.Union) unionNote(where, "enum '${info.finalName}'", st.line)
        val built = buildInlineEnum(st, info.finalName, where)
        return built.copy(annotations = listOfNotNull(info.annotation))
    }

    /**
     * An anonymous or named enumeration's values (a union of enumerations' values, see
     * [enumFacets]), in order, with per-value naming and docs.
     */
    internal fun buildInlineEnum(st: XSimpleType, name: String, where: String): UnitEnum {
        val values =
            enumValueNames(st).map { (f, field) ->
                val valueWhere = "enum value '$name.${f.value}'"
                // A value's @xsd(name) override, when the xsd target would accept it, always
                // regenerates the original xsd text exactly, so this is never reported; a value
                // that is not even a valid XML name gets no override (one would only be
                // rejected), and that is reported instead.
                if (field.unfixable) {
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.APPROXIMATED,
                            valueWhere,
                            "$valueWhere has no Schemata equivalent; imported as '${field.name}'",
                            f.line,
                        )
                }
                UnitEnumValue(field.name, f.doc, listOfNotNull(field.annotation))
            }
        return UnitEnum(name, values, st.doc, emptyList())
    }

    /**
     * [st]'s enumeration values with the names they import as, in order: each lowered as a field
     * name is, its signs spelled first (see [spellSigns]); a value whose name an earlier one
     * already took is numbered (`v_2`), with an override back to its text when that is a valid
     * name.
     */
    private fun enumValueNames(st: XSimpleType): List<Pair<XFacet, FieldName>> {
        val taken = mutableSetOf<String>()
        return context.enumFacets(st).orEmpty().map { f ->
            val own = fieldNameFor(f.value)
            val spelled = spellSigns(f.value)
            val base = if (spelled == f.value) own else own.copy(name = fieldNameFor(spelled).name)
            var name = base.name
            var n = 2
            while (name in taken) name = "${base.name}_${n++}"
            taken += name
            f to
                if (name == base.name) base
                else if (XsdNames.isValidOverride(f.value))
                    FieldName(name, UnitAnnotation("xsd", "name", "\"${f.value}\""), false)
                else FieldName(name, null, unfixable = true)
        }
    }

    /** The imported name of [st]'s enumeration value [raw], or `null` when it has none. */
    internal fun enumValueName(st: XSimpleType, raw: String): String? =
        enumValueNames(st).firstOrNull { it.first.value == raw }?.second?.name

    /** The enumerated simple type [qname] names, with its declaring document. */
    internal fun namedEnum(qname: QName): Pair<XsdDoc, XSimpleType>? {
        if (qname.namespace == ImportTypes.XS) return null
        val targetDoc = context.docsByNamespace[qname.namespace] ?: return null
        val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
        return if (context.isEnum(st)) targetDoc to st else null
    }

    internal fun implicitAnyType(where: String, line: Int): UnitType.Scalar {
        context.diagnostics +=
            context.lossy(
                ImportCodes.WIDENED,
                where,
                "no declared type; treated as xs:anyType, imported as string",
                line,
            )
        return UnitType.Scalar("string", emptyList())
    }

    internal fun resolveElementScalarOrRef(
        el: XElement,
        originalName: String,
        where: String,
        nested: MutableList<UnitDecl>,
    ): UnitType? {
        if (el.inlineSimple != null)
            return resolveInlineSimpleType(el.inlineSimple, originalName, where, nested)
        if (el.type != null) return resolveTypeRef(el.type, where, el.line)
        return implicitAnyType(where, el.line)
    }

    internal fun resolveElementItemType(
        el: XElement,
        where: String,
        nested: MutableList<UnitDecl>,
    ): UnitType? {
        if (el.inlineSimple != null) {
            return resolveInlineSimpleType(el.inlineSimple, el.name ?: "item", where, nested)
        }
        if (el.type != null) return resolveTypeRef(el.type, where, el.line)
        return implicitAnyType(where, el.line)
    }

    internal fun resolveAttributeType(
        a: XAttribute,
        where: String,
        nested: MutableList<UnitDecl>,
    ): UnitType? {
        if (a.inlineSimple != null)
            return resolveInlineSimpleType(a.inlineSimple, a.name ?: "attribute", where, nested)
        if (a.type != null) return resolveTypeRef(a.type, where, a.line)
        return implicitAnyType(where, a.line)
    }

    internal fun resolveTypeRef(qname: QName, where: String, line: Int): UnitType? {
        if (qname.namespace == ImportTypes.XS) {
            val mapped = ImportTypes.builtin(qname.local) ?: return null
            mapped.notes.forEach {
                context.diagnostics += context.lossy(ImportCodes.WIDENED, where, it, line)
            }
            val type = mapped.type
            // A bare `xs:decimal` reference carries no facets at all, so, exactly like a
            // restriction that omits totalDigits and fractionDigits, it needs the same
            // precision-and-scale default Schemata requires.
            if (
                type is UnitType.Scalar && type.builtin == "decimal" && type.refinements.isEmpty()
            ) {
                val (refined, notes) = ImportTypes.facets(type, emptyList(), line)
                notes.forEach {
                    context.diagnostics += context.noteDiagnostic(context.sourcePath, where, it)
                }
                return refined
            }
            return type
        }
        val targetDoc = context.docsByNamespace[qname.namespace] ?: return null
        context.choiceLowering.headType(qname)?.let {
            return it
        }
        val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
        if (ct != null) return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
        val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
        if (context.isEnum(st)) return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
        return resolveNamedSimpleType(st, targetDoc.path, where, setOf(qname))
    }

    internal fun qualifiedTypeName(targetDoc: XsdDoc, original: String): String {
        val info = context.typeNames.getValue(QName(targetDoc.targetNamespace, original))
        return if (targetDoc === context.doc) info.finalName
        else "${context.namespaceNames.getValue(targetDoc)}.${info.finalName}"
    }

    /**
     * An inline (anonymous) simple type: an enumeration, or a union of them, becomes a nested enum;
     * anything else is resolved in place.
     */
    private fun resolveInlineSimpleType(
        st: XSimpleType,
        elementOrAttributeName: String,
        where: String,
        nested: MutableList<UnitDecl>,
    ): UnitType {
        if (context.isEnum(st)) {
            val name = ImportNames.upperCamel(elementOrAttributeName)
            if (st.variety is XVariety.Union) unionNote(where, "enum '$name'", st.line)
            nested += buildInlineEnum(st, name, where)
            return UnitType.Ref(name)
        }
        return resolveNamedSimpleType(st, context.doc.path, where)
    }

    /**
     * [st] as a type: a restriction as its refined base, a list as `list<T>` of its item type, a
     * union as described at [unionType]. [visiting] holds the named simple types already on the way
     * here, so a chain that reaches one of them again is reported rather than followed.
     */
    internal fun resolveNamedSimpleType(
        st: XSimpleType,
        path: String,
        where: String,
        visiting: Set<QName> = emptySet(),
    ): UnitType {
        return when (val variety = st.variety) {
            is XVariety.Restriction -> {
                val base =
                    when {
                        variety.base != null ->
                            resolveSimpleTypeByQName(variety.base, where, st.line, visiting)
                                ?: UnitType.Scalar("string", emptyList())
                        variety.inlineBase != null ->
                            resolveNamedSimpleType(variety.inlineBase, path, where, visiting)
                        else -> UnitType.Scalar("string", emptyList())
                    }
                restrict(base, variety.facets, st.line, path, where)
            }
            is XVariety.ListOf ->
                UnitType.ListOf(
                    listItem(variety, path, where, st.line, visiting),
                    false,
                    emptyList(),
                )
            is XVariety.Union -> unionType(variety, path, where, st.line, visiting)
        }
    }

    /**
     * A list's item type: a builtin, an enum, or any other simple type resolved in place. An item
     * that is itself a list has no Schemata equivalent and is imported as a string.
     */
    private fun listItem(
        list: XVariety.ListOf,
        path: String,
        where: String,
        line: Int,
        visiting: Set<QName>,
    ): UnitType {
        val string = UnitType.Scalar("string", emptyList())
        val q = list.itemType
        val item =
            when {
                q != null -> {
                    val named = namedEnum(q)
                    if (named != null) UnitType.Ref(qualifiedTypeName(named.first, q.local))
                    else resolveSimpleTypeByQName(q, where, line, visiting) ?: string
                }
                list.inlineItem != null ->
                    resolveNamedSimpleType(list.inlineItem, path, where, visiting)
                else -> string
            }
        return when {
            item is UnitType.ListOf -> {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.APPROXIMATED,
                        where,
                        "list item that is itself a list imported as string",
                        line,
                    )
                string
            }
            // A bare xs:decimal carries no digits, which Schemata requires.
            item is UnitType.Scalar && item.builtin == "decimal" && item.refinements.isEmpty() ->
                restrict(item, emptyList(), line, path, where)
            else -> item
        }
    }

    /**
     * A union simple type that is not enumerated (an enumerated one is an enum declaration): the
     * builtin its members share when they are all one scalar builtin, without their refinements; a
     * string otherwise. Noted either way.
     */
    private fun unionType(
        union: XVariety.Union,
        path: String,
        where: String,
        line: Int,
        visiting: Set<QName>,
    ): UnitType {
        val string = UnitType.Scalar("string", emptyList())
        val members =
            union.memberTypes.map { q ->
                if (namedEnum(q) != null) string
                else resolveSimpleTypeByQName(q, where, line, visiting) ?: string
            } +
                union.inlineMembers.map {
                    if (context.isEnum(it)) string
                    else resolveNamedSimpleType(it, path, where, visiting)
                }
        val builtins = members.map { (it as? UnitType.Scalar)?.builtin }.distinct()
        val builtin = builtins.singleOrNull() ?: "string"
        unionNote(where, builtin, line)
        val type = UnitType.Scalar(builtin, emptyList())
        return if (builtin == "decimal") restrict(type, emptyList(), line, path, where) else type
    }

    /** Notes that a union simple type at [where] was imported as [what]. */
    internal fun unionNote(where: String, what: String, line: Int) {
        context.diagnostics +=
            context.lossy(
                ImportCodes.APPROXIMATED,
                where,
                "union simple type imported as $what",
                line,
            )
    }

    /**
     * [base] narrowed by [facets]: a scalar's as [ImportTypes.facets] reads them; a list's length
     * facets bound the list, and any other facet on a list or an enum is dropped. Notes point into
     * [path], the declaring document.
     */
    internal fun restrict(
        base: UnitType,
        facets: List<XFacet>,
        line: Int,
        path: String,
        where: String,
    ): UnitType {
        if (base is UnitType.Scalar) {
            val (refined, notes) = ImportTypes.facets(base, facets, line)
            notes.forEach { context.diagnostics += context.noteDiagnostic(path, where, it) }
            return refined
        }
        val bounds = (base as? UnitType.ListOf)?.refinements.orEmpty().toMap(LinkedHashMap())
        facets.forEach { f ->
            val keys =
                when (f.name) {
                    "length" -> listOf("min", "max")
                    "minLength" -> listOf("min")
                    "maxLength" -> listOf("max")
                    else -> emptyList()
                }
            val count = f.value.trim().takeIf { it.matches(Regex("[0-9]+")) }
            val note =
                when {
                    base !is UnitType.ListOf || keys.isEmpty() -> "facet ${f.name} dropped"
                    count == null -> "facet ${f.name} value '${f.value}' dropped"
                    else -> null
                }
            if (note != null) {
                context.diagnostics +=
                    context.noteDiagnostic(path, where, Note(ImportCodes.WIDENED, note, f.line))
            } else {
                keys.forEach { bounds[it] = count!!.toBigInteger().toString() }
            }
        }
        if (base !is UnitType.ListOf) return base
        val refinements = listOf("min", "max").mapNotNull { k -> bounds[k]?.let { k to it } }
        return base.copy(refinements = refinements)
    }

    internal fun resolveSimpleTypeByQName(
        qname: QName,
        where: String,
        line: Int,
        visiting: Set<QName> = emptySet(),
    ): UnitType? {
        if (qname.namespace == ImportTypes.XS) {
            val mapped = ImportTypes.builtin(qname.local) ?: return null
            mapped.notes.forEach {
                context.diagnostics += context.lossy(ImportCodes.WIDENED, where, it, line)
            }
            return mapped.type
        }
        if (qname in visiting) {
            context.diagnostics +=
                context.lossy(
                    ImportCodes.UNRESOLVED,
                    where,
                    "simple type '${qname.local}' cannot be resolved; the reference chain is " +
                        "cyclic",
                    line,
                )
            return UnitType.Scalar("string", emptyList())
        }
        val targetDoc = context.docsByNamespace[qname.namespace] ?: return null
        val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
        // An enumeration, or a union of them, is an enum declaration of its own: a simple
        // content value or a restriction based on one names the enum.
        if (context.isEnum(st)) {
            return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
        }
        return resolveNamedSimpleType(st, targetDoc.path, where, visiting + qname)
    }
}
