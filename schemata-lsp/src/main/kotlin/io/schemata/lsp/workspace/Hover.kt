package io.schemata.lsp.workspace

/** One line per builtin type name, shown when the cursor rests on it. */
object BuiltinDocs {
    val text: Map<String, String> =
        mapOf(
            "bool" to "true or false",
            "int32" to "a 32-bit signed integer; refinements: min, max",
            "int64" to "a 64-bit signed integer; refinements: min, max",
            "float32" to "a 32-bit IEEE 754 floating-point number; refinements: min, max",
            "float64" to "a 64-bit IEEE 754 floating-point number; refinements: min, max",
            "decimal" to
                "an exact decimal number, written decimal(precision, scale); refinements: min, max",
            "string" to "Unicode text; refinements: min, max (length), pattern",
            "bytes" to "a byte sequence; refinements: min, max (length)",
            "uuid" to "a universally unique identifier",
            "date" to "a calendar date with no time or zone",
            "time" to "a time of day with no date or zone",
            "instant" to "a point in time, in UTC",
            "duration" to "a length of time",
            "list" to "list<T>: an ordered collection; refinements: min, max (item count)",
            "map" to
                "map<K, V>: entries keyed by string, int32, or int64; refinements: min, max (entry count)",
        )
}
