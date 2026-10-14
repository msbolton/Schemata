package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpSecurityTest {
    private fun openapi(vararg keys: Pair<String, AnnotationValue>) =
        Annotations(mapOf("openapi" to mapOf(*keys)))

    private fun auth(vararg names: String) = "auth" to AnnotationValue.Names(names.toList())

    private fun scopes(text: String) = "scopes" to AnnotationValue.Str(text)

    @Test
    fun `the nearest auth wins and brings its own scopes`() {
        val line = openapi(auth("bearer"))
        val service = openapi(auth("oauth2"), scopes("a:read  b:write a:read"))
        assertEquals(
            SecurityRequirement(listOf("bearer"), emptyList()),
            HttpSecurity.requirement(line),
        )
        assertEquals(
            SecurityRequirement(listOf("oauth2"), listOf("a:read", "b:write")),
            HttpSecurity.requirement(line, service, Annotations.NONE),
        )
        assertEquals(
            SecurityRequirement(listOf("bearer", "basic"), emptyList()),
            HttpSecurity.requirement(line, service, openapi(auth("bearer", "basic"))),
        )
    }

    @Test
    fun `scopes written without auth keep the inherited schemes`() {
        val service = openapi(auth("oauth2"), scopes("a:read"))
        assertEquals(
            SecurityRequirement(listOf("oauth2"), listOf("b:write")),
            HttpSecurity.requirement(Annotations.NONE, service, openapi(scopes("b:write"))),
        )
    }

    @Test
    fun `none requires no scheme and no auth anywhere requires nothing`() {
        assertEquals(
            SecurityRequirement(emptyList(), emptyList()),
            HttpSecurity.requirement(openapi(auth("bearer")), openapi(auth("none"))),
        )
        assertNull(
            HttpSecurity.requirement(Annotations.NONE, openapi(scopes("a")), Annotations.NONE)
        )
    }

    @Test
    fun `the two oauth2 keys belong to one scheme`() {
        assertEquals("oauth2", HttpSecurity.schemeOf("oauth2_authorization_url"))
        assertEquals("oauth2", HttpSecurity.schemeOf("oauth2_token_url"))
        assertEquals("api_key", HttpSecurity.schemeOf("api_key"))
        assertNull(HttpSecurity.schemeOf("server"))
        assertEquals(
            listOf("oauth2_token_url", "oauth2_authorization_url"),
            HttpSecurity.keysOf("oauth2"),
        )
        assertEquals(listOf("basic"), HttpSecurity.keysOf("basic"))
    }
}
