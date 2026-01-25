package dhroxy.client

import dhroxy.config.SundhedClientProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.function.Consumer

class SundhedClientHeaderTest {

    @Test
    fun `forwarded headers from incoming request are copied to outgoing`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("cookie", "user-agent"),
            fallbackHeaders = emptyMap()
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders().apply {
            add("cookie", "session=abc123")
            add("user-agent", "TestBrowser/1.0")
        }

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        assertEquals("session=abc123", outgoingHeaders.getFirst("cookie"))
        assertEquals("TestBrowser/1.0", outgoingHeaders.getFirst("user-agent"))
    }

    @Test
    fun `fallback headers are applied when forwarded headers are not present`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("cookie", "user-agent"),
            fallbackHeaders = mapOf(
                "cookie" to "fallback-cookie",
                "user-agent" to "FallbackBrowser/1.0"
            )
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders() // empty - no headers provided

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        assertEquals("fallback-cookie", outgoingHeaders.getFirst("cookie"))
        assertEquals("FallbackBrowser/1.0", outgoingHeaders.getFirst("user-agent"))
    }

    @Test
    fun `forwarded headers take precedence over fallback headers`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("cookie", "user-agent"),
            fallbackHeaders = mapOf(
                "cookie" to "fallback-cookie",
                "user-agent" to "FallbackBrowser/1.0"
            )
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders().apply {
            add("cookie", "request-cookie")
            // user-agent not provided - should use fallback
        }

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        // cookie from request takes precedence
        assertEquals("request-cookie", outgoingHeaders.getFirst("cookie"))
        // user-agent uses fallback since not in request
        assertEquals("FallbackBrowser/1.0", outgoingHeaders.getFirst("user-agent"))
    }

    @Test
    fun `default Accept header is added when not present`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("accept"),
            fallbackHeaders = emptyMap()
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders() // no accept header

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        assertEquals(listOf(MediaType.APPLICATION_JSON), outgoingHeaders.accept)
    }

    @Test
    fun `provided Accept header is not overwritten`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("accept"),
            fallbackHeaders = emptyMap()
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_XML)
        }

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        assertEquals(listOf(MediaType.APPLICATION_XML), outgoingHeaders.accept)
    }

    @Test
    fun `header names are case-insensitive`() {
        val capturedHeaders = slot<Consumer<HttpHeaders>>()
        val webClient = mockWebClient(capturedHeaders)

        val props = SundhedClientProperties(
            forwardedHeaders = listOf("Cookie", "USER-AGENT"),
            fallbackHeaders = mapOf("X-Custom-Header" to "fallback-value")
        )
        val client = SundhedClient(webClient, props)

        val incomingHeaders = HttpHeaders().apply {
            add("COOKIE", "uppercase-cookie")
        }

        runBlocking { client.fetchLabsvar(null, null, null, incomingHeaders) }

        val outgoingHeaders = HttpHeaders()
        capturedHeaders.captured.accept(outgoingHeaders)

        assertEquals("uppercase-cookie", outgoingHeaders.getFirst("cookie"))
        assertEquals("fallback-value", outgoingHeaders.getFirst("x-custom-header"))
    }

    private fun mockWebClient(capturedHeaders: io.mockk.CapturingSlot<Consumer<HttpHeaders>>): WebClient {
        val responseSpec = mockk<WebClient.ResponseSpec>()
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<Class<*>>()) } returns Mono.empty()
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<*>>()) } returns Mono.empty()

        val requestHeadersSpec = mockk<WebClient.RequestHeadersSpec<*>>()
        every { requestHeadersSpec.headers(capture(capturedHeaders)) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec

        val requestHeadersUriSpec = mockk<WebClient.RequestHeadersUriSpec<*>>()
        every { requestHeadersUriSpec.uri(any<java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>>()) } answers { requestHeadersSpec }

        val webClient = mockk<WebClient>()
        every { webClient.get() } returns requestHeadersUriSpec

        return webClient
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
