package io.schemata.target.openapi

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Operation
import io.schemata.core.ir.Service
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.HttpSecurity
import io.schemata.target.SecurityRequirement
import io.schemata.target.flag
import io.schemata.target.string

/**
 * One namespace's security: the schemes its schema line declares, the requirement each operation is
 * under, and the checks of both. A problem is reported under SCH2606 at the schema, service, or
 * operation that writes the key.
 */
internal class SecurityLowering(
    private val namespace: Namespace,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val line: Annotations = namespace.annotations

    /**
     * The scheme kinds the schema line writes a key for, valid or not: an `auth` naming a scheme
     * whose value is already reported is not reported again.
     */
    private val declared: Set<String> =
        HttpSecurity.SCHEMES.filter { scheme ->
                HttpSecurity.keysOf(scheme).any { it in line["openapi"] }
            }
            .toSet()

    /** The schema line's requirement, which the document's top-level `security` writes. */
    val document: SecurityRequirement? = HttpSecurity.requirement(line)

    /** The requirement [operation] of [service] is under. */
    fun requirement(service: Service, operation: Operation): SecurityRequirement? =
        HttpSecurity.requirement(line, service.annotations, operation.annotations)

    /**
     * [operation]'s own `security`: null when its requirement is the document's, since OpenAPI
     * applies the top-level one to every operation that writes none.
     */
    fun operation(service: Service, operation: Operation): List<Requirement>? =
        requirement(service, operation).takeIf { it != document }?.let(::written)

    /**
     * [requirement] as a `security` list: one entry per scheme, any one of which admits a call, the
     * scopes on the schemes that carry them; `none` is the empty list.
     */
    fun written(requirement: SecurityRequirement): List<Requirement> =
        requirement.schemes.map { scheme ->
            Requirement(
                scheme,
                if (scheme in HttpSecurity.SCOPED) requirement.scopes else emptyList(),
            )
        }

    /**
     * The declared schemes in the order bearer, basic, api_key, oauth2, openid, each named by its
     * key; one whose value is reported is left out. An oauth2 scheme lists every scope of the
     * [requirements] that name it, sorted.
     */
    fun schemes(requirements: List<SecurityRequirement>): List<SecurityScheme> {
        val scopes =
            requirements
                .filter { HttpSecurity.OAUTH2 in it.schemes }
                .flatMap { it.scopes }
                .distinct()
                .sorted()
        return buildList<SecurityScheme> {
            when (val bearer = line["openapi"]["bearer"]) {
                null -> {}
                is AnnotationValue.Str ->
                    add(SecurityScheme.Http("bearer", "bearer", bearer.value.ifEmpty { null }))
                else -> add(SecurityScheme.Http("bearer", "bearer", null))
            }
            if (line.flag("openapi", "basic")) add(SecurityScheme.Http("basic", "basic", null))
            line.string("openapi", "api_key")?.let(::apiKey)?.let { (location, name) ->
                add(SecurityScheme.ApiKey("api_key", location, name))
            }
            url("oauth2_token_url")?.let { token ->
                add(
                    SecurityScheme.OAuth2(
                        HttpSecurity.OAUTH2,
                        token,
                        url("oauth2_authorization_url"),
                        scopes,
                    )
                )
            }
            url("openid")?.let { add(SecurityScheme.OpenIdConnect("openid", it)) }
        }
    }

    /** Checks the schema line's schemes and every `auth` and `scopes` of the namespace. */
    fun check() {
        checkSchemes()
        checkLevel("schema '${namespace.name}'", namespace.span, line, outer = null)
        namespace.services.forEach { service ->
            checkLevel("service '${service.name}'", service.nameSpan, service.annotations, document)
            val inherited = HttpSecurity.requirement(line, service.annotations)
            service.operations.forEach { op ->
                checkLevel("operation '${op.name}'", op.nameSpan, op.annotations, inherited)
            }
        }
    }

    /**
     * An API key needs where it is sent and its name; the authorization code flow needs the token
     * URL as well; every URL is absolute `http` or `https`.
     */
    private fun checkSchemes() {
        val where = "schema '${namespace.name}'"
        line.string("openapi", "api_key")?.let { value ->
            if (apiKey(value) == null)
                report(
                    where,
                    namespace.span,
                    "@openapi(api_key: \"$value\") is not \"<in> <name>\"",
                    "write `@openapi(api_key: \"header X-API-Key\")`: header, query, or cookie, " +
                        "then the name",
                )
        }
        if ("oauth2_authorization_url" in line["openapi"] && "oauth2_token_url" !in line["openapi"])
            report(
                where,
                namespace.span,
                "@openapi(oauth2_authorization_url) needs @openapi(oauth2_token_url)",
                "add `@openapi(oauth2_token_url: \"…\")`; the authorization code flow needs both URLs",
            )
        URL_KEYS.forEach { key ->
            val value = line.string("openapi", key) ?: return@forEach
            if (!URL.matches(value))
                report(
                    where,
                    namespace.span,
                    "@openapi($key: \"$value\") is not an absolute http or https URL",
                    "use an absolute URL such as `https://auth.example.com/token`",
                )
        }
    }

    /**
     * One element's `auth` names declared schemes, or `none` alone; its `scopes` need an oauth2 or
     * openid scheme in the requirement there, its own `auth` or else the [outer] one it inherits.
     */
    private fun checkLevel(
        where: String,
        span: Span,
        annotations: Annotations,
        outer: SecurityRequirement?,
    ) {
        val written = annotations["openapi"]
        val auth = (written["auth"] as? AnnotationValue.Names)?.values
        if (auth != null) checkAuth(where, span, auth)
        if ("scopes" !in written) return
        val schemes = auth?.filter { it != HttpSecurity.NONE } ?: outer?.schemes.orEmpty()
        if (schemes.none { it in HttpSecurity.SCOPED })
            report(
                where,
                span,
                "@openapi(scopes) applies to no oauth2 or openid scheme",
                "require oauth2 or openid with `@openapi(auth: oauth2)` at the same level, or " +
                    "remove the scopes",
            )
    }

    private fun checkAuth(where: String, span: Span, names: List<String>) {
        val distinct = names.distinct()
        if (HttpSecurity.NONE in distinct && distinct.size > 1)
            report(
                where,
                span,
                "@openapi(auth) names none together with a scheme",
                "write `@openapi(auth: none)` alone, or leave none out",
            )
        distinct
            .filter { it != HttpSecurity.NONE }
            .forEach { name ->
                when {
                    name !in HttpSecurity.SCHEMES ->
                        report(
                            where,
                            span,
                            "@openapi(auth) names '$name', which is not a scheme",
                            "name bearer, basic, api_key, oauth2, openid, or none",
                        )
                    name !in declared ->
                        report(
                            where,
                            span,
                            "@openapi(auth) names '$name', which the schema does not declare",
                            "declare it on the schema line with `${declaration(name)}`, or name a " +
                                "declared scheme",
                        )
                }
            }
    }

    /** How the schema line declares [scheme], for a help message. */
    private fun declaration(scheme: String): String =
        when (scheme) {
            "api_key" -> "@openapi(api_key: \"header X-API-Key\")"
            HttpSecurity.OAUTH2 -> "@openapi(oauth2_token_url: \"…\")"
            "openid" -> "@openapi(openid: \"…\")"
            else -> "@openapi($scheme)"
        }

    /** `"header X-API-Key"` as its place and name, or null when it is not one. */
    private fun apiKey(value: String): Pair<String, String>? =
        API_KEY.matchEntire(value)?.let { it.groupValues[1] to it.groupValues[2] }

    /**
     * The schema line's URL under [key] when it is valid; an invalid one is reported by [check].
     */
    private fun url(key: String): String? = line.string("openapi", key)?.takeIf { URL.matches(it) }

    private fun report(where: String, span: Span, message: String, help: String) {
        diagnostics += Diagnostic(OpenApiCodes.SECURITY, "$where: $message", span, help = help)
    }

    private companion object {
        val URL_KEYS = listOf("oauth2_token_url", "oauth2_authorization_url", "openid")
        // a URL's scheme is case-insensitive, so `HTTPS://` is as good as `https://`
        val URL = Regex("(?i:https?)://[^\\s/?#]+\\S*")
        val API_KEY = Regex("(header|query|cookie)\\s+(\\S+)")
    }
}
