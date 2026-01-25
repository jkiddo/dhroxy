package dhroxy.service

import dhroxy.client.SundhedClient
import dhroxy.mapper.ConditionMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.hl7.fhir.r4.model.Bundle
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service

@Service
class ConditionService(
    private val client: SundhedClient,
    private val mapper: ConditionMapper
) {
    suspend fun search(headers: HttpHeaders, requestUrl: String): Bundle = coroutineScope {
        val forloebDeferred = async { client.fetchForloebsoversigt(headers) }
        val diagnoserDeferred = async { client.fetchDiagnoser(headers) }

        val forloeb = forloebDeferred.await()
        val diagnoser = diagnoserDeferred.await()

        mapper.toBundle(forloeb, diagnoser, requestUrl)
    }
}
