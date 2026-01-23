package dhroxy.client

import dhroxy.model.*
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Component
class SundhedClient(
    private val webClient: WebClient,
    private val props: dhroxy.config.SundhedClientProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val forwardedHeaderNames = props.forwardedHeaders.map { it.lowercase() }.toSet()
    private val staticHeaderNames = props.staticHeaders.mapKeys { it.key.lowercase() }

    suspend fun fetchLabsvar(
        fra: String?,
        til: String?,
        omraade: String?,
        incomingHeaders: HttpHeaders
    ): dhroxy.model.LabsvarResponse? {
        return webClient.get()
            .uri { builder ->
                builder.path("/api/labsvar/svaroversigt")
                    .apply {
                        if (!fra.isNullOrBlank()) queryParam("fra", fra)//"2025-06-29T00%3A00%3A00")
                        if (!til.isNullOrBlank()) queryParam("til", til)
                        if (!omraade.isNullOrBlank()) queryParam("omraade", omraade)
                    }
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("labsvar request failed with status {}", resp.statusCode())
                resp.bodyToMono<String>().defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(_root_ide_package_.dhroxy.model.LabsvarResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchEffectuatedVaccinations(incomingHeaders: HttpHeaders): List<dhroxy.model.VaccinationRecord> {
        return webClient.get()
            .uri("/app/vaccination/api/v1/effectuatedvaccinations/")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("vaccinations request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(Array<VaccinationRecord>::class.java)
            .awaitSingleOrNull()
            ?.toList()
            ?: emptyList()
    }

    suspend fun fetchVaccinationHistory(
        vaccinationId: Long,
        incomingHeaders: HttpHeaders
    ): List<VaccinationHistoryEntry> {
        return webClient.get()
            .uri("/app/vaccination/api/v1/effectuatedvaccinations/$vaccinationId/history")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("vaccination history request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(Array<VaccinationHistoryEntry>::class.java)
            .awaitSingleOrNull()
            ?.toList()
            ?: emptyList()
    }

    suspend fun fetchImagingReferral(
        id: String?,
        undersoegelsesId: String?,
        incomingHeaders: HttpHeaders
    ): ImagingReferralResponse? {
        return webClient.get()
            .uri { builder ->
                builder.path("/app/billedbeskrivelserborger/api/v1/billedbeskrivelser/henvisning/")
                    .apply {
                        if (!id.isNullOrBlank()) queryParam("Id", id)
                        if (!undersoegelsesId.isNullOrBlank()) queryParam("undersoegelsesId", undersoegelsesId)
                    }
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("imaging referral request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(ImagingReferralResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchImagingReferrals(
        incomingHeaders: HttpHeaders
    ): ImagingReferralsResponse? {
        return webClient.get()
            .uri("/app/billedbeskrivelserborger/api/v1/billedbeskrivelser/henvisninger/")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("imaging referrals list request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(ImagingReferralsResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchOrdinationOverview(incomingHeaders: HttpHeaders): OrdinationOverviewResponse? {
        return webClient.get()
            .uri("/app/medicinkort2borger/api/v1/ordinations/overview/")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("ordination overview request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(OrdinationOverviewResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchPrescriptionOverview(incomingHeaders: HttpHeaders): PrescriptionOverviewResponse? {
        return webClient.get()
            .uri("/app/medicinkort2borger/api/v1/prescriptions/overview/")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("prescription overview request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(PrescriptionOverviewResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchAppointments(
        incomingHeaders: HttpHeaders,
        fra: String? = null,
        til: String? = null
    ): AppointmentsResponse? {
        log.info("Fetching appointments with fra={}, til={}", fra, til)

        // Sundhed.dk API requires both fromDate and toDate to be non-null
        // If not provided, use default range: 1 year back to 1 year ahead
        val now = java.time.LocalDate.now()
        val defaultFrom = now.minusYears(1).toString()
        val defaultTo = now.plusYears(1).toString()

        val effectiveFrom = fra ?: defaultFrom
        val effectiveTo = til ?: defaultTo

        val requestBody = AppointmentsRequest(
            fromDate = effectiveFrom,
            toDate = effectiveTo
        )
        log.debug("Sending appointments request body: fromDate={}, toDate={}", effectiveFrom, effectiveTo)

        val requestWithBody = webClient.post()
            .uri("/app/aftalerborger/api/v1/aftaler/cpr")
            .headers { h ->
                copyForwardedHeaders(incomingHeaders, h)
                h.contentType = MediaType.APPLICATION_JSON
            }
            .bodyValue(requestBody)

        return requestWithBody
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                resp.bodyToMono(String::class.java).defaultIfEmpty("").doOnNext { body ->
                    log.warn("appointments request failed with status {} - {}", resp.statusCode(), body)
                }.then(Mono.empty())
            }
            .bodyToMono(String::class.java)
            .doOnNext { rawJson ->
                log.debug("Raw appointments response: {}", rawJson)
            }
            .map { rawJson ->
                try {
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                        .readValue(rawJson, AppointmentsResponse::class.java)
                } catch (e: Exception) {
                    log.error("Failed to parse appointments response. Raw JSON: {}", rawJson, e)
                    null
                }
            }
            .onErrorResume { error ->
                log.warn("appointments request error: {}", error.message)
                Mono.empty()
            }
            .awaitSingleOrNull()
    }

    suspend fun fetchPersonSelection(
        incomingHeaders: HttpHeaders
    ): PersonSelectionResponse? {
        return webClient.get()
            .uri("/app/personvaelgerportal/api/v1/GetPersonSelection")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("person selection request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(PersonSelectionResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchMinLaegeOrganizationId(
        incomingHeaders: HttpHeaders
    ): Int? {
        return webClient.get()
            .uri("/api/minlaegeorganization/")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("minlaegeorganization request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(MinLaegeOrganizationResponse::class.java)
            .awaitSingleOrNull()
            ?.organizationId
    }

    suspend fun fetchOrganization(
        organizationId: Int,
        incomingHeaders: HttpHeaders
    ): CoreOrganizationResponse? {
        return webClient.get()
            .uri("/api/core/organisation/{id}", organizationId)
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("core organisation request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(CoreOrganizationResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchMedicationCard(
        eservicesId: String,
        incomingHeaders: HttpHeaders
    ): List<MedicationCardEntry> {
        if (eservicesId.isBlank()) return emptyList()
        return webClient.get()
            .uri { builder ->
                builder
                    .path("/app/medicinkort2borger/api/v1/ordinations/")
                    .queryParam("orderBy", "StartDate")
                    .queryParam("sortBy", "desc")
                    .queryParam("status", "active")
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("medication card request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(Array<MedicationCardEntry>::class.java)
            .awaitSingleOrNull()
            ?.toList()
            ?: emptyList()
    }

    suspend fun fetchOrdinationDetails(
        id: String,
        incomingHeaders: HttpHeaders
    ): OrdinationDetails? {
        return webClient.get()
            .uri("/app/medicinkort2borger/api/v1/ordinations/{id}/details", id)
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("ordination details request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(OrdinationDetails::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchDiagnoser(
        incomingHeaders: HttpHeaders
    ): DiagnoserResponse? {
        return webClient.get()
            .uri("/app/diagnoserborger/api/v1/diagnoser")
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("diagnoser request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(DiagnoserResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchForloebsoversigt(
        incomingHeaders: HttpHeaders
    ): ForloebsoversigtResponse? {
        return webClient.get()
            .uri { builder ->
                builder
                    .path("/app/ejournalportalborger/api/ejournal/forloebsoversigt")
                    .queryParam("Side", 1)
                    .queryParam("Sortering", "updated")
                    .queryParam("SortDesc", true)
                    .queryParam("ItemsPerPage", 10)
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("forloebsoversigt request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(ForloebsoversigtResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchKontaktperioder(
        noegle: String,
        incomingHeaders: HttpHeaders
    ): KontaktperioderResponse? {
        val keyJson = """{"Database":null,"Noegle":"$noegle","VaerdispringNoegle":null}"""
        return webClient.get()
            .uri { builder ->
                builder.path("/app/ejournalportalborger/api/ejournal/kontaktperioder")
                    .queryParam("noegle", keyJson)
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("kontaktperioder request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(KontaktperioderResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchEpikriser(
        noegle: String,
        incomingHeaders: HttpHeaders
    ): EpikriserResponse? {
        val keyJson = """{"Database":null,"Noegle":"$noegle","VaerdispringNoegle":null}"""
        return webClient.get()
            .uri { builder ->
                builder.path("/app/ejournalportalborger/api/ejournal/epikriser")
                    .queryParam("noegle", keyJson)
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("epikriser request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(EpikriserResponse::class.java)
            .awaitSingleOrNull()
    }

    suspend fun fetchNotater(
        noegle: String,
        incomingHeaders: HttpHeaders
    ): NotaterResponse? {
        val keyJson = """{"Database":null,"Noegle":"$noegle","VaerdispringNoegle":null}"""
        return webClient.get()
            .uri { builder ->
                builder.path("/app/ejournalportalborger/api/ejournal/notater")
                    .queryParam("noegle", keyJson)
                    .build()
            }
            .headers { copyForwardedHeaders(incomingHeaders, it) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                log.warn("notater request failed with status {}", resp.statusCode())
                resp.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> ResponseStatusException(resp.statusCode(), body) }
            }
            .bodyToMono(NotaterResponse::class.java)
            .awaitSingleOrNull()
    }

    private fun copyForwardedHeaders(incoming: HttpHeaders, outgoing: HttpHeaders) {
        forwardedHeaderNames.forEach { name ->
            val values = incoming[name]
            if (!values.isNullOrEmpty()) {
                outgoing.addAll(name, values)
            }
        }
        // Static headers override incoming ones when provided
        staticHeaderNames.forEach { (name, value) ->
            outgoing.set(name, value)
        }
        if (!outgoing.containsKey(HttpHeaders.ACCEPT)) {
            outgoing.accept = listOf(MediaType.APPLICATION_JSON)
        }
    }
}
