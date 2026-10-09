package io.schemata.evolution

import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Payload

/** A request or response as a verdict names it: `Order`, `stream Order`, or `none`. */
fun payloadText(payload: Payload?): String =
    when {
        payload == null -> "none"
        payload.stream -> "stream ${payload.target.simpleName}"
        else -> payload.target.simpleName
    }

/** A binding as written, `get /orders/{id}`; `none` for an operation with no binding. */
fun bindingText(binding: HttpBinding?): String =
    if (binding == null) "none" else "${binding.verb.lower} ${binding.path}"
