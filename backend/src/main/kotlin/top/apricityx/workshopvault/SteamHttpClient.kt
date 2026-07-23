package top.apricityx.workshopvault

import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Component
class SteamHttpClient(properties: WorkshopProperties) {
    val client: HttpClient = HttpClient.newBuilder().also { builder ->
        parseProxy(properties.upstreamHttpProxy)?.let { proxy -> builder.proxy(proxy) }
    }
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun parseProxy(value: String): ProxySelector? {
        if (value.isBlank()) {
            return null
        }
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("UPSTREAM_HTTP_PROXY is not a valid URI")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.port !in 1..65535) {
            throw IllegalStateException("UPSTREAM_HTTP_PROXY must be an http(s) proxy with host and port")
        }
        return ProxySelector.of(InetSocketAddress(uri.host, uri.port))
    }
}
