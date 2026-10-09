package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.UnitType
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import java.math.BigDecimal
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The `(validate.rules)` of protoc-gen-validate on one field: the rules of one [kind]. */
internal data class ValidateRules(val kind: String, val rules: OptionValue.Aggregate)

private const val EXTENSION = "(validate.rules)"

/**
 * The validate rules among a field's [options], one entry per kind. Three spellings reach the same
 * shape: `(validate.rules).uint32 = {gte: 1}`, `(validate.rules) = {uint32 {gte: 1}}`, and the
 * dotted `(validate.rules).uint32.gte = 1`. The dotted name is rebuilt as the nested aggregate it
 * abbreviates, so `repeated.items.string.min_len = 1` is `repeated {items {string {min_len: 1}}}`.
 */
internal fun validateRules(options: List<ProtoOption>): List<ValidateRules> {
    val byKind = LinkedHashMap<String, MutableList<Pair<String, OptionValue>>>()
    for (option in options) {
        if (option.name != EXTENSION && !option.name.startsWith("$EXTENSION.")) continue
        val path = option.name.removePrefix(EXTENSION).split('.').filter { it.isNotEmpty() }
        val value: OptionValue = option.aggregate ?: OptionValue.Literal(option.value)
        val tree =
            path.foldRight(value) { part, inner -> OptionValue.Aggregate(listOf(part to inner)) }
        (tree as? OptionValue.Aggregate)?.fields?.forEach { (kind, rules) ->
            if (rules is OptionValue.Aggregate)
                byKind.getOrPut(kind) { mutableListOf() } += rules.fields
        }
    }
    return byKind.map { (kind, fields) -> ValidateRules(kind, OptionValue.Aggregate(fields)) }
}

/**
 * Turns a field's validate rules into the bounds and patterns of its Schemata type. A rule with no
 * Schemata counterpart is counted in [FileLowering.droppedRules] and reported once per file, so a
 * corpus that repeats one rule on hundreds of fields yields one line.
 */
internal class ValidateLowering(private val lowering: FileLowering) {
    /**
     * [type] and [nullable] as the proto type lowered, with the field's rules applied: the refined
     * type, and whether the field is still nullable (`message.required` takes the `?` off a
     * wrapper). A `schemata:` note is merged by the caller afterwards, so a note still wins.
     */
    fun apply(
        f: ProtoField,
        type: UnitType,
        nullable: Boolean,
        where: String,
        note: (DiagnosticCode, String) -> Unit,
    ): Pair<UnitType, Boolean> {
        val rules = validateRules(f.options)
        if (rules.isEmpty()) return type to nullable
        val field = FieldRules(f, where, note, nullable)
        var result = type
        for (r in rules) result = field.kind(r.kind, r.rules, result)
        return result to field.nullable
    }

