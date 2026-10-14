package io.schemata.core.ir

import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsesTest {
    private val at = Span("t", 1, 1, 1, 1)
    private val order = QualifiedName("shop", listOf("Order"))
    private val problem = QualifiedName("shop", listOf("Problem"))
    private val conflict = QualifiedName("shop", listOf("Conflict"))

    private fun operation(
        response: Payload? = null,
        status: Int? = null,
        errors: List<ErrorResponse> = emptyList(),
    ) =
        Operation(
            ordinal = 1,
            name = "op",
            request = null,
            response = response,
            binding = null,
            doc = null,
            span = at,
            nameSpan = at,
            status = status,
            errors = errors,
        )

    private fun service(errors: List<ErrorResponse>, operation: Operation) =
        Service(
            qualifiedName = QualifiedName("shop", listOf("S")),
            name = "S",
            operations = listOf(operation),
            reserved = Reserved.NONE,
            doc = null,
            span = at,
            nameSpan = at,
            errors = errors,
        )

    @Test
    fun `the success status is the written code or 200 with a response and 204 without`() {
        assertEquals(200, operation(response = Payload(order, false)).successStatus())
        assertEquals(204, operation().successStatus())
        assertEquals(201, operation(response = Payload(order, false), status = 201).successStatus())
        assertEquals(202, operation(status = 202).successStatus())
    }

    @Test
    fun `effective errors merge the service clause with the operation's own`() {
        val op =
            operation(
                errors =
                    listOf(
                        ErrorResponse(null, conflict),
                        ErrorResponse(409, conflict),
                        ErrorResponse(404, conflict),
                    )
            )
        val s =
            service(
                listOf(
                    ErrorResponse(500, problem),
                    ErrorResponse(404, problem),
                    ErrorResponse(null, problem),
                ),
                op,
            )
        assertEquals(
            listOf(
                ErrorResponse(404, conflict),
                ErrorResponse(409, conflict),
                ErrorResponse(500, problem),
                ErrorResponse(null, conflict),
            ),
            effectiveErrors(s, op),
        )
    }

    @Test
    fun `an operation code without a body replaces the service body for that code`() {
        val op = operation(errors = listOf(ErrorResponse(404, null)))
        assertEquals(
            listOf(ErrorResponse(404, null)),
            effectiveErrors(service(listOf(ErrorResponse(404, problem)), op), op),
        )
    }

    @Test
    fun `no clause anywhere gives no errors`() {
        val op = operation()
        assertEquals(emptyList(), effectiveErrors(service(emptyList(), op), op))
    }
}
