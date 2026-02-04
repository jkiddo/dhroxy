package dhroxy.service

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import dhroxy.model.HealthKitObservationMetadata
import dhroxy.model.HealthKitProcessingError
import dhroxy.model.HealthKitSubmission
import dhroxy.model.HealthKitSubmissionResponse
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for processing HealthKit FHIR data submissions from iOS devices.
 *
 * Handles incoming FHIR Bundles containing health observations and clinical records
 * exported via Apple's HealthKit, Stanford's HealthKitOnFHIR, or Microsoft's HealthKit-on-FHIR.
 *
 * Supported resource types:
 * - Observation (vital signs, activity, body measurements)
 * - DiagnosticReport (lab results)
 * - Condition (diagnoses)
 * - MedicationStatement (medications)
 * - Immunization (vaccinations)
 * - AllergyIntolerance (allergies)
 * - Procedure (procedures)
 */
@Service
class HealthKitService(
    private val fhirContext: FhirContext
) {
    private val logger = LoggerFactory.getLogger(HealthKitService::class.java)
    private val jsonParser: IParser = fhirContext.newJsonParser().setPrettyPrint(false)

    // In-memory storage for received observations (replace with persistent storage in production)
    private val observationStore = ConcurrentHashMap<String, IBaseResource>()
    private val metadataStore = ConcurrentHashMap<String, HealthKitObservationMetadata>()

    companion object {
        private val SUPPORTED_RESOURCE_TYPES = setOf(
            "Observation",
            "DiagnosticReport",
            "Condition",
            "MedicationStatement",
            "Immunization",
            "AllergyIntolerance",
            "Procedure",
            "Patient"
        )
    }

    /**
     * Process a HealthKit FHIR submission.
     *
     * @param submission The submission containing device info and FHIR Bundle
     * @return Response with processing statistics and any errors
     */
    fun processSubmission(submission: HealthKitSubmission): HealthKitSubmissionResponse {
        val submissionId = UUID.randomUUID().toString()
        val errors = mutableListOf<HealthKitProcessingError>()
        var processed = 0
        var accepted = 0
        var rejected = 0

        logger.info("Processing HealthKit submission {} from device {}", submissionId, submission.deviceId)

        submission.bundle?.entry?.forEach { entry ->
            processed++
            try {
                val resourceMap = entry.resource ?: run {
                    rejected++
                    errors.add(HealthKitProcessingError(
                        resourceType = null,
                        resourceId = null,
                        errorCode = "EMPTY_RESOURCE",
                        errorMessage = "Entry contains no resource"
                    ))
                    return@forEach
                }

                val resourceType = resourceMap["resourceType"]?.toString()
                if (resourceType == null || resourceType !in SUPPORTED_RESOURCE_TYPES) {
                    rejected++
                    errors.add(HealthKitProcessingError(
                        resourceType = resourceType,
                        resourceId = resourceMap["id"]?.toString(),
                        errorCode = "UNSUPPORTED_RESOURCE_TYPE",
                        errorMessage = "Resource type '$resourceType' is not supported"
                    ))
                    return@forEach
                }

                // Parse the resource using HAPI FHIR
                val resourceJson = fhirContext.newJsonParser().encodeToString(mapToBundle(resourceMap))
                    ?: throw IllegalArgumentException("Failed to encode resource")

                // Parse individual resource from map
                val resource = parseResource(resourceMap)
                if (resource != null) {
                    storeResource(resource, submission)
                    accepted++
                    logger.debug("Accepted {} resource: {}", resourceType, resource.idElement?.idPart)
                } else {
                    rejected++
                    errors.add(HealthKitProcessingError(
                        resourceType = resourceType,
                        resourceId = resourceMap["id"]?.toString(),
                        errorCode = "PARSE_ERROR",
                        errorMessage = "Failed to parse resource"
                    ))
                }
            } catch (e: Exception) {
                rejected++
                val resourceType = entry.resource?.get("resourceType")?.toString()
                errors.add(HealthKitProcessingError(
                    resourceType = resourceType,
                    resourceId = entry.resource?.get("id")?.toString(),
                    errorCode = "PROCESSING_ERROR",
                    errorMessage = e.message ?: "Unknown error"
                ))
                logger.warn("Error processing resource: {}", e.message)
            }
        }

        logger.info("Submission {} completed: {} processed, {} accepted, {} rejected",
            submissionId, processed, accepted, rejected)

        return HealthKitSubmissionResponse(
            success = rejected == 0,
            submissionId = submissionId,
            resourcesProcessed = processed,
            resourcesAccepted = accepted,
            resourcesRejected = rejected,
            errors = errors
        )
    }

    /**
     * Process a raw FHIR Bundle directly (for clients sending standard FHIR).
     */
    fun processBundle(bundle: Bundle, deviceId: String? = null, patientIdentifier: String? = null): HealthKitSubmissionResponse {
        val submissionId = UUID.randomUUID().toString()
        val errors = mutableListOf<HealthKitProcessingError>()
        var processed = 0
        var accepted = 0
        var rejected = 0

        logger.info("Processing FHIR Bundle {} with {} entries", submissionId, bundle.entry?.size ?: 0)

        bundle.entry?.forEach { entry ->
            processed++
            val resource = entry.resource
            if (resource == null) {
                rejected++
                errors.add(HealthKitProcessingError(
                    resourceType = null,
                    resourceId = null,
                    errorCode = "EMPTY_RESOURCE",
                    errorMessage = "Entry contains no resource"
                ))
                return@forEach
            }

            val resourceType = resource.fhirType()
            if (resourceType !in SUPPORTED_RESOURCE_TYPES) {
                rejected++
                errors.add(HealthKitProcessingError(
                    resourceType = resourceType,
                    resourceId = resource.idElement?.idPart,
                    errorCode = "UNSUPPORTED_RESOURCE_TYPE",
                    errorMessage = "Resource type '$resourceType' is not supported"
                ))
                return@forEach
            }

            try {
                val submission = HealthKitSubmission(
                    deviceId = deviceId,
                    patientIdentifier = patientIdentifier,
                    submissionTime = Instant.now()
                )
                storeResource(resource, submission)
                accepted++
            } catch (e: Exception) {
                rejected++
                errors.add(HealthKitProcessingError(
                    resourceType = resourceType,
                    resourceId = resource.idElement?.idPart,
                    errorCode = "STORAGE_ERROR",
                    errorMessage = e.message ?: "Failed to store resource"
                ))
            }
        }

        return HealthKitSubmissionResponse(
            success = rejected == 0,
            submissionId = submissionId,
            resourcesProcessed = processed,
            resourcesAccepted = accepted,
            resourcesRejected = rejected,
            errors = errors
        )
    }

    /**
     * Retrieve stored observations for a patient.
     */
    fun getObservationsForPatient(patientIdentifier: String): List<Observation> {
        return metadataStore.entries
            .filter { it.value.patientIdentifier == patientIdentifier }
            .mapNotNull { observationStore[it.key] as? Observation }
    }

    /**
     * Retrieve all stored observations as a FHIR Bundle.
     */
    fun getAllObservationsBundle(requestUrl: String): Bundle {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            link = listOf(Bundle.BundleLinkComponent().apply {
                relation = "self"
                url = requestUrl
            })
        }

        observationStore.values.filterIsInstance<Observation>().forEach { observation ->
            bundle.addEntry(Bundle.BundleEntryComponent().apply {
                fullUrl = "urn:uuid:${observation.idElement?.idPart ?: UUID.randomUUID()}"
                resource = observation
            })
        }

        bundle.total = bundle.entry.size
        return bundle
    }

    /**
     * Get count of stored resources by type.
     */
    fun getResourceCounts(): Map<String, Int> {
        return observationStore.values
            .groupBy { it.fhirType() }
            .mapValues { it.value.size }
    }

    private fun parseResource(resourceMap: Map<String, Any?>): IBaseResource? {
        return try {
            val json = com.google.gson.Gson().toJson(resourceMap)
            jsonParser.parseResource(json)
        } catch (e: Exception) {
            logger.warn("Failed to parse resource: {}", e.message)
            null
        }
    }

    private fun storeResource(resource: IBaseResource, submission: HealthKitSubmission) {
        val id = when (resource) {
            is Observation -> resource.idElement?.idPart ?: UUID.randomUUID().toString()
            is DiagnosticReport -> resource.idElement?.idPart ?: UUID.randomUUID().toString()
            is Condition -> resource.idElement?.idPart ?: UUID.randomUUID().toString()
            is MedicationStatement -> resource.idElement?.idPart ?: UUID.randomUUID().toString()
            is Immunization -> resource.idElement?.idPart ?: UUID.randomUUID().toString()
            else -> UUID.randomUUID().toString()
        }

        val storageKey = "${resource.fhirType()}-$id"
        observationStore[storageKey] = resource

        // Store metadata for quick lookups
        val metadata = HealthKitObservationMetadata(
            id = storageKey,
            resourceType = resource.fhirType(),
            patientIdentifier = submission.patientIdentifier,
            deviceId = submission.deviceId,
            effectiveDateTime = extractEffectiveDateTime(resource),
            category = extractCategory(resource),
            code = extractCode(resource),
            displayName = extractDisplayName(resource)
        )
        metadataStore[storageKey] = metadata
    }

    private fun extractEffectiveDateTime(resource: IBaseResource): Instant? {
        return when (resource) {
            is Observation -> {
                when (val effective = resource.effective) {
                    is DateTimeType -> effective.value?.toInstant()
                    is Period -> effective.start?.toInstant()
                    else -> null
                }
            }
            is DiagnosticReport -> resource.effectiveDateTimeType?.value?.toInstant()
            is Condition -> resource.onsetDateTimeType?.value?.toInstant()
            else -> null
        }
    }

    private fun extractCategory(resource: IBaseResource): String? {
        return when (resource) {
            is Observation -> resource.category?.firstOrNull()?.coding?.firstOrNull()?.code
            else -> null
        }
    }

    private fun extractCode(resource: IBaseResource): String? {
        return when (resource) {
            is Observation -> resource.code?.coding?.firstOrNull()?.code
            is Condition -> resource.code?.coding?.firstOrNull()?.code
            else -> null
        }
    }

    private fun extractDisplayName(resource: IBaseResource): String? {
        return when (resource) {
            is Observation -> resource.code?.text ?: resource.code?.coding?.firstOrNull()?.display
            is Condition -> resource.code?.text ?: resource.code?.coding?.firstOrNull()?.display
            else -> null
        }
    }

    private fun mapToBundle(resourceMap: Map<String, Any?>): IBaseResource? {
        return try {
            val json = com.google.gson.Gson().toJson(resourceMap)
            jsonParser.parseResource(json)
        } catch (e: Exception) {
            null
        }
    }
}
