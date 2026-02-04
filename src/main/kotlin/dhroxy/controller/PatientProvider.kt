package dhroxy.controller

import ca.uhn.fhir.rest.annotation.IdParam
import ca.uhn.fhir.rest.annotation.Operation
import ca.uhn.fhir.rest.annotation.OptionalParam
import ca.uhn.fhir.rest.annotation.Search
import ca.uhn.fhir.rest.api.server.IBundleProvider
import ca.uhn.fhir.rest.param.StringParam
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.IResourceProvider
import ca.uhn.fhir.rest.server.SimpleBundleProvider
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails
import dhroxy.mapper.PatientSummaryMapper
import dhroxy.service.PatientService
import dhroxy.service.PatientSummaryService
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

@Component
class PatientProvider(
    private val patientService: PatientService,
    private val patientSummaryService: PatientSummaryService,
    private val patientSummaryMapper: PatientSummaryMapper
) : IResourceProvider {

    override fun getResourceType(): Class<Patient> = Patient::class.java

    @Search
    fun search(
        @OptionalParam(name = Patient.SP_NAME) name: StringParam?,
        @OptionalParam(name = Patient.SP_IDENTIFIER) identifier: TokenParam?,
        details: ServletRequestDetails
    ): IBundleProvider {
        val headers = toHttpHeaders(details)
        val bundle = runBlocking {
            patientService.search(headers, name?.value, identifier?.value, requestUrl(details))
        }
        return SimpleBundleProvider(bundle.entry.mapNotNull { it.resource as? Patient })
    }

    /**
     * Implements the $summary operation on Patient to generate an International Patient Summary (IPS).
     *
     * The IPS is a FHIR Document Bundle containing a Composition resource and all referenced
     * clinical data including problems, medications, allergies, immunizations, and lab results.
     *
     * Usage: GET /fhir/Patient/{id}/$summary
     *
     * @param patientId The patient ID (format: pat-{cpr})
     * @param details The servlet request details for header extraction
     * @return A FHIR Document Bundle representing the International Patient Summary
     */
    @Operation(name = "\$summary", idempotent = true)
    fun summary(
        @IdParam patientId: IdType?,
        details: ServletRequestDetails
    ): Bundle {
        val headers = toHttpHeaders(details)
        val requestUrl = requestUrl(details)

        return runBlocking {
            val summaryData = patientSummaryService.fetchSummaryData(headers, patientId?.idPart)
            patientSummaryMapper.toIpsBundle(summaryData, requestUrl)
        }
    }

    private fun toHttpHeaders(details: ServletRequestDetails): HttpHeaders =
        HttpHeaders().apply {
            details.headers?.forEach { entry: Map.Entry<String, MutableList<String>> ->
                addAll(entry.key, entry.value)
            }
        }

    private fun requestUrl(details: ServletRequestDetails): String =
        details.servletRequest?.let { req ->
            buildString {
                append(req.requestURL.toString())
                req.queryString?.let { append("?").append(it) }
            }
        } ?: (details.requestPath ?: "")
}
