package dhroxy.controller

import ca.uhn.fhir.context.FhirContext
import dhroxy.model.HealthKitSubmission
import dhroxy.model.HealthKitSubmissionResponse
import dhroxy.service.HealthKitService
import jakarta.servlet.http.HttpServletRequest
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST Controller for receiving HealthKit FHIR data from iOS devices.
 *
 * Provides endpoints for:
 * - Submitting HealthKit data wrapped in a custom submission format
 * - Submitting raw FHIR Bundles directly
 * - Retrieving submitted observations
 * - Checking service status
 *
 * Supports data from:
 * - Apple's native HKFHIRResource (iOS 14+)
 * - Stanford's HealthKitOnFHIR library
 * - Microsoft's HealthKit-on-FHIR connector
 *
 * @see <a href="https://github.com/StanfordBDHG/HealthKitOnFHIR">Stanford HealthKitOnFHIR</a>
 * @see <a href="https://developer.apple.com/documentation/healthkit/hkfhirresource">Apple HKFHIRResource</a>
 */
@RestController
@RequestMapping("/api/healthkit")
class HealthKitController(
    private val healthKitService: HealthKitService,
    private val fhirContext: FhirContext
) {
    private val logger = LoggerFactory.getLogger(HealthKitController::class.java)
    private val jsonParser = fhirContext.newJsonParser().setPrettyPrint(true)

    /**
     * Submit HealthKit data using the custom submission wrapper.
     *
     * Expected format:
     * ```json
     * {
     *   "deviceId": "iPhone-UUID",
     *   "appVersion": "1.0.0",
     *   "patientIdentifier": "optional-cpr-or-id",
     *   "bundle": {
     *     "resourceType": "Bundle",
     *     "type": "collection",
     *     "entry": [...]
     *   }
     * }
     * ```
     *
     * @param submission The HealthKit submission containing device metadata and FHIR Bundle
     * @return Response with processing statistics
     */
    @PostMapping(
        "/submit",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun submitHealthKitData(
        @RequestBody submission: HealthKitSubmission,
        request: HttpServletRequest
    ): ResponseEntity<HealthKitSubmissionResponse> {
        logger.info("Received HealthKit submission from device: {}, app version: {}",
            submission.deviceId, submission.appVersion)

        return try {
            val response = healthKitService.processSubmission(submission)

            if (response.success) {
                ResponseEntity.ok(response)
            } else {
                ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response)
            }
        } catch (e: Exception) {
            logger.error("Error processing HealthKit submission", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                HealthKitSubmissionResponse(
                    success = false,
                    submissionId = "",
                    resourcesProcessed = 0,
                    resourcesAccepted = 0,
                    resourcesRejected = 0,
                    errors = listOf(
                        dhroxy.model.HealthKitProcessingError(
                            resourceType = null,
                            resourceId = null,
                            errorCode = "INTERNAL_ERROR",
                            errorMessage = e.message ?: "Unknown error"
                        )
                    )
                )
            )
        }
    }

    /**
     * Submit a raw FHIR Bundle directly (standard FHIR format).
     *
     * Accepts FHIR R4 Bundle with type "collection" or "batch" containing
     * HealthKit observations and clinical records.
     *
     * @param bundleJson Raw FHIR Bundle JSON
     * @param deviceId Optional device identifier header
     * @param patientId Optional patient identifier header
     * @return FHIR OperationOutcome or Bundle response
     */
    @PostMapping(
        "/fhir",
        consumes = ["application/fhir+json", MediaType.APPLICATION_JSON_VALUE],
        produces = ["application/fhir+json", MediaType.APPLICATION_JSON_VALUE]
    )
    fun submitFhirBundle(
        @RequestBody bundleJson: String,
        @RequestHeader("X-Device-Id", required = false) deviceId: String?,
        @RequestHeader("X-Patient-Identifier", required = false) patientId: String?,
        request: HttpServletRequest
    ): ResponseEntity<String> {
        logger.info("Received FHIR Bundle from device: {}", deviceId)

        return try {
            val bundle = jsonParser.parseResource(Bundle::class.java, bundleJson)

            if (bundle.type != Bundle.BundleType.COLLECTION &&
                bundle.type != Bundle.BundleType.BATCH &&
                bundle.type != Bundle.BundleType.TRANSACTION) {
                return ResponseEntity.badRequest().body(
                    createOperationOutcome(
                        OperationOutcome.IssueSeverity.ERROR,
                        "INVALID_BUNDLE_TYPE",
                        "Bundle type must be 'collection', 'batch', or 'transaction'"
                    )
                )
            }

            val response = healthKitService.processBundle(bundle, deviceId, patientId)

            val outcome = createSuccessOutcome(response)
            ResponseEntity.status(if (response.success) HttpStatus.OK else HttpStatus.PARTIAL_CONTENT)
                .body(outcome)

        } catch (e: ca.uhn.fhir.parser.DataFormatException) {
            logger.warn("Invalid FHIR format: {}", e.message)
            ResponseEntity.badRequest().body(
                createOperationOutcome(
                    OperationOutcome.IssueSeverity.ERROR,
                    "INVALID_FHIR",
                    "Invalid FHIR format: ${e.message}"
                )
            )
        } catch (e: Exception) {
            logger.error("Error processing FHIR Bundle", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                createOperationOutcome(
                    OperationOutcome.IssueSeverity.FATAL,
                    "INTERNAL_ERROR",
                    "Internal server error: ${e.message}"
                )
            )
        }
    }

    /**
     * Retrieve all submitted HealthKit observations as a FHIR Bundle.
     *
     * @param patientId Optional filter by patient identifier
     * @return FHIR Bundle containing observations
     */
    @GetMapping(
        "/observations",
        produces = ["application/fhir+json", MediaType.APPLICATION_JSON_VALUE]
    )
    fun getObservations(
        @RequestParam("patient", required = false) patientId: String?,
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val requestUrl = buildRequestUrl(request)

        return try {
            val bundle = if (patientId != null) {
                val observations = healthKitService.getObservationsForPatient(patientId)
                Bundle().apply {
                    type = Bundle.BundleType.SEARCHSET
                    link = listOf(Bundle.BundleLinkComponent().apply {
                        relation = "self"
                        url = requestUrl
                    })
                    observations.forEach { obs ->
                        addEntry(Bundle.BundleEntryComponent().apply {
                            fullUrl = "urn:uuid:${obs.idElement?.idPart}"
                            resource = obs
                        })
                    }
                    total = entry.size
                }
            } else {
                healthKitService.getAllObservationsBundle(requestUrl)
            }

            ResponseEntity.ok(jsonParser.encodeResourceToString(bundle))
        } catch (e: Exception) {
            logger.error("Error retrieving observations", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                createOperationOutcome(
                    OperationOutcome.IssueSeverity.ERROR,
                    "RETRIEVAL_ERROR",
                    "Error retrieving observations: ${e.message}"
                )
            )
        }
    }

    /**
     * Get service status and statistics.
     */
    @GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(): ResponseEntity<Map<String, Any>> {
        val counts = healthKitService.getResourceCounts()
        return ResponseEntity.ok(mapOf(
            "status" to "operational",
            "resourceCounts" to counts,
            "totalResources" to counts.values.sum(),
            "supportedResourceTypes" to listOf(
                "Observation",
                "DiagnosticReport",
                "Condition",
                "MedicationStatement",
                "Immunization",
                "AllergyIntolerance",
                "Procedure"
            )
        ))
    }

    /**
     * Get capability statement for this endpoint.
     */
    @GetMapping(
        "/metadata",
        produces = ["application/fhir+json", MediaType.APPLICATION_JSON_VALUE]
    )
    fun getMetadata(): ResponseEntity<String> {
        val capabilityStatement = org.hl7.fhir.r4.model.CapabilityStatement().apply {
            status = org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE
            kind = org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind.INSTANCE
            fhirVersion = org.hl7.fhir.r4.model.Enumerations.FHIRVersion._4_0_1
            format = listOf(
                org.hl7.fhir.r4.model.CodeType("application/fhir+json"),
                org.hl7.fhir.r4.model.CodeType("application/json")
            )
            description = "HealthKit FHIR Data Receiver - Accepts health data from iOS HealthKit"
            name = "HealthKitFHIRReceiver"
            title = "HealthKit FHIR Data Receiver"

            rest = listOf(
                org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent().apply {
                    mode = org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode.SERVER
                    resource = listOf(
                        org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent().apply {
                            type = "Observation"
                            interaction = listOf(
                                org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent().apply {
                                    code = org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.CREATE
                                },
                                org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent().apply {
                                    code = org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE
                                }
                            )
                        },
                        org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent().apply {
                            type = "Bundle"
                            interaction = listOf(
                                org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent().apply {
                                    code = org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.CREATE
                                }
                            )
                        }
                    )
                }
            )
        }

        return ResponseEntity.ok(jsonParser.encodeResourceToString(capabilityStatement))
    }

    private fun createOperationOutcome(
        severity: OperationOutcome.IssueSeverity,
        code: String,
        diagnostics: String
    ): String {
        val outcome = OperationOutcome().apply {
            addIssue().apply {
                this.severity = severity
                this.code = OperationOutcome.IssueType.PROCESSING
                this.diagnostics = diagnostics
                details = org.hl7.fhir.r4.model.CodeableConcept().apply {
                    addCoding().apply {
                        system = "https://dhroxy.dk/healthkit/errors"
                        this.code = code
                    }
                }
            }
        }
        return jsonParser.encodeResourceToString(outcome)
    }

    private fun createSuccessOutcome(response: HealthKitSubmissionResponse): String {
        val outcome = OperationOutcome().apply {
            addIssue().apply {
                severity = if (response.success)
                    OperationOutcome.IssueSeverity.INFORMATION
                else
                    OperationOutcome.IssueSeverity.WARNING

                code = OperationOutcome.IssueType.INFORMATIONAL
                diagnostics = "Processed ${response.resourcesProcessed} resources: " +
                        "${response.resourcesAccepted} accepted, ${response.resourcesRejected} rejected"

                details = org.hl7.fhir.r4.model.CodeableConcept().apply {
                    addCoding().apply {
                        system = "https://dhroxy.dk/healthkit/submission"
                        this.code = response.submissionId
                        display = "Submission ID"
                    }
                }
            }

            // Add individual errors as separate issues
            response.errors.forEach { error ->
                addIssue().apply {
                    severity = OperationOutcome.IssueSeverity.ERROR
                    code = OperationOutcome.IssueType.PROCESSING
                    diagnostics = "${error.resourceType ?: "Unknown"}: ${error.errorMessage}"
                    details = org.hl7.fhir.r4.model.CodeableConcept().apply {
                        addCoding().apply {
                            system = "https://dhroxy.dk/healthkit/errors"
                            this.code = error.errorCode
                        }
                    }
                }
            }
        }
        return jsonParser.encodeResourceToString(outcome)
    }

    private fun buildRequestUrl(request: HttpServletRequest): String {
        return buildString {
            append(request.requestURL.toString())
            request.queryString?.let { append("?").append(it) }
        }
    }
}
