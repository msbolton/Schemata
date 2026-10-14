package io.schemata.importer.openapi

import io.schemata.importer.ImportCodes
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitService
import io.schemata.lang.SchemataText
import io.schemata.target.HttpSecurity
import io.swagger.v3.oas.models.security.Scopes
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme

/**
 * What a document gives its unit's schema line: the doc comment, the `@openapi` keys, and the
 * services with the requirements their operations are under.
 */
internal data class SchemaLine(
    val doc: String?,
    val annotations: List<UnitAnnotation>,
    val services: List<UnitService>,
)

/**
 * One document's `info`, `servers` and security as `@openapi` keys, the reverse of how the OpenAPI
 * target writes them.
 *
 * `info.version` is `@openapi(version)` unless it is the target's default `1.0.0`;
 * `info.description` is the schema's doc comment; the first server is `@openapi(server)`. Each
 * security scheme becomes the schema-line key of its kind, which is also the name the target gives
 * the scheme: `bearer`, `basic`, `api_key`, the OAuth URLs, `openid`. Requirements name those keys
 * instead of the document's scheme names: the document's `security` is the schema line's `auth` and
 * `scopes`, a requirement every operation of a service shares is the service's, and an operation
 * under another writes its own; `security: []` is `auth: none`. What Schemata cannot say is noted
 * and the closest requirement kept.
 */
