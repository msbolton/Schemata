package io.schemata.core

import io.schemata.core.ir.Builtin
import java.math.BigDecimal

/**
 * The finite range of a binary floating-point builtin: a default or bound of larger magnitude has
 * no value of the type, and a target would write a literal its own tools reject.
 */
internal enum class FloatRange(val builtin: Builtin, val text: String) {
    FLOAT32(Builtin.FLOAT32, "3.4028235E38"),
    FLOAT64(Builtin.FLOAT64, "1.7976931348623157E308");

    private val limit = BigDecimal(text)

    fun contains(value: BigDecimal): Boolean = value.abs() <= limit

    /** How a message names the range: `±3.4028235E38`. */
    val shown: String
        get() = "±$text"

    /** The range as help writes it: `-3.4028235E38 and 3.4028235E38`. */
    val between: String
        get() = "-$text and $text"

    companion object {
        fun of(builtin: Builtin): FloatRange? = entries.firstOrNull { it.builtin == builtin }
    }
}
