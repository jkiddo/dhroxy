package dhroxy.controller.support

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
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class ProviderTestConfig {
    @Bean @Primary fun observationService(): ObservationService = mockk()
    @Bean @Primary fun medicationOverviewService(): MedicationOverviewService = mockk()
    @Bean @Primary fun vaccinationService(): VaccinationService = mockk()
    @Bean @Primary fun imagingService(): ImagingService = mockk()
    @Bean @Primary fun medicationCardService(): MedicationCardService = mockk()
    @Bean @Primary fun patientService(): PatientService = mockk()
    @Bean @Primary fun patientSummaryService(): PatientSummaryService = mockk()
    @Bean @Primary fun organizationService(): OrganizationService = mockk()
    @Bean @Primary fun appointmentService(): AppointmentService = mockk()
    @Bean @Primary fun conditionService(): ConditionService = mockk()
    @Bean @Primary fun encounterService(): EncounterService = mockk()
    @Bean @Primary fun documentReferenceService(): DocumentReferenceService = mockk()
}
