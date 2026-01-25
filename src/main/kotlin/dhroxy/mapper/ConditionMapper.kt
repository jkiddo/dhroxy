package dhroxy.mapper

import dhroxy.model.DiagnoseEntry
import dhroxy.model.DiagnoserResponse
import dhroxy.model.ForloebEntry
import dhroxy.model.ForloebsoversigtResponse
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.Date
import java.util.UUID

@Component
class ConditionMapper {

    fun toBundle(
        forloebPayload: ForloebsoversigtResponse?,
        diagnoserPayload: DiagnoserResponse?,
        requestUrl: String
    ): Bundle {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            link = listOf(
                Bundle.BundleLinkComponent().apply {
                    relation = "self"
                    url = requestUrl
                }
            )
        }

        val cpr = forloebPayload?.personNummer?.replace("-", "")

        // Map conditions from forloebsoversigt
        forloebPayload?.forloeb.orEmpty().forEach { entry ->
            mapFromForloeb(entry, cpr)?.let {
                bundle.addEntry(Bundle.BundleEntryComponent().apply {
                    fullUrl = "urn:uuid:${UUID.randomUUID()}"
                    resource = it
                })
            }
        }

        // Map conditions from diagnoser endpoint
        diagnoserPayload?.diagnoser.orEmpty().forEach { entry ->
            mapFromDiagnose(entry, cpr)?.let {
                bundle.addEntry(Bundle.BundleEntryComponent().apply {
                    fullUrl = "urn:uuid:${UUID.randomUUID()}"
                    resource = it
                })
            }
        }

        bundle.total = bundle.entry.size
        return bundle
    }

    private fun mapFromForloeb(entry: ForloebEntry, cpr: String?): Condition? {
        val codeDisplay = entry.diagnoseNavn ?: entry.diagnoseKode
        if (codeDisplay.isNullOrBlank() && entry.diagnoseKode.isNullOrBlank()) return null

        val condition = Condition()
        condition.id = "cond-${safeId(entry.idNoegle?.noegle ?: UUID.randomUUID().toString())}"
        condition.code = CodeableConcept().apply {
            text = codeDisplay
            entry.diagnoseKode?.let { addCoding(Coding().setSystem("https://www.sundhed.dk/diagnosekode").setCode(it).setDisplay(entry.diagnoseNavn)) }
        }
        condition.clinicalStatus = CodeableConcept().apply {
            addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .setCode(if (entry.datoTil.isNullOrBlank()) "active" else "resolved")
            )
        }
        condition.verificationStatus = CodeableConcept().apply {
            addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
                    .setCode("confirmed")
            )
        }
        entry.datoFra?.let { condition.setOnset(org.hl7.fhir.r4.model.DateTimeType(Date.from(OffsetDateTime.parse(it).toInstant()))) }
        entry.datoTil?.let { condition.setAbatement(org.hl7.fhir.r4.model.DateTimeType(Date.from(OffsetDateTime.parse(it).toInstant()))) }
        entry.datoOpdateret?.let { condition.recordedDate = Date.from(OffsetDateTime.parse(it).toInstant()) }

        cpr?.let {
            condition.subject = Reference().apply {
                setIdentifier(Identifier().setSystem("urn:dk:cpr").setValue(it))
            }
        }

        entry.idNoegle?.noegle?.let {
            condition.addIdentifier().setSystem("https://www.sundhed.dk/ejournal/forloeb").value = it
        }

        return condition
    }

    private fun mapFromDiagnose(entry: DiagnoseEntry, cpr: String?): Condition? {
        val codeDisplay = entry.diagnoseNavn ?: entry.diagnoseKode
        if (codeDisplay.isNullOrBlank() && entry.diagnoseKode.isNullOrBlank()) return null

        val condition = Condition()
        condition.id = "cond-diag-${safeId(entry.diagnoseKode ?: UUID.randomUUID().toString())}"
        condition.code = CodeableConcept().apply {
            text = codeDisplay
            entry.diagnoseKode?.let { addCoding(Coding().setSystem("https://www.sundhed.dk/diagnosekode").setCode(it).setDisplay(entry.diagnoseNavn)) }
        }
        condition.clinicalStatus = CodeableConcept().apply {
            addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .setCode(if (entry.datoTil.isNullOrBlank()) "active" else "resolved")
            )
        }
        condition.verificationStatus = CodeableConcept().apply {
            addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
                    .setCode("confirmed")
            )
        }

        entry.type?.let {
            condition.addCategory(CodeableConcept().apply {
                addCoding(Coding().setSystem("https://www.sundhed.dk/diagnosetype").setCode(it))
            })
        }

        entry.datoFra?.let { condition.setOnset(org.hl7.fhir.r4.model.DateTimeType(Date.from(OffsetDateTime.parse(it).toInstant()))) }
        entry.datoTil?.let { condition.setAbatement(org.hl7.fhir.r4.model.DateTimeType(Date.from(OffsetDateTime.parse(it).toInstant()))) }

        cpr?.let {
            condition.subject = Reference().apply {
                setIdentifier(Identifier().setSystem("urn:dk:cpr").setValue(it))
            }
        }

        condition.addIdentifier().setSystem("https://www.sundhed.dk/diagnoser").value = entry.diagnoseKode

        return condition
    }

    private fun safeId(input: String): String =
        input.lowercase().replace(Regex("[^a-z0-9]+"), "-")
}
