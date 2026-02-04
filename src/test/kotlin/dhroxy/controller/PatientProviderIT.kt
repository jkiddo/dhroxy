package dhroxy.controller

import dhroxy.controller.support.BaseProviderIntegrationTest
import dhroxy.model.*
import dhroxy.service.PatientSummaryData
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Composition
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatientProviderIT : BaseProviderIntegrationTest() {
    @Test
    fun `patient search returns result`() = runBlocking {
        coEvery { patientService.search(any(), any(), any(), any()) } returns
            Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                total = 3
                addLink(Bundle.BundleLinkComponent().apply {
                    relation = "self"
                    url = "http://localhost:8080/fhir/Patient"
                })
                addEntry(Bundle.BundleEntryComponent().apply {
                    fullUrl = "http://localhost:8080/fhir/Patient/pat-0906817173"
                    resource = Patient().apply {
                        id = "pat-0906817173"
                        addIdentifier().apply { system = "urn:dk:cpr"; value = "0906817173" }
                        addName().apply {
                            family = "Jørgensen"
                            given = listOf(org.hl7.fhir.r4.model.StringType("Jens"), org.hl7.fhir.r4.model.StringType("Kristian"))
                        }
                        addExtension("https://www.sundhed.dk/fhir/StructureDefinition/relationType", org.hl7.fhir.r4.model.CodeType("MigSelv"))
                    }
                })
                addEntry(Bundle.BundleEntryComponent().apply {
                    fullUrl = "http://localhost:8080/fhir/Patient/pat-0707173333"
                    resource = Patient().apply {
                        id = "pat-0707173333"
                        addIdentifier().apply { system = "urn:dk:cpr"; value = "0707173333" }
                        addName().apply {
                            family = "Jørgensen"
                            given = listOf(org.hl7.fhir.r4.model.StringType("Søren"), org.hl7.fhir.r4.model.StringType("Isaksen"))
                        }
                        addExtension("https://www.sundhed.dk/fhir/StructureDefinition/relationType", org.hl7.fhir.r4.model.CodeType("Foraelder"))
                    }
                })
                addEntry(Bundle.BundleEntryComponent().apply {
                    fullUrl = "http://localhost:8080/fhir/Patient/pat-1207131111"
                    resource = Patient().apply {
                        id = "pat-1207131111"
                        addIdentifier().apply { system = "urn:dk:cpr"; value = "1207131111" }
                        addName().apply {
                            family = "Jørgensen"
                            given = listOf(org.hl7.fhir.r4.model.StringType("Benny"), org.hl7.fhir.r4.model.StringType("Isaksen"))
                        }
                        addExtension("https://www.sundhed.dk/fhir/StructureDefinition/relationType", org.hl7.fhir.r4.model.CodeType("Foraelder"))
                    }
                })
            }

        // GET /fhir/Patient?identifier=urn:dk:cpr|0906817173
        val bundle = client.search<Bundle>()
            .forResource(Patient::class.java)
            .where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:dk:cpr", "0906817173"))
            .returnBundle(Bundle::class.java)
            .execute()

        assertEquals(3, bundle.entry.size)
        assertEquals("0906817173", (bundle.entry[0].resource as Patient).identifierFirstRep.value)
        assertEquals("0707173333", (bundle.entry[1].resource as Patient).identifierFirstRep.value)
        assertEquals("1207131111", (bundle.entry[2].resource as Patient).identifierFirstRep.value)
    }

    @Test
    fun `patient summary operation returns IPS document bundle`() = runBlocking {
        val testCpr = "0101837173"
        val summaryData = PatientSummaryData(
            patient = PersonDelegationData(
                id = "1",
                cpr = testCpr,
                name = "Jens Jorgensen",
                relationType = "MigSelv"
            ),
            conditions = DiagnoserResponse(
                diagnoser = listOf(
                    DiagnoseEntry(
                        diagnoseKode = "E11",
                        diagnoseNavn = "Type 2 diabetes",
                        datoFra = "2020-01-15T00:00:00+01:00",
                        datoTil = null,
                        type = "Aktionsdiagnose"
                    )
                )
            ),
            forloeb = null,
            medications = listOf(
                MedicationCardEntry(
                    ordinationId = "ord-123",
                    drugMedication = "Metformin 500mg",
                    activeSubstance = "Metformin",
                    dosage = "1 tablet twice daily",
                    cause = "Type 2 diabetes",
                    startDate = "2020-01-20T00:00:00+01:00",
                    endDate = null,
                    dosageEndDate = null,
                    status = MedicationCardStatus(enumStr = "active")
                )
            ),
            immunizations = listOf(
                VaccinationRecord(
                    vaccinationIdentifier = 12345L,
                    vaccine = "COVID-19 vaccine",
                    effectuatedDateTime = "2021-03-15T10:00:00+01:00",
                    effectuatedBy = "Region Hovedstaden",
                    activeStatus = true,
                    negativeConsent = false,
                    selfCreated = false,
                    coverageDuration = "1 year"
                )
            ),
            observations = null,
            cpr = testCpr
        )

        coEvery { patientSummaryService.fetchSummaryData(any(), any()) } returns summaryData

        // GET /fhir/Patient/pat-0101837173/$summary
        val bundle = client.operation()
            .onInstance(IdType("Patient", "pat-$testCpr"))
            .named("\$summary")
            .withNoParameters(org.hl7.fhir.r4.model.Parameters::class.java)
            .returnResourceType(Bundle::class.java)
            .execute()

        // Verify it's a document bundle
        assertEquals(Bundle.BundleType.DOCUMENT, bundle.type)
        assertNotNull(bundle.timestamp)

        // Verify first entry is a Composition
        assertTrue(bundle.entry.isNotEmpty(), "Bundle should have entries")
        val firstResource = bundle.entryFirstRep.resource
        assertTrue(firstResource is Composition, "First entry should be Composition")

        val composition = firstResource as Composition
        assertEquals(Composition.CompositionStatus.FINAL, composition.status)
        assertEquals("International Patient Summary", composition.title)

        // Verify composition has required sections
        val sectionCodes = composition.section.map { it.code.codingFirstRep.code }
        assertTrue(sectionCodes.contains("11450-4"), "Should have Problems section")
        assertTrue(sectionCodes.contains("10160-0"), "Should have Medications section")
        assertTrue(sectionCodes.contains("48765-2"), "Should have Allergies section")
        assertTrue(sectionCodes.contains("11369-6"), "Should have Immunizations section")

        // Verify patient is in the bundle
        val patients = bundle.entry.mapNotNull { it.resource as? Patient }
        assertTrue(patients.isNotEmpty(), "Bundle should contain Patient resource")
        assertEquals(testCpr, patients.first().identifierFirstRep.value)
    }

    @Test
    fun `patient summary with empty data returns valid IPS with no-known entries`() = runBlocking {
        val testCpr = "0202838383"
        val emptySummaryData = PatientSummaryData(
            patient = PersonDelegationData(
                id = "2",
                cpr = testCpr,
                name = "Test Patient",
                relationType = "MigSelv"
            ),
            conditions = null,
            forloeb = null,
            medications = emptyList(),
            immunizations = emptyList(),
            observations = null,
            cpr = testCpr
        )

        coEvery { patientSummaryService.fetchSummaryData(any(), any()) } returns emptySummaryData

        val bundle = client.operation()
            .onInstance(IdType("Patient", "pat-$testCpr"))
            .named("\$summary")
            .withNoParameters(org.hl7.fhir.r4.model.Parameters::class.java)
            .returnResourceType(Bundle::class.java)
            .execute()

        assertEquals(Bundle.BundleType.DOCUMENT, bundle.type)

        // Should still have Composition
        val composition = bundle.entryFirstRep.resource as Composition
        assertNotNull(composition)

        // Should have no-known-problems condition
        val conditions = bundle.entry.mapNotNull { it.resource as? org.hl7.fhir.r4.model.Condition }
        assertTrue(conditions.any {
            it.code.codingFirstRep.code == "no-known-problems"
        }, "Should have no-known-problems condition")

        // Should have no-known-medications statement
        val medications = bundle.entry.mapNotNull { it.resource as? org.hl7.fhir.r4.model.MedicationStatement }
        assertTrue(medications.any {
            it.medicationCodeableConcept.codingFirstRep.code == "no-known-medications"
        }, "Should have no-known-medications entry")
    }
}
