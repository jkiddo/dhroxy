package dhroxy.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Models for receiving HealthKit FHIR data from iOS devices.
 *
 * HealthKit on iOS can export health data as FHIR R4 resources via:
 * - Apple's native HKFHIRResource (WWDC20)
 * - Stanford's HealthKitOnFHIR library
 * - Microsoft's HealthKit-on-FHIR connector
 *
 * Supported FHIR resource types from HealthKit:
 * - Observation (heart rate, blood pressure, steps, etc.)
 * - DiagnosticReport (lab results from clinical records)
 * - Condition (from clinical records)
 * - MedicationStatement (from clinical records)
 * - Immunization (from clinical records)
 * - AllergyIntolerance (from clinical records)
 * - Procedure (from clinical records)
 */

/**
 * Request wrapper for submitting HealthKit FHIR data.
 * Accepts a FHIR Bundle containing health observations and clinical records.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthKitSubmission(
    @JsonProperty("deviceId")
    val deviceId: String? = null,

    @JsonProperty("appVersion")
    val appVersion: String? = null,

    @JsonProperty("submissionTime")
    val submissionTime: Instant? = null,

    @JsonProperty("patientIdentifier")
    val patientIdentifier: String? = null,

    @JsonProperty("bundle")
    val bundle: HealthKitFhirBundle? = null
)

/**
 * Simplified FHIR Bundle structure for HealthKit data.
 * The actual FHIR resources are parsed using HAPI FHIR.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthKitFhirBundle(
    @JsonProperty("resourceType")
    val resourceType: String = "Bundle",

    @JsonProperty("type")
    val type: String = "collection",

    @JsonProperty("entry")
    val entry: List<HealthKitBundleEntry> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthKitBundleEntry(
    @JsonProperty("fullUrl")
    val fullUrl: String? = null,

    @JsonProperty("resource")
    val resource: Map<String, Any?>? = null
)

/**
 * Response returned after processing HealthKit submission.
 */
data class HealthKitSubmissionResponse(
    val success: Boolean,
    val submissionId: String,
    val resourcesProcessed: Int,
    val resourcesAccepted: Int,
    val resourcesRejected: Int,
    val errors: List<HealthKitProcessingError> = emptyList(),
    val timestamp: Instant = Instant.now()
)

data class HealthKitProcessingError(
    val resourceType: String?,
    val resourceId: String?,
    val errorCode: String,
    val errorMessage: String
)

/**
 * Metadata about a stored HealthKit observation.
 */
data class HealthKitObservationMetadata(
    val id: String,
    val resourceType: String,
    val patientIdentifier: String?,
    val deviceId: String?,
    val effectiveDateTime: Instant?,
    val receivedAt: Instant = Instant.now(),
    val category: String? = null,
    val code: String? = null,
    val displayName: String? = null
)

/**
 * HealthKit-specific observation categories mapped from Apple's HKQuantityTypeIdentifier.
 */
enum class HealthKitCategory(val fhirCode: String, val displayName: String) {
    VITAL_SIGNS("vital-signs", "Vital Signs"),
    ACTIVITY("activity", "Activity"),
    LABORATORY("laboratory", "Laboratory"),
    BODY_MEASUREMENT("exam", "Physical Examination"),
    SLEEP("social-history", "Sleep Analysis"),
    NUTRITION("social-history", "Nutrition"),
    REPRODUCTIVE_HEALTH("social-history", "Reproductive Health");

    companion object {
        fun fromHealthKitType(hkType: String): HealthKitCategory {
            return when {
                hkType.contains("HeartRate", ignoreCase = true) -> VITAL_SIGNS
                hkType.contains("BloodPressure", ignoreCase = true) -> VITAL_SIGNS
                hkType.contains("RespiratoryRate", ignoreCase = true) -> VITAL_SIGNS
                hkType.contains("BodyTemperature", ignoreCase = true) -> VITAL_SIGNS
                hkType.contains("OxygenSaturation", ignoreCase = true) -> VITAL_SIGNS
                hkType.contains("Steps", ignoreCase = true) -> ACTIVITY
                hkType.contains("Distance", ignoreCase = true) -> ACTIVITY
                hkType.contains("ActiveEnergy", ignoreCase = true) -> ACTIVITY
                hkType.contains("Exercise", ignoreCase = true) -> ACTIVITY
                hkType.contains("FlightsClimbed", ignoreCase = true) -> ACTIVITY
                hkType.contains("BodyMass", ignoreCase = true) -> BODY_MEASUREMENT
                hkType.contains("Height", ignoreCase = true) -> BODY_MEASUREMENT
                hkType.contains("BMI", ignoreCase = true) -> BODY_MEASUREMENT
                hkType.contains("BodyFat", ignoreCase = true) -> BODY_MEASUREMENT
                hkType.contains("Sleep", ignoreCase = true) -> SLEEP
                hkType.contains("Blood", ignoreCase = true) -> LABORATORY
                hkType.contains("Glucose", ignoreCase = true) -> LABORATORY
                else -> VITAL_SIGNS
            }
        }
    }
}
