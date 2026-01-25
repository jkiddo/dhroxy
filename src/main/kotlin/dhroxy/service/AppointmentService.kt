package dhroxy.service

import dhroxy.client.SundhedClient
import dhroxy.mapper.AppointmentMapper
import org.hl7.fhir.r4.model.Bundle
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service

@Service
class AppointmentService(
    private val client: SundhedClient,
    private val mapper: AppointmentMapper
) {
    suspend fun search(
        headers: HttpHeaders,
        start: String?,
        end: String?,
        requestUrl: String
    ): Bundle {
        val payload = client.fetchAppointments(headers, start, end)
        val bundle = mapper.toAppointmentBundle(payload, requestUrl)
        return mapper.filterByDate(bundle, start, end)
    }
}
