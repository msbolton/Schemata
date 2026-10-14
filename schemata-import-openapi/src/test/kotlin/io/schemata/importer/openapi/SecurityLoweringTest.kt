package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityLoweringTest {
    private fun importYaml(text: String): ImportResult =
        OpenApiImporter.import(
            listOf(ImportInput("shop.yaml", text.trimIndent(), relative = "shop.yaml"))
        )

    private fun text(result: ImportResult): String =
        result.files.single { it.path == "shop.schemata" }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `bearer basic and api key schemes are schema line keys and the document security is auth`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security:
                  - BearerAuth: []
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    BearerAuth: {type: http, scheme: bearer, bearerFormat: JWT}
                    ApiKeyAuth: {type: apiKey, in: header, name: X-API-Key}
                    Basic: {type: http, scheme: basic}
                """
            )
        assertEquals(
            """
            schema shop @openapi(bearer: "JWT") @openapi(basic) @openapi(api_key: "header X-API-Key") @openapi(auth: bearer)

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `oauth2 authorization code and openid schemes keep their urls and the scopes`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security:
                  - OAuth: [read, write]
                  - Oidc: [read, write]
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    OAuth:
                      type: oauth2
                      flows:
                        authorizationCode:
                          authorizationUrl: https://auth.example.com/authorize
                          tokenUrl: https://auth.example.com/token
                          scopes: {read: '', write: ''}
                    Oidc: {type: openIdConnect, openIdConnectUrl: 'https://auth.example.com/.well-known/openid-configuration'}
                """
            )
        assertEquals(
            """
            schema shop @openapi(oauth2_token_url: "https://auth.example.com/token") @openapi(oauth2_authorization_url: "https://auth.example.com/authorize") @openapi(openid: "https://auth.example.com/.well-known/openid-configuration") @openapi(auth: (oauth2, openid)) @openapi(scopes: "read write")

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a requirement every operation of a service shares moves to it and another differing one stays`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security:
                  - BearerAuth: []
                tags:
                  - name: Admin
                  - name: Public
                paths:
                  /admin/users:
                    get:
                      tags: [Admin]
                      operationId: Admin_users
                      security: [{OAuth: [admin]}]
                      responses: {'204': {description: No content}}
                    delete:
                      tags: [Admin]
                      operationId: Admin_purge
                      security: [{OAuth: [admin]}]
                      responses: {'204': {description: No content}}
                  /health:
                    get:
                      tags: [Public]
                      operationId: Public_health
                      security: []
                      responses: {'204': {description: No content}}
                  /catalog:
                    get:
                      tags: [Public]
                      operationId: Public_catalog
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    BearerAuth: {type: http, scheme: bearer}
                    OAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes: {admin: ''}
                """
            )
        assertEquals(
            """
            schema shop @openapi(bearer) @openapi(oauth2_token_url: "https://auth.example.com/token") @openapi(auth: bearer)

            @openapi(auth: oauth2)
            @openapi(scopes: "admin")
            service Admin {
              #1 users()  get "/admin/users"
              #2 purge()  delete "/admin/users"
            }

            service Public {
              @openapi(auth: none) #1 health()  get "/health"
              #2 catalog()  get "/catalog"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `schemes and requirements schemata cannot say are noted and the first scheme of a kind kept`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security:
                  - {BearerAuth: [], ApiKeyAuth: []}
                paths:
                  /a:
                    get:
                      operationId: Shop_a
                      security: [{Second: []}, {}]
                      responses: {'204': {description: No content}}
                  /b:
                    get:
                      operationId: Shop_b
                      security: [{Nope: []}, {BearerAuth: [admin]}]
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    BearerAuth: {type: http, scheme: bearer}
                    Second: {type: http, scheme: bearer}
                    ApiKeyAuth: {type: apiKey, in: query, name: key}
                    Legacy:
                      type: oauth2
                      flows:
                        implicit: {authorizationUrl: 'https://auth.example.com/authorize', scopes: {}}
                        password: {tokenUrl: 'https://auth.example.com/token', scopes: {}}
                    Digest: {type: http, scheme: digest}
                """
            )
        assertEquals(
            """
            schema shop @openapi(bearer) @openapi(api_key: "query key") @openapi(auth: bearer)

            service Shop {
              #1 a()  get "/a"
              #2 b()  get "/b"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/components/securitySchemes/Second: scheme 'Second' dropped; scheme 'BearerAuth' is already the bearer scheme",
                "SCH2405 shop.yaml: #/components/securitySchemes/Legacy/flows/implicit: implicit flow of scheme 'Legacy' dropped; Schemata writes the authorization code and client credentials flows",
                "SCH2405 shop.yaml: #/components/securitySchemes/Legacy/flows/password: password flow of scheme 'Legacy' dropped; Schemata writes the authorization code and client credentials flows",
                "SCH2405 shop.yaml: #/components/securitySchemes/Legacy: scheme 'Legacy' dropped; it has no authorization code or client credentials flow",
                "SCH2405 shop.yaml: #/components/securitySchemes/Digest: scheme 'Digest' dropped; Schemata has no http digest scheme",
                "SCH2405 shop.yaml: #/security/0: requirement names 'BearerAuth', 'ApiKeyAuth' together, which Schemata cannot say; only 'BearerAuth' is kept",
                "SCH2405 shop.yaml: #/paths/~1a/get/security/1: empty requirement dropped; it admits a call without credentials, which Schemata cannot say beside a scheme",
                "SCH2405 shop.yaml: #/paths/~1b/get/security/0: requirement names 'Nope', which is not a scheme Schemata keeps; dropped",
                "SCH2405 shop.yaml: #/paths/~1b/get/security/1: scopes of 'BearerAuth' dropped; only oauth2 and openid requirements carry scopes",
            ),
            messages(r),
        )
    }

    @Test
    fun `the version description and first server are the schema line and the title is noted`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info:
                  title: Shop API
                  version: 2.1.0
                  description: The shop.
                servers:
                  - url: https://api.example.com/v2
                  - url: https://staging.example.com/v2
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            /// The shop.
            schema shop @openapi(version: "2.1.0") @openapi(server: "https://api.example.com/v2")

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/info/title: title 'Shop API' dropped; the regenerated title is the schema name 'shop'",
                "SCH2405 shop.yaml: #/servers/1: server 'https://staging.example.com/v2' dropped; only the first server is kept",
            ),
            messages(r),
        )
    }

    @Test
    fun `scopes that differ between schemes or carry descriptions are noted`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security:
                  - OAuth: [read]
                  - Oidc: [profile]
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    OAuth:
                      type: oauth2
                      flows:
                        authorizationCode:
                          authorizationUrl: https://auth.example.com/authorize
                          tokenUrl: https://auth.example.com/token
                          scopes: {read: Read access, write: Write access}
                    Oidc: {type: openIdConnect, openIdConnectUrl: 'https://auth.example.com/.well-known/openid-configuration'}
                """
            )
        assertEquals(
            """
            schema shop @openapi(oauth2_token_url: "https://auth.example.com/token") @openapi(oauth2_authorization_url: "https://auth.example.com/authorize") @openapi(openid: "https://auth.example.com/.well-known/openid-configuration") @openapi(auth: (oauth2, openid)) @openapi(scopes: "read")

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/security/1: scopes of 'Oidc' differ from those of 'OAuth'; the requirement keeps those of 'OAuth'",
                "SCH2405 shop.yaml: #/components/securitySchemes/OAuth/flows/authorizationCode/scopes: scopes of scheme 'OAuth' dropped; the regenerated scheme lists only the scopes its requirements name, without descriptions",
            ),
            messages(r),
        )
    }

    @Test
    fun `a server or scheme the target would refuse is dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                servers:
                  - url: api.example.com
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    OAuth:
                      type: oauth2
                      flows:
                        clientCredentials: {tokenUrl: /token, scopes: {}}
                    Key: {type: apiKey, in: header, name: X Key}
                """
            )
        assertEquals(
            """
            schema shop

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/servers/0: server 'api.example.com' dropped; it is not an absolute URL or a path",
                "SCH2405 shop.yaml: #/components/securitySchemes/OAuth: scheme 'OAuth' dropped; its URL '/token' is not an absolute http or https URL",
                "SCH2405 shop.yaml: #/components/securitySchemes/Key: scheme 'Key' dropped; its name 'X Key' holds whitespace",
            ),
            messages(r),
        )
    }

    @Test
    fun `an empty document security is auth none`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                security: []
                paths:
                  /admin:
                    get:
                      operationId: Shop_admin
                      security: [{OAuth: [admin]}]
                      responses: {'204': {description: No content}}
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    OAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes: {admin: ''}
                """
            )
        assertEquals(
            """
            schema shop @openapi(oauth2_token_url: "https://auth.example.com/token") @openapi(auth: none)

            service Shop {
              @openapi(auth: oauth2)
              @openapi(scopes: "admin")
              #1 admin()  get "/admin"
              #2 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an api key with an empty name is dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  securitySchemes:
                    Key: {type: apiKey, in: header, name: ''}
                """
            )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/components/securitySchemes/Key: scheme 'Key' dropped; its name is empty"
            ),
            messages(r),
        )
    }
}
