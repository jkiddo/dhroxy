package dhroxy.mapper

import dhroxy.model.DiagnoseEntry
import dhroxy.model.DiagnoserResponse
import dhroxy.model.ForloebEntry
import dhroxy.model.ForloebsoversigtResponse
import dhroxy.model.NoegleRef
import org.hl7.fhir.r4.model.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConditionMapperTest {
    private val mapper = ConditionMapper()

    @Test
    fun `maps forloeb entries to conditions`() {
        val forloeb = ForloebsoversigtResponse(
            personNummer = "123456-7890",
            forloeb = listOf(
                ForloebEntry(
                    diagnoseKode = "DJ069",
                    diagnoseNavn = "Akut infektion i øvre luftveje",
                    datoFra = "2024-01-15T10:00:00+01:00",
                    datoTil = null,
                    idNoegle = NoegleRef(noegle = "forloeb-123")
                )
            )
        )

        val bundle = mapper.toBundle(forloeb, null, "http://localhost/fhir/Condition")

        assertEquals(1, bundle.total)
        val condition = bundle.entryFirstRep.resource as Condition
        assertEquals("Akut infektion i øvre luftveje", condition.code.text)
        assertEquals("DJ069", condition.code.codingFirstRep.code)
        assertEquals("active", condition.clinicalStatus.codingFirstRep.code)
        assertEquals("1234567890", condition.subject.identifier.value)
        assertNotNull(condition.onsetDateTimeType)
    }

    @Test
    fun `maps diagnoser entries to conditions`() {
        val diagnoser = DiagnoserResponse(
            diagnoser = listOf(
                DiagnoseEntry(
                    diagnoseKode = "DZ761",
                    diagnoseNavn = "Kontakt mhp radiologisk undersøgelse",
                    type = "aktionsdiagnose",
                    datoFra = "2024-02-01T08:00:00+01:00",
                    datoTil = "2024-02-01T09:00:00+01:00"
                )
            )
        )

        val bundle = mapper.toBundle(null, diagnoser, "http://localhost/fhir/Condition")

        assertEquals(1, bundle.total)
        val condition = bundle.entryFirstRep.resource as Condition
        assertEquals("Kontakt mhp radiologisk undersøgelse", condition.code.text)
        assertEquals("DZ761", condition.code.codingFirstRep.code)
        assertEquals("resolved", condition.clinicalStatus.codingFirstRep.code)
        assertEquals("aktionsdiagnose", condition.category.first().codingFirstRep.code)
        assertNotNull(condition.onsetDateTimeType)
        assertNotNull(condition.abatementDateTimeType)
    }

    @Test
    fun `merges conditions from both sources`() {
        val forloeb = ForloebsoversigtResponse(
            personNummer = "123456-7890",
            forloeb = listOf(
                ForloebEntry(
                    diagnoseKode = "DJ069",
                    diagnoseNavn = "Akut infektion",
                    datoFra = "2024-01-15T10:00:00+01:00",
                    idNoegle = NoegleRef(noegle = "forloeb-1")
                )
            )
        )
        val diagnoser = DiagnoserResponse(
            diagnoser = listOf(
                DiagnoseEntry(
                    diagnoseKode = "DZ761",
                    diagnoseNavn = "Radiologisk undersøgelse",
                    datoFra = "2024-02-01T08:00:00+01:00"
                )
            )
        )

        val bundle = mapper.toBundle(forloeb, diagnoser, "http://localhost/fhir/Condition")

        assertEquals(2, bundle.total)
        val codes = bundle.entry.map { (it.resource as Condition).code.codingFirstRep.code }
        assertTrue(codes.contains("DJ069"))
        assertTrue(codes.contains("DZ761"))
    }

    @Test
    fun `skips entries without diagnosis code or name`() {
        val forloeb = ForloebsoversigtResponse(
            forloeb = listOf(
                ForloebEntry(
                    diagnoseKode = null,
                    diagnoseNavn = null,
                    idNoegle = NoegleRef(noegle = "empty-1")
                ),
                ForloebEntry(
                    diagnoseKode = "DJ069",
                    diagnoseNavn = "Valid diagnosis",
                    idNoegle = NoegleRef(noegle = "valid-1")
                )
            )
        )
        val diagnoser = DiagnoserResponse(
            diagnoser = listOf(
                DiagnoseEntry(diagnoseKode = null, diagnoseNavn = null),
                DiagnoseEntry(diagnoseKode = "DZ761", diagnoseNavn = "Valid diagnosis 2")
            )
        )

        val bundle = mapper.toBundle(forloeb, diagnoser, "http://localhost/fhir/Condition")

        assertEquals(2, bundle.total)
    }

    @Test
    fun `handles null payloads gracefully`() {
        val bundle = mapper.toBundle(null, null, "http://localhost/fhir/Condition")

        assertEquals(0, bundle.total)
        assertTrue(bundle.entry.isEmpty())
    }

    @Test
    fun `sets resolved status when datoTil is present`() {
        val forloeb = ForloebsoversigtResponse(
            forloeb = listOf(
                ForloebEntry(
                    diagnoseKode = "DJ069",
                    diagnoseNavn = "Resolved condition",
                    datoFra = "2024-01-15T10:00:00+01:00",
                    datoTil = "2024-01-20T10:00:00+01:00",
                    idNoegle = NoegleRef(noegle = "resolved-1")
                )
            )
        )

        val bundle = mapper.toBundle(forloeb, null, "http://localhost/fhir/Condition")

        val condition = bundle.entryFirstRep.resource as Condition
        assertEquals("resolved", condition.clinicalStatus.codingFirstRep.code)
        assertNotNull(condition.abatementDateTimeType)
    }
}
