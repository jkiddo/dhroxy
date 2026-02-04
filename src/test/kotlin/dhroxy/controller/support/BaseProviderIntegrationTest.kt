package dhroxy.controller.support

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import dhroxy.service.AppointmentService
import dhroxy.service.ImagingService
import dhroxy.service.MedicationCardService
import dhroxy.service.MedicationOverviewService
import dhroxy.service.ObservationService
import dhroxy.service.OrganizationService
import dhroxy.service.PatientService
import dhroxy.service.PatientSummaryService
import dhroxy.service.VaccinationService
import dhroxy.service.ConditionService
import dhroxy.service.EncounterService
import dhroxy.service.DocumentReferenceService
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Resource
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ProviderTestConfig::class)
abstract class BaseProviderIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    protected lateinit var client: IGenericClient

    @Autowired protected lateinit var observationService: ObservationService
    @Autowired protected lateinit var medicationOverviewService: MedicationOverviewService
    @Autowired protected lateinit var vaccinationService: VaccinationService
    @Autowired protected lateinit var imagingService: ImagingService
    @Autowired protected lateinit var medicationCardService: MedicationCardService
    @Autowired protected lateinit var patientService: PatientService
    @Autowired protected lateinit var patientSummaryService: PatientSummaryService
    @Autowired protected lateinit var organizationService: OrganizationService
    @Autowired protected lateinit var appointmentService: AppointmentService
    @Autowired protected lateinit var conditionService: ConditionService
    @Autowired protected lateinit var encounterService: EncounterService
    @Autowired protected lateinit var documentReferenceService: DocumentReferenceService

    @BeforeEach
    fun baseSetup() {
        client = FhirContext.forR4().newRestfulGenericClient("http://localhost:$port/fhir")
    }

    protected fun bundleOf(resource: Resource): Bundle =
        Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            addEntry(Bundle.BundleEntryComponent().apply { this.resource = resource })
            total = entry.size
        }
}