    private inner class FieldRules(
        val f: ProtoField,
        val where: String,
        val note: (DiagnosticCode, String) -> Unit,
        var nullable: Boolean,
    ) {
        /** The rule keys already counted for this field: a field counts once per key. */
        private val counted = HashSet<String>()

        fun drop(kind: String, key: String) {
            val name = "$kind.$key"
            if (!counted.add(name)) return
            val previous = lowering.droppedRules[name]
            lowering.droppedRules[name] =
                if (previous == null) f.pos to 1 else previous.first to previous.second + 1
        }

        private fun dropAll(kind: String, rules: OptionValue.Aggregate) {
            rules.fields.forEach { (key, _) -> if (key != IGNORE_EMPTY) drop(kind, key) }
        }

        private fun on(value: OptionValue): Boolean = literal(value) != "false"

        /** [rules] of one [kind] applied to [type]; the type, refined. */
        fun kind(kind: String, rules: OptionValue.Aggregate, type: UnitType): UnitType {
            // An ignore_empty rule makes every other rule of the field conditional on the value
            // being non-empty, which Schemata cannot say, so the rules go and the field stays
            // plain.
            if (rules.fields.any { it.first == IGNORE_EMPTY && literal(it.second) == "true" }) {
                drop(kind, IGNORE_EMPTY)
                return type
            }
            return when (kind) {
                "int32",
                "int64",
                "uint32",
                "uint64",
                "sint32",
                "sint64",
                "fixed32",
                "fixed64",
                "sfixed32",
                "sfixed64" -> scalar(kind, rules, type) { numeric(kind, rules, it, true) }
                "float",
                "double" -> scalar(kind, rules, type) { numeric(kind, rules, it, false) }
                "string" -> scalar(kind, rules, type) { string(rules, it) }
                "bytes" -> scalar(kind, rules, type) { bytes(rules, it) }
                "repeated" -> repeated(rules, type)
                "map" -> map(rules, type)
                "enum" -> {
                    rules.fields.forEach { (key, _) ->
                        if (key != "defined_only" && key != IGNORE_EMPTY) drop(kind, key)
                    }
                    type
                }
                "message" -> {
                    // A wrapper lowers to `T?`; required says it is always set, which is plain `T`.
                    // On a message-typed field, a oneof member or with skip, the field is already
                    // as strict as Schemata can be.
                    val required = rules.fields.any { it.first == "required" && on(it.second) }
                    if (required) nullable = false
                    type
                }
                "any" -> {
                    rules.fields.forEach { (key, _) ->
                        if (key != "required" && key != IGNORE_EMPTY) drop(kind, key)
                    }
                    type
                }
                // Bounds on a duration or instant, a bool's const, and kinds the validate plugin
                // does not define have no Schemata counterpart.
                else -> {
                    dropAll(kind, rules)
                    type
                }
            }
        }

        private fun scalar(
            kind: String,
            rules: OptionValue.Aggregate,
            type: UnitType,
            apply: (UnitType.Scalar) -> UnitType.Scalar,
        ): UnitType {
            if (type !is UnitType.Scalar) {
                dropAll(kind, rules)
                return type
            }
            return apply(type)
        }

        private fun numeric(
            kind: String,
            rules: OptionValue.Aggregate,
            type: UnitType.Scalar,
            integer: Boolean,
        ): UnitType.Scalar {
            if (!accepts(type, "min")) {
                dropAll(kind, rules)
                return type
            }
            val refinements = type.refinements.toMutableList()
            for ((key, value) in rules.fields) {
                val text = literal(value)?.let { number(it, integer) }
                when {
                    key == IGNORE_EMPTY -> {}
                    text == null || key !in NUMERIC_BOUNDS -> drop(kind, key)
                    key == "gte" -> tighten(refinements, "min", text)
                    key == "lte" -> tighten(refinements, "max", text)
                    key == "const" -> {
                        tighten(refinements, "min", text)
                        tighten(refinements, "max", text)
                    }
                    // An integer bound that excludes N is the bound one step in.
                    integer -> {
                        val n = text.toLong()
                        val bound =
                            try {
                                if (key == "gt") Math.addExact(n, 1) else Math.subtractExact(n, 1)
                            } catch (_: ArithmeticException) {
                                drop(kind, key)
                                continue
                            }
                        tighten(refinements, if (key == "gt") "min" else "max", bound.toString())
                    }
                    // A float has no next value, so the exclusive bound becomes the inclusive one.
                    else -> {
                        val bound = if (key == "gt") "min" else "max"
                        tighten(refinements, bound, text)
                        note(
                            ImportCodes.APPROXIMATED,
                            "$where: $key $text imported as $bound $text; the bound is inclusive",
                        )
                    }
                }
            }
            return type.copy(refinements = ordered(refinements))
        }

        private fun string(rules: OptionValue.Aggregate, type: UnitType.Scalar): UnitType.Scalar {
            if (!accepts(type, "pattern")) {
                dropAll("string", rules)
                return type
            }
            val refinements = type.refinements.toMutableList()
            var uuid = false
            val hasWellKnown = rules.fields.any { it.first == "well_known_regex" }
            // Only one pattern fits the slot: the first of these that is written is kept.
            val patternRule =
                PATTERN_RULES.firstOrNull { name -> rules.fields.any { it.first == name } }
            for ((key, value) in rules.fields) {
                val text = literal(value)
                when {
                    key == IGNORE_EMPTY -> {}
                    key in PATTERN_RULES -> {
                        if (key != patternRule) drop("string", key)
                        else {
                            val regex = regex(key, value)
                            if (regex == null) drop("string", key)
                            else if (key != "pattern" || compiles(regex)) {
                                tighten(refinements, "pattern", SchemataText.string(regex))
                            }
                        }
                    }
                    key == "min_len" || key == "max_len" || key == "len" -> {
                        val n = count(text)
                        if (n == null) drop("string", key) else length(refinements, key, n)
                    }
                    key == "min_bytes" -> {
                        val n = count(text)
                        when {
                            n == null -> drop("string", key)
                            n == "0" -> {}
                            n == "1" -> tighten(refinements, "min", "1")
                            else -> {
                                // N bytes hold at least ceil(N/4) characters, as a character is at
                                // most four bytes of UTF-8; fewer characters than that cannot fill
                                // N.
                                val chars = (n.toLong() + 3) / 4
                                tighten(refinements, "min", chars.toString())
                                note(
                                    ImportCodes.WIDENED,
                                    "$where: min_bytes $n imported as min $chars; " +
                                        "Schemata counts characters",
                                )
                            }
                        }
                    }
                    key == "max_bytes" -> {
                        val n = count(text)
                        if (n == null) drop("string", key)
                        else {
                            // A character takes at least one byte, so N bytes hold at most N chars.
                            tighten(refinements, "max", n)
                            note(
                                ImportCodes.WIDENED,
                                "$where: max_bytes $n imported as max $n; Schemata counts characters",
                            )
                        }
                    }
                    key == "uuid" -> if (text == "true") uuid = true
                    // Strict only qualifies the well-known regex; it is its own rule without one.
                    key == "strict" -> if (!hasWellKnown) drop("string", key)
                    key in STRING_FLAGS -> if (on(value)) drop("string", key)
                    else -> drop("string", key)
                }
            }
            // A uuid has no bounds or pattern of its own; the type says all there is to say.
            if (uuid) return UnitType.Scalar("uuid", emptyList())
            return type.copy(refinements = ordered(refinements))
        }

        private fun bytes(rules: OptionValue.Aggregate, type: UnitType.Scalar): UnitType.Scalar {
            val refinements = type.refinements.toMutableList()
            for ((key, value) in rules.fields) {
                when (key) {
                    IGNORE_EMPTY -> {}
                    "min_len",
                    "max_len",
                    "len" -> {
                        val n = count(literal(value))
                        if (n == null) drop("bytes", key) else length(refinements, key, n)
                    }
                    else -> drop("bytes", key)
                }
            }
            return type.copy(refinements = ordered(refinements))
        }

        private fun repeated(rules: OptionValue.Aggregate, type: UnitType): UnitType {
            if (type !is UnitType.ListOf) {
                dropAll("repeated", rules)
                return type
            }
            val refinements = type.refinements.toMutableList()
            var element = type.element
            for ((key, value) in rules.fields) {
                val n = count(literal(value))
                when {
                    key == IGNORE_EMPTY -> {}
                    key == "min_items" && n != null -> tighten(refinements, "min", n)
                    key == "max_items" && n != null -> tighten(refinements, "max", n)
                    key == "items" && value is OptionValue.Aggregate ->
                        element = nested(value, element)
                    key == "unique" && !on(value) -> {}
                    else -> drop("repeated", key)
                }
            }
            return type.copy(element = element, refinements = ordered(refinements))
        }

        private fun map(rules: OptionValue.Aggregate, type: UnitType): UnitType {
            if (type !is UnitType.MapOf) {
                dropAll("map", rules)
                return type
            }
            val refinements = type.refinements.toMutableList()
            var key = type.key
            var value = type.value
            for ((name, rule) in rules.fields) {
                val n = count(literal(rule))
                when {
                    name == IGNORE_EMPTY -> {}
                    name == "min_pairs" && n != null -> tighten(refinements, "min", n)
                    name == "max_pairs" && n != null -> tighten(refinements, "max", n)
                    name == "keys" && rule is OptionValue.Aggregate -> key = nested(rule, key)
                    name == "values" && rule is OptionValue.Aggregate -> value = nested(rule, value)
                    name == "no_sparse" && !on(rule) -> {}
                    else -> drop("map", name)
                }
            }
            return type.copy(key = key, value = value, refinements = ordered(refinements))
        }

        /** The rules of a repeated field's items or a map's keys or values, as `{kind {…}}`. */
        private fun nested(rules: OptionValue.Aggregate, type: UnitType): UnitType {
            var result = type
            for ((kind, inner) in rules.fields) {
                if (inner is OptionValue.Aggregate) result = kind(kind, inner, result)
            }
            return result
        }

        private fun length(refinements: MutableList<Pair<String, String>>, key: String, n: String) {
            if (key != "max_len") tighten(refinements, "min", n)
            if (key != "min_len") tighten(refinements, "max", n)
        }
    }

