package com.sporez.actualist.core.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class CustomHeaderException(message: String) : Exception(message)

/** One configured header; its value is never printed (it is often a proxy credential). */
class CustomHeader(val name: String, val value: String) {
    override fun toString(): String = "CustomHeader(<redacted>)"
    override fun equals(other: Any?): Boolean = other is CustomHeader && other.name == name && other.value == value
    override fun hashCode(): Int = name.hashCode() * 31 + value.hashCode()
}

/**
 * Custom request headers for an Actual server behind an access proxy, as Actualist's
 * `CustomHTTPHeaders` validates and applies them: header tokens only, no control characters in
 * values, unique names, never a header the app or transport controls, and sent only to the
 * origin (scheme, host, port) they were saved for.
 */
class EndpointHeaders(serverUrl: String, headers: List<CustomHeader>) {
    val origin: Triple<String, String, Int>
    val headers: List<CustomHeader> = validate(headers)

    init {
        val url = serverUrl.toHttpUrlOrNull() ?: throw CustomHeaderException("Enter a valid server address first.")
        origin = originOf(url)
    }

    fun appliesTo(url: HttpUrl): Boolean = originOf(url) == origin

    companion object {
        val reservedNames = setOf(
            "x-actual-token", "x-actual-file-id", "accept", "content-type", "content-length",
            "host", "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authenticate", "proxy-authorization",
        )
        private const val PUNCTUATION = "!#$%&'*+-.^_`|~"

        private fun originOf(url: HttpUrl) = Triple(url.scheme, url.host.lowercase(), url.port)

        fun validate(headers: List<CustomHeader>): List<CustomHeader> {
            val names = HashSet<String>()
            return headers.map { header ->
                val name = header.name.trim()
                if (name.isEmpty() || !name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in PUNCTUATION }) {
                    throw CustomHeaderException("Enter a valid HTTP header name using letters, numbers, or HTTP token punctuation.")
                }
                val lowered = name.lowercase()
                if (lowered in reservedNames) throw CustomHeaderException("This header is controlled by the app or the network transport.")
                if (!names.add(lowered)) throw CustomHeaderException("Each header name must be unique, regardless of capitalization.")
                if (header.value.any { Character.isISOControl(it) }) throw CustomHeaderException("Header values cannot contain line breaks or control characters.")
                CustomHeader(name, header.value)
            }
        }

        /** `Name: value` per line; blank lines are ignored. */
        fun parse(text: String): List<CustomHeader> = text.lines().filter { it.isNotBlank() }.map { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw CustomHeaderException("Write each header as Name: value, one per line.")
            CustomHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }

        fun format(headers: List<CustomHeader>): String = headers.joinToString("\n") { "${it.name}: ${it.value}" }
    }
}

/** Adds the configured headers to requests for their origin only (these clients never follow redirects). */
class CustomHeaderInterceptor(private val endpoint: EndpointHeaders) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!endpoint.appliesTo(request.url)) return chain.proceed(request)
        val builder = request.newBuilder()
        for (header in endpoint.headers) builder.header(header.name, header.value)
        return chain.proceed(builder.build())
    }
}

/** The default client plus the configured headers for their server. */
fun httpClientWithHeaders(endpoint: EndpointHeaders?): OkHttpClient =
    if (endpoint == null || endpoint.headers.isEmpty()) ActualServerClient.defaultHttpClient()
    else ActualServerClient.defaultHttpClient().newBuilder().addInterceptor(CustomHeaderInterceptor(endpoint)).build()
