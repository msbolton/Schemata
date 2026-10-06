package io.schemata.evolution

import io.schemata.core.ir.Payload

/** A request or response as a verdict names it: `Order`, `stream Order`, or `none`. */
internal fun payloadText(payload: Payload?): String =
    when {
        payload == null -> "none"
        payload.stream -> "stream ${payload.target.simpleName}"
        else -> payload.target.simpleName
    }
