package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations

/**
 * What the `@openapi(auth)` in effect asks of a call: any one of [schemes] admits it, with every
 * one of [scopes] for a scheme that carries scopes. No schemes is `auth: none`, which asks nothing.
 */
data class SecurityRequirement(val schemes: List<String>, val scopes: List<String>)

/** The `@openapi` security keys, read one way by the OpenAPI target and by `diff`. */
object HttpSecurity {
    const val NONE = "none"
    const val OAUTH2 = "oauth2"

    /**
     * Every scheme kind, in the order a document declares them; each is also its component name.
     */
    val SCHEMES = listOf("bearer", "basic", "api_key", OAUTH2, "openid")

    /** The kinds whose requirement carries scopes. */
    val SCOPED = setOf(OAUTH2, "openid")

    /** The schema-line keys that declare or describe [scheme]. */
    fun keysOf(scheme: String): List<String> =
        if (scheme == OAUTH2) listOf("oauth2_token_url", "oauth2_authorization_url")
        else listOf(scheme)

    /** The scheme a schema-line key declares or describes, or null for any other key. */
    fun schemeOf(key: String): String? =
        when (key) {
            "oauth2_token_url",
            "oauth2_authorization_url" -> OAUTH2
            "bearer",
            "basic",
            "api_key",
            "openid" -> key
            else -> null
        }

    /**
     * The requirement in effect at the innermost of [levels], given outermost first (the schema
     * line, a service, an operation). The nearest level that writes `auth` names the schemes and
     * brings its own `scopes`, none when it writes no `scopes`; a nearer level that writes only
     * `scopes` keeps the inherited schemes and replaces their scopes. Null when no level writes
     * `auth`.
     */
    fun requirement(vararg levels: Annotations): SecurityRequirement? {
        var schemes: List<String>? = null
        var scopes: List<String> = emptyList()
        for (level in levels) {
            val written = level["openapi"]
            val auth = (written["auth"] as? AnnotationValue.Names)?.values
            val own = (written["scopes"] as? AnnotationValue.Str)?.value?.let(::scopes)
            if (auth != null) {
                schemes = auth
                scopes = own ?: emptyList()
            } else if (own != null) scopes = own
        }
        val named = schemes ?: return null
        return SecurityRequirement(named.filter { it != NONE }.distinct(), scopes)
    }

    /** A `scopes` value's scope names: space-separated, each once, in written order. */
    fun scopes(text: String): List<String> =
        text.split(WHITESPACE).filter { it.isNotEmpty() }.distinct()

    private val WHITESPACE = Regex("\\s+")
}
