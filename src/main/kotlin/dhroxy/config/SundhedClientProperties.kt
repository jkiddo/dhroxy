package dhroxy.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("sundhed.client")
data class SundhedClientProperties(
    val baseUrl: String = "https://www.sundhed.dk",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(20),
    val forwardedHeaders: List<String> = listOf(
        "accept",
        "cookie",
        "conversation-uuid",
        "x-xsrf-token",
        "x-queueit-ajaxpageurl",
        "referer",
        "user-agent",
        "accept-language",
        "page-app-id",
        "dnt"
    ),
    /**
     * Optional fallback header values used when not provided by forwarded headers.
     * Map key should be lowercase header name.
     */
    val fallbackHeaders: Map<String, String> = emptyMap(),
    /**
     * Eservices identifier used for the medication card endpoint (/api/eserviceslink/{id}).
     */
    val medicationCardEservicesId: String? = null
)