    /**
     * The pattern a prefix, suffix, contains, in, or pattern rule stands for; null if unreadable.
     * The reader has already decoded a string's quotes and escapes.
     */
    private fun FieldRules.regex(key: String, value: OptionValue): String? =
        when (key) {
            "in" -> {
                val items = (value as? OptionValue.ListValue)?.items?.map { literal(it) }
                if (items.isNullOrEmpty() || null in items) null
                else "^(${items.joinToString("|") { escape(it!!) }})$"
            }
            else -> {
                val text = literal(value)
                when {
                    text == null -> null
                    key == "pattern" -> text
                    key == "prefix" -> "^" + escape(text)
                    key == "suffix" -> escape(text) + "$"
                    else -> escape(text)
                }
            }
        }

    /** Whether the pattern compiles; a pattern that does not is reported and left out. */
    private fun FieldRules.compiles(regex: String): Boolean =
        try {
            Pattern.compile(regex)
            true
        } catch (e: PatternSyntaxException) {
            note(
                ImportCodes.DROPPED,
                "$where: validate pattern '$regex' dropped; it does not compile: ${e.description}",
            )
            false
        }

    private companion object {
        const val IGNORE_EMPTY = "ignore_empty"
        val NUMERIC_BOUNDS = setOf("gt", "gte", "lt", "lte", "const")
        val PATTERN_RULES = listOf("pattern", "prefix", "suffix", "contains", "in")
        val STRING_FLAGS =
            setOf(
                "email",
                "hostname",
                "ip",
                "ipv4",
                "ipv6",
                "uri",
                "uri_ref",
                "address",
                "well_known_regex",
            )
        val INTEGER = Regex("-?[0-9]+")
        val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val ORDER = listOf("p", "s", "min", "max", "pattern")

        fun literal(value: OptionValue): String? = (value as? OptionValue.Literal)?.text

        /** A bound as a number, or null when it is not one this kind takes. */
        fun number(text: String, integer: Boolean): String? =
            text.takeIf { if (integer) INTEGER.matches(it) else DECIMAL.matches(it) }

        /** A size: a non-negative integer, or null. */
        fun count(text: String?): String? =
            text?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        /** Only min, max, and (on a string) pattern have a place; other scalars take none. */
        fun accepts(type: UnitType.Scalar, key: String): Boolean =
            when (type.builtin) {
                "int32",
                "int64",
                "float32",
                "float64",
                "decimal",
                "bytes" -> key != "pattern"
                "string" -> true
                else -> false
            }

        /**
         * Sets [key] to [value] unless a bound at least as tight is there: the larger minimum, the
         * smaller maximum. A pattern has no order; the first stays.
         */
        fun tighten(refinements: MutableList<Pair<String, String>>, key: String, value: String) {
            val i = refinements.indexOfFirst { it.first == key }
            if (i < 0) {
                refinements += key to value
                return
            }
            if (key == "pattern") return
            val cmp = BigDecimal(value).compareTo(BigDecimal(refinements[i].second))
            if ((key == "min" && cmp > 0) || (key == "max" && cmp < 0))
                refinements[i] = key to value
        }

        fun ordered(refinements: List<Pair<String, String>>): List<Pair<String, String>> =
            refinements.sortedBy { ORDER.indexOf(it.first) }

        /**
         * Backslash before each character a regular expression reads as syntax. Only those get one
         * so the result is the same in every dialect a target checks it against; `\Q…\E` would not
         * be.
         */
        fun escape(text: String): String = buildString {
            for (c in text) {
                if (c in METACHARACTERS) append('\\')
                append(c)
            }
        }

        const val METACHARACTERS = "\\^$.|?*+()[]{}"
    }
}
