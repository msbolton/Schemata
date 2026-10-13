package io.schemata.evolution

import io.schemata.core.ir.ErrorResponse
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName

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

/** An error response's code as written: `404`, or `default`. */
fun errorCodeText(code: Int?): String = code?.toString() ?: "default"

/** An error body as a verdict names it: `Problem`, or `none` for a code without one. */
fun errorBodyText(body: QualifiedName?): String = body?.simpleName ?: "none"

/** An error response as its clause writes it: `404 Problem`, or `503` without a body. */
fun errorText(error: ErrorResponse): String =
    listOfNotNull(errorCodeText(error.code), error.body?.simpleName).joinToString(" ")