internal class SecurityLowering(
    private val doc: OpenApiDoc,
    private val namespace: String,
    private val refs: Refs,
    private val report: Report,
) {
    /**
     * Any one of [schemes], each a scheme key, admits a call, with [scopes] for the schemes that
     * carry scopes; no schemes is `none`.
     */
    private data class Requirement(val schemes: List<String>, val scopes: List<String>)

    /** Each document scheme name the import keeps, to the key of its kind. */
    private val keys = mutableMapOf<String, String>()

    /** Each kind's first scheme: its document name and the schema-line keys that declare it. */
    private val declared = mutableMapOf<String, Pair<String, List<UnitAnnotation>>>()

    /** The scopes each oauth2 scheme's kept flow declares, and where they sit. */
    private val flowScopes = mutableMapOf<String, Placed<Scopes>>()

    fun lower(services: List<LoweredService>): SchemaLine {
        val info = info()
        schemes()
        val document = doc.api.security?.let { requirement(it, ROOT / "security") }
        val effective = mutableListOf<Requirement>()
        val placed = services.map { place(it, document, effective) }
        checkScopes(listOfNotNull(document) + effective)
        // the target declares its schemes in this order, whatever order the source writes them in
        val schemes = HttpSecurity.SCHEMES.flatMap { declared[it]?.second.orEmpty() }
        return SchemaLine(
            doc.api.info?.description?.trimEnd()?.ifEmpty { null },
            info + schemes + annotations(document),
            placed,
        )
    }

    /** The version and server keys; a title other than the schema's name is noted. */
    private fun info(): List<UnitAnnotation> {
        val title = doc.api.info?.title
        if (title != null && title != namespace)
            dropped(
                ROOT / "info" / "title",
                "title '$title' dropped; the regenerated title is the schema name '$namespace'",
            )
        val version =
            doc.api.info?.version?.takeIf { it != DEFAULT_VERSION }?.let { openapi("version", it) }
        var server: UnitAnnotation? = null
        // the model gives a document without servers a `/` server, so the tree is read instead
        doc.tree.path("servers").forEachIndexed { i, entry ->
            val url = entry.path("url").textValue() ?: return@forEachIndexed
            val at = ROOT / "servers" / i
            when {
                i > 0 -> dropped(at, "server '$url' dropped; only the first server is kept")
                SERVER.matches(url) -> server = openapi("server", url)
                else -> dropped(at, "server '$url' dropped; it is not an absolute URL or a path")
            }
        }
        return listOfNotNull(version, server)
    }

    /**
     * Each scheme's kind and keys, the first of a kind kept; a later one of the same kind is noted,
     * and a requirement naming it asks for the kind.
     */
    private fun schemes() {
        val at = ROOT / "components" / "securitySchemes"
        doc.api.components?.securitySchemes.orEmpty().forEach { (name, written) ->
            val here = at / name
            val place =
                refs.follow(
                    report,
                    Placed(written, doc, here, doc.tree.at(here.text.removePrefix("#"))),
                    "securitySchemes",
                    "security scheme",
                    { it.`$ref` },
                    { d, n, r -> d.getSecurityScheme(n, "securityScheme", r) },
                ) ?: return@forEach
            val (key, annotations) = scheme(name, place) ?: return@forEach
            val first = declared[key]
            if (first != null)
                dropped(
                    here,
                    "scheme '$name' dropped; scheme '${first.first}' is already the $key scheme",
                )
            else declared[key] = name to annotations
            keys[name] = key
        }
    }

    /**
     * [place]'s kind and the schema-line keys that declare it; null after noting why it has none.
     */
    private fun scheme(
        name: String,
        place: Placed<SecurityScheme>,
    ): Pair<String, List<UnitAnnotation>>? {
        val scheme = place.value
        return when (scheme.type) {
            SecurityScheme.Type.HTTP -> {
                val kind = scheme.scheme ?: return null
                // an HTTP authentication scheme's name is case-insensitive
                when (kind.lowercase()) {
                    "bearer" ->
                        "bearer" to
                            listOf(
                                UnitAnnotation(
                                    "openapi",
                                    "bearer",
                                    scheme.bearerFormat
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let(SchemataText::string),
                                )
                            )
                    "basic" -> "basic" to listOf(UnitAnnotation("openapi", "basic", null))
                    else -> drop(place, name, "Schemata has no http $kind scheme")
                }
            }
            SecurityScheme.Type.APIKEY -> {
                val key = scheme.name ?: return null
                val where = scheme.`in`?.toString() ?: return null
                // the target reads `api_key` as the place, then the name up to the end
                if (key.any(Char::isWhitespace))
                    drop(place, name, "its name '$key' holds whitespace")
                else "api_key" to listOf(openapi("api_key", "$where $key"))
            }
            SecurityScheme.Type.OAUTH2 -> oauth2(name, place)
            SecurityScheme.Type.OPENIDCONNECT -> {
                val url = scheme.openIdConnectUrl ?: return null
                if (URL.matches(url)) "openid" to listOf(openapi("openid", url))
                else drop(place, name, "its URL '$url' is not an absolute http or https URL")
            }
            null -> null
            else -> drop(place, name, "Schemata has no ${scheme.type} scheme")
        }
    }

    /**
     * An oauth2 scheme's token URL, and its authorization URL for the authorization code flow,
     * which the target writes when both URLs are set; it writes the client credentials flow
     * otherwise, and no other flow.
     */
    private fun oauth2(
        name: String,
        place: Placed<SecurityScheme>,
    ): Pair<String, List<UnitAnnotation>>? {
        val flows = place.value.flows
        val at = place.at / "flows"
        if (flows?.implicit != null)
            flowDropped(place, at / "implicit", "implicit", name, UNWRITTEN)
        if (flows?.password != null)
            flowDropped(place, at / "password", "password", name, UNWRITTEN)
        val code = flows?.authorizationCode
        val client = flows?.clientCredentials
        if (code != null && client != null)
            flowDropped(
                place,
                at / "clientCredentials",
                "clientCredentials",
                name,
                "the authorizationCode flow is kept",
            )
        val flow =
            code
                ?: client
                ?: return drop(
                    place,
                    name,
                    "it has no authorization code or client credentials flow",
                )
        val flowName = if (code != null) "authorizationCode" else "clientCredentials"
        val token = flow.tokenUrl ?: return null
        val authorization = if (code != null) (flow.authorizationUrl ?: return null) else null
        listOfNotNull(token, authorization)
            .firstOrNull { !URL.matches(it) }
            ?.let {
                return drop(place, name, "its URL '$it' is not an absolute http or https URL")
            }
        flowScopes[name] =
            Placed(
                flow.scopes ?: Scopes(),
                place.doc,
                at / flowName / "scopes",
                place.node.path("flows").path(flowName).path("scopes"),
            )
        return HttpSecurity.OAUTH2 to
            listOfNotNull(
                openapi("oauth2_token_url", token),
                authorization?.let { openapi("oauth2_authorization_url", it) },
            )
    }

    /**
     * [written] as one requirement: its alternatives' schemes in order, with the scopes of the
     * first scheme that carries scopes. An alternative naming several schemes keeps the first; an
     * empty one is `none` alone and is dropped beside a scheme. Null when every alternative was
     * dropped, so the outer requirement applies.
     */
    private fun requirement(written: List<SecurityRequirement>, at: Pointer): Requirement? {
        if (written.isEmpty()) return Requirement(emptyList(), emptyList())
        val schemes = mutableListOf<String>()
        var scopes: List<String>? = null
        var scopesOf: String? = null
        val empty = mutableListOf<Int>()
        written.forEachIndexed { i, alternative ->
            val here = at / i
            if (alternative.isEmpty()) {
                empty += i
                return@forEachIndexed
            }
            val names = alternative.keys.toList()
            val name = names.first()
            if (names.size > 1)
                dropped(
                    here,
                    "requirement names ${names.joinToString(", ") { "'$it'" }} together, which Schemata cannot say; only '$name' is kept",
                )
            val key =
                keys[name]
                    ?: run {
                        dropped(
                            here,
                            "requirement names '$name', which is not a scheme Schemata keeps; dropped",
                        )
                        return@forEachIndexed
                    }
            val own = alternative[name].orEmpty()
            when {
                key !in HttpSecurity.SCOPED ->
                    if (own.isNotEmpty())
                        dropped(
                            here,
                            "scopes of '$name' dropped; only oauth2 and openid requirements carry scopes",
                        )
                scopes == null -> {
                    scopes = own
                    scopesOf = name
                }
                own.distinct() != scopes!!.distinct() ->
                    report.at(
                        doc,
                        here,
                        ImportCodes.APPROXIMATED,
                        "scopes of '$name' differ from those of '$scopesOf'; the requirement keeps those of '$scopesOf'",
                    )
            }
            if (key !in schemes) schemes += key
        }
        if (schemes.isEmpty())
            return if (empty.isNotEmpty()) Requirement(emptyList(), emptyList()) else null
        empty.forEach {
            dropped(
                at / it,
                "empty requirement dropped; it admits a call without credentials, which Schemata cannot say beside a scheme",
            )
        }
        return Requirement(schemes, scopes.orEmpty().distinct())
    }

    /**
     * [lowered] with its requirements written where they first differ from the outer one: on the
     * service when its operations, two or more, all share one, and on each operation that differs
     * from the service's or the document's. [all] collects every operation's requirement.
     */
    private fun place(
        lowered: LoweredService,
        document: Requirement?,
        all: MutableList<Requirement>,
    ): UnitService {
        val effective =
            lowered.sources.map { source ->
                source.operation.security?.let { requirement(it, source.at / "security") }
                    ?: document
            }
        all += effective.filterNotNull()
        val shared =
            effective.distinct().singleOrNull()?.takeIf { effective.size >= 2 && it != document }
        val outer = shared ?: document
        val service = lowered.service
        return service.copy(
            annotations = service.annotations + annotations(shared),
            operations =
                service.operations.mapIndexed { i, op ->
                    if (effective[i] == outer) op
                    else op.copy(annotations = op.annotations + annotations(effective[i]))
                },
        )
    }

    /** `auth` naming [requirement]'s schemes, one or a tuple or `none`, and its `scopes`. */
    private fun annotations(requirement: Requirement?): List<UnitAnnotation> {
        requirement ?: return emptyList()
        val auth =
            when (requirement.schemes.size) {
                0 -> HttpSecurity.NONE
                1 -> requirement.schemes.single()
                else -> requirement.schemes.joinToString(", ", "(", ")")
            }
        return listOfNotNull(
            UnitAnnotation("openapi", "auth", auth),
            requirement.scopes
                .takeIf { it.isNotEmpty() }
                ?.let { openapi("scopes", it.joinToString(" ")) },
        )
    }

    /**
     * The target lists, as the oauth2 scheme's scopes, those its requirements name, sorted and
     * undescribed; a document declaring others, or describing them, is noted.
     */
    private fun checkScopes(requirements: List<Requirement>) {
        val name = declared[HttpSecurity.OAUTH2]?.first ?: return
        val place = flowScopes[name] ?: return
        val named =
            requirements
                .filter { HttpSecurity.OAUTH2 in it.schemes }
                .flatMap { it.scopes }
                .distinct()
                .sorted()
        if (place.value.keys.toList() != named || place.value.values.any { !it.isNullOrEmpty() })
            dropped(
                place.at,
                "scopes of scheme '$name' dropped; the regenerated scheme lists only the scopes its requirements name, without descriptions",
                place.doc,
            )
    }

    private fun flowDropped(
        place: Placed<SecurityScheme>,
        at: Pointer,
        flow: String,
        name: String,
        reason: String,
    ) {
        dropped(at, "$flow flow of scheme '$name' dropped; $reason", place.doc)
    }

    private fun drop(
        place: Placed<SecurityScheme>,
        name: String,
        reason: String,
    ): Pair<String, List<UnitAnnotation>>? {
        dropped(place.at, "scheme '$name' dropped; $reason", place.doc)
        return null
    }

    private fun dropped(at: Pointer, message: String, on: OpenApiDoc = doc) {
        report.at(on, at, ImportCodes.DROPPED, message)
    }

    private fun openapi(key: String, value: String): UnitAnnotation =
        UnitAnnotation("openapi", key, SchemataText.string(value))

    private companion object {
        val ROOT = Pointer("#")
        const val DEFAULT_VERSION = "1.0.0"
        const val UNWRITTEN = "Schemata writes the authorization code and client credentials flows"
        // the target's `@openapi(server)` rule: an absolute URL or a path
        val SERVER = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+|/\\S*")
        // the target's rule for a scheme URL; a URL's scheme is case-insensitive
        val URL = Regex("(?i:https?)://[^\\s/?#]+\\S*")
    }
}
