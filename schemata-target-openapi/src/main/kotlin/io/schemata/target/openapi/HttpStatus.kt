package io.schemata.target.openapi

/** The reason phrases a response without a body is described by. */
internal object HttpStatus {
    /**
     * The phrase RFC 9110 or the IANA HTTP status code registry gives [code]; `Error` for `default`
     * (null) and for a code neither registers, such as 418, which IANA lists as unused.
     */
    fun reason(code: Int?): String = code?.let { phrases[it] } ?: "Error"

    private val phrases: Map<Int, String> =
        mapOf(
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            204 to "No Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            402 to "Payment Required",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            407 to "Proxy Authentication Required",
            408 to "Request Timeout",
            409 to "Conflict",
            410 to "Gone",
            411 to "Length Required",
            412 to "Precondition Failed",
            413 to "Content Too Large",
            414 to "URI Too Long",
            415 to "Unsupported Media Type",
            416 to "Range Not Satisfiable",
            417 to "Expectation Failed",
            421 to "Misdirected Request",
            422 to "Unprocessable Content",
            423 to "Locked",
            424 to "Failed Dependency",
            425 to "Too Early",
            426 to "Upgrade Required",
            428 to "Precondition Required",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            451 to "Unavailable For Legal Reasons",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported",
            506 to "Variant Also Negotiates",
            507 to "Insufficient Storage",
            508 to "Loop Detected",
            510 to "Not Extended",
            511 to "Network Authentication Required",
        )
}
