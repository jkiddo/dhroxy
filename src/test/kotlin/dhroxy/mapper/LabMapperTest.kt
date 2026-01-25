package dhroxy.mapper

import dhroxy.model.*
import org.hl7.fhir.r4.model.Observation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class LabMapperTest {
    private val mapper = LabMapper()

    @Test
    fun `maps labsvar payload to observation bundle`() {
        val quantitative = QuantitativeFindings(
            data = listOf(
                listOf(
                    null,
                    "Code",
                    "CodeType",
                    "CodeResponsible",
                    "Text",
                    "InterPretation_Code",
                    "InterPretation_CodeType",
                    "InterPretation_CodeResponseble",
                    "InterPretation_Text",
                    "Value",
                    "Unit",
                    "Ref. Område",
                    "Kommentar",
                    null
                ),
                listOf(
                    "Finding: 0",
                    "DNK35312",
                    "0",
                    "SSI",
                    "CRP",
                    null,
                    "0",
                    null,
                    "_",
                    "5.2",
                    "mg/L",
                    "",
                    "",
                    null
                )
            ),
            noColumns = 2,
            noRows = 14
        )

        val undersoegelse = Undersoegelse(
            analyseKode = "DNK35312",
            eksaminator = "Statens Serum Institut",
            materiale = "Serum",
            oprindelsesSted = "Lab",
            producent = "SSI",
            quantitativeFindings = quantitative,
            undersoegelsesNavn = "CRP"
        )

        val result = Laboratorieresultat(
            analysetypeId = "KliniskBiokemi",
            produktionsnummerLaboratorie = "prod-1",
            proevenummerLaboratorie = "prov-1",
            proevenummerRekvirent = "prov-1",
            rekvisitionsId = "req-1",
            resultatStatus = "KompletSvar",
            resultatStatuskode = "SvarEndeligt",
            resultatdato = "2024-02-01T08:32:00+01:00",
            resultattype = "Xrpt05",
            undersoegelser = listOf(undersoegelse),
            vaerditype = "Tal"
        )

        val rekvisition = Rekvisition(
            afsenderHtml = "Clinic",
            id = "req-1",
            laboratorieProductionsNummer = "prod-1",
            laboratorieProevenummer = "prov-1",
            laboratorieomraade = "KliniskBiokemi",
            patientCpr = "1111111111",
            patientNavn = "Test Person",
            proevetagningstidspunkt = "2024-02-01T07:00:00+01:00",
            rekvirentHtml = "Clinic",
            rekvirentsOrganisation = "Clinic",
            rekvirentsProevenummer = "prov-1",
            svartidspunkt = "2024-02-01T08:40:00+01:00"
        )

        val response = LabsvarResponse(
            svaroversigt = Svaroversigt(
                laboratorieresultater = listOf(result),
                rekvisitioner = listOf(rekvisition)
            )
        )

        val bundle = mapper.toObservationBundle(response, "http://localhost/fhir/Observation")
        assertEquals(1, bundle.total)

        val observation = bundle.entryFirstRep.resource as Observation
        assertEquals(Observation.ObservationStatus.FINAL, observation.status)
        assertEquals("CRP", observation.code.text)
        assertEquals("DNK35312", observation.code.codingFirstRep.code)
        assertEquals("Test Person", observation.subject.display)
        assertNotNull(observation.valueQuantity)
        assertEquals("5.2", observation.valueQuantity.value?.toPlainString())
    }

    @Test
    fun `maps pathology labsvar payload with narrative fields`() {
        val result = Laboratorieresultat(
            analysetypeId = "Patologi",
            rekvisitionsId = "AH91-4634_910400XXX",
            resultatStatus = "KompletSvar",
            resultatStatuskode = "SvarEndeligt",
            resultatdato = "1981-06-12T00:00:00.0000000+02:00",
            vaerditype = "Tekst",
            materialeHtml = "[1]: APPENDIX <br/>",
            diagnoseHtml = "[1]: Sdslke flksdfjk - asdf <br/>",
            konklusionHtml = "[01]T6XXXX csdlkj <br/>M41700sf mlksdf&#230;n&#248;s sdfnjlk<br/>",
            mikroskopiHtml = " lkfsdms eef ...",
            makroskopiHtml = " lksfdk ml fslkm ...",
            kliniskeInformationerHtml = " ÆLSDF KÆFLSD: FSDÆLK. DSFSDFÆKL.",
            vaerdi = "PATO",
            undersoegelser = emptyList()
        )

        val rekvisition = Rekvisition(
            id = "AH91-4634_9104004634",
            patientCpr = "070)833281",
            patientNavn = "John Petersen",
            rekvirentsOrganisation = "Patologisk Institut",
            proevetagningstidspunkt = "1984-05-07T15:00:00.0000000+02:00"
        )

        val response = LabsvarResponse(
            svaroversigt = Svaroversigt(
                laboratorieresultater = listOf(result),
                rekvisitioner = listOf(rekvisition)
            )
        )

        val bundle = mapper.toObservationBundle(response, "http://localhost/fhir/Observation")
        assertEquals(1, bundle.total)
        val observation = bundle.entryFirstRep.resource as Observation

        // Status and code fallbacks
        assertEquals(Observation.ObservationStatus.FINAL, observation.status)
        assertEquals("Patologi", observation.code.text)

        // Narrative value uses pathology text (cleaned)
        val valueString = observation.valueStringType?.value
        assertNotNull(valueString)
        requireNotNull(valueString)
        assert(valueString.contains("[01]T6XXXX csdlkj \nM41700sf mlksdfænøs sdfnjlk"))

        // Notes include the extra pathology fields
        val notes = observation.note.mapNotNull { it.text }
        assert(notes.any { it.contains("Materiale") })
        assert(notes.any { it.contains("Diagnose") })
        assert(notes.any { it.contains("Konklusion") })
        assert(notes.any { it.contains("Kliniske oplysninger") })

    }
}
