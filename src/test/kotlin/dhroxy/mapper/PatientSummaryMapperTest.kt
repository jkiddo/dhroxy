package dhroxy.mapper

import dhroxy.model.*
import dhroxy.service.PatientSummaryData
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatientSummaryMapperTest {
    private val mapper = PatientSummaryMapper()

    @Test
    fun `creates IPS document bundle with composition as first entry`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        assertEquals(Bundle.BundleType.DOCUMENT, bundle.type)
        assertNotNull(bundle.timestamp)
        assertNotNull(bundle.identifier)

        // Composition must be the first entry
        val firstEntry = bundle.entryFirstRep.resource
        assertTrue(firstEntry is Composition, "First entry must be a Composition")
    }

    @Test
    fun `composition has required IPS sections`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val composition = bundle.entryFirstRep.resource as Composition
        assertEquals(Composition.CompositionStatus.FINAL, composition.status)
        assertEquals("International Patient Summary", composition.title)

        // Check for required sections
        val sectionCodes = composition.section.map { it.code.codingFirstRep.code }
        assertTrue(sectionCodes.contains("11450-4"), "Should have Problems section")
        assertTrue(sectionCodes.contains("10160-0"), "Should have Medications section")
        assertTrue(sectionCodes.contains("48765-2"), "Should have Allergies section")
        assertTrue(sectionCodes.contains("11369-6"), "Should have Immunizations section")
    }

    @Test
    fun `maps patient data correctly`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val patient = bundle.entry.mapNotNull { it.resource as? Patient }.firstOrNull()
        assertNotNull(patient)
        assertEquals("0101837173", patient?.identifierFirstRep?.value)
        assertEquals("Jorgensen", patient?.nameFirstRep?.family)
        assertEquals("Jens", patient?.nameFirstRep?.givenAsSingleString)
    }

    @Test
    fun `maps conditions from diagnoses`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val conditions = bundle.entry.mapNotNull { it.resource as? Condition }
        assertTrue(conditions.isNotEmpty(), "Should have at least one condition")

        val diabetesCondition = conditions.find { it.code.text == "Type 2 diabetes" }
        assertNotNull(diabetesCondition, "Should have diabetes condition")
        assertEquals("active", diabetesCondition?.clinicalStatus?.codingFirstRep?.code)
    }

    @Test
    fun `maps medications correctly`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val medications = bundle.entry.mapNotNull { it.resource as? MedicationStatement }
        assertTrue(medications.isNotEmpty(), "Should have at least one medication")

        val metformin = medications.find {
            it.medicationCodeableConcept.text?.contains("Metformin") == true
        }
        assertNotNull(metformin, "Should have Metformin medication")
        assertEquals(MedicationStatement.MedicationStatementStatus.ACTIVE, metformin?.status)
    }

    @Test
    fun `maps immunizations correctly`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val immunizations = bundle.entry.mapNotNull { it.resource as? Immunization }
        assertTrue(immunizations.isNotEmpty(), "Should have at least one immunization")

        val covidVax = immunizations.find { it.vaccineCode.text == "COVID-19 vaccine" }
        assertNotNull(covidVax, "Should have COVID-19 vaccine")
        assertEquals(Immunization.ImmunizationStatus.COMPLETED, covidVax?.status)
    }

    @Test
    fun `maps lab observations correctly`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val observations = bundle.entry.mapNotNull { it.resource as? Observation }
        assertTrue(observations.isNotEmpty(), "Should have at least one observation")

        val hba1c = observations.find { it.code.text == "HbA1c" }
        assertNotNull(hba1c, "Should have HbA1c observation")
        assertEquals(Observation.ObservationStatus.FINAL, hba1c?.status)
    }

    @Test
    fun `creates no-known entries when data is empty`() {
        val emptyData = PatientSummaryData(
            patient = PersonDelegationData(id = "1", cpr = "0101837173", name = "Test Patient", relationType = "MigSelv"),
            conditions = null,
            forloeb = null,
            medications = emptyList(),
            immunizations = emptyList(),
            observations = null,
            cpr = "0101837173"
        )

        val bundle = mapper.toIpsBundle(emptyData, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        // Should still have conditions section with "no known problems"
        val conditions = bundle.entry.mapNotNull { it.resource as? Condition }
        assertTrue(conditions.any {
            it.code.codingFirstRep.code == "no-known-problems"
        }, "Should have no-known-problems condition")

        // Should have "no known medications" entry
        val medications = bundle.entry.mapNotNull { it.resource as? MedicationStatement }
        assertTrue(medications.any {
            it.medicationCodeableConcept.codingFirstRep.code == "no-known-medications"
        }, "Should have no-known-medications entry")
    }

    @Test
    fun `all bundle entries have fullUrl`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        bundle.entry.forEach { entry ->
            assertNotNull(entry.fullUrl, "Each entry must have a fullUrl")
            assertTrue(entry.fullUrl.startsWith("urn:uuid:"), "fullUrl should be a UUID URN")
        }
    }

    @Test
    fun `composition references all resources in bundle`() {
        val data = createTestSummaryData()
        val bundle = mapper.toIpsBundle(data, "http://localhost/fhir/Patient/pat-0101837173/\$summary")

        val composition = bundle.entryFirstRep.resource as Composition
        val allSectionReferences = composition.section.flatMap { section ->
            section.entry.map { it.reference }
        }.toSet()

        // Get all non-Composition resource fullUrls
        val resourceFullUrls = bundle.entry
            .filter { it.resource !is Composition }
            .mapNotNull { it.fullUrl }
            .toSet()

        // All resources (except those in empty sections) should be referenced
        val conditionRefs = bundle.entry.mapNotNull { it.resource as? Condition }.map { "urn:uuid:${it.id}" }
        val medicationRefs = bundle.entry.mapNotNull { it.resource as? MedicationStatement }.map { "urn:uuid:${it.id}" }
        val immunizationRefs = bundle.entry.mapNotNull { it.resource as? Immunization }.map { "urn:uuid:${it.id}" }

        conditionRefs.forEach { ref ->
            assertTrue(allSectionReferences.contains(ref), "Condition $ref should be referenced in composition")
        }
        medicationRefs.forEach { ref ->
            assertTrue(allSectionReferences.contains(ref), "Medication $ref should be referenced in composition")
        }
        immunizationRefs.forEach { ref ->
            assertTrue(allSectionReferences.contains(ref), "Immunization $ref should be referenced in composition")
        }
    }

    private fun createTestSummaryData(): PatientSummaryData {
        return PatientSummaryData(
            patient = PersonDelegationData(
                id = "1",
                cpr = "0101837173",
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
            observations = LabsvarResponse(
                svaroversigt = Svaroversigt(
                    laboratorieresultater = listOf(
                        Laboratorieresultat(
                            rekvisitionsId = "req-001",
                            proevenummerLaboratorie = "lab-001",
                            resultatdato = "2024-01-10T08:00:00+01:00",
                            resultatStatuskode = "SvarEndeligt",
                            resultatStatus = null,
                            resultattype = "laboratory",
                            vaerditype = "numeric",
                            vaerdi = "48",
                            analysetypeId = "HBA1C",
                            referenceIntervalTekst = "< 48 mmol/mol",
                            analysevejledningLink = null,
                            undersoegelser = listOf(
                                Undersoegelse(
                                    undersoegelsesNavn = "HbA1c",
                                    analyseKode = "NPU27300",
                                    eksaminator = null,
                                    quantitativeFindings = null
                                )
                            ),
                            konklusionHtml = null,
                            diagnoseHtml = null,
                            mikroskopiHtml = null,
                            makroskopiHtml = null,
                            materialeHtml = null,
                            kliniskeInformationerHtml = null
                        )
                    ),
                    rekvisitioner = listOf(
                        Rekvisition(
                            id = "req-001",
                            patientCpr = "0101837173",
                            patientNavn = "Jens Jorgensen",
                            rekvirentsOrganisation = "Regionshospitalet",
                            proevetagningstidspunkt = "2024-01-10T07:30:00+01:00"
                        )
                    )
                )
            ),
            cpr = "0101837173"
        )
    }
}
