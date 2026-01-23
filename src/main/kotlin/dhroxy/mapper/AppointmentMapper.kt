package dhroxy.mapper

import dhroxy.model.AppointmentItem
import dhroxy.model.AppointmentsResponse
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
class AppointmentMapper {

    fun toAppointmentBundle(payload: AppointmentsResponse?, requestUrl: String): Bundle {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            link = listOf(Bundle.BundleLinkComponent().apply {
                relation = "self"
                url = requestUrl
            })
        }
        payload?.appointments.orEmpty().forEach { apt ->
            val resource = mapAppointment(apt)
            bundle.addEntry(Bundle.BundleEntryComponent().apply {
                fullUrl = "urn:uuid:${UUID.randomUUID()}"
                this.resource = resource
            })
        }
        bundle.total = bundle.entry.size
        return bundle
    }

    fun filterByDate(bundle: Bundle, start: String?, end: String?): Bundle {
        if (start.isNullOrBlank() && end.isNullOrBlank()) return bundle
        val startInstant = start?.let { OffsetDateTime.parse(it).toInstant() }
        val endInstant = end?.let { OffsetDateTime.parse(it).toInstant() }
        val filtered = bundle.entry.filter { entry ->
            val apt = entry.resource as? Appointment ?: return@filter false
            val startDate = apt.start?.toInstant()
            when {
                startInstant != null && endInstant != null -> startDate != null && !startDate.isBefore(startInstant) && !startDate.isAfter(endInstant)
                startInstant != null -> startDate != null && !startDate.isBefore(startInstant)
                endInstant != null -> startDate != null && !startDate.isAfter(endInstant)
                else -> true
            }
        }
        return Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            link = bundle.link
            filtered.forEach { addEntry(it) }
            total = filtered.size
        }
    }

    private fun mapAppointment(item: AppointmentItem): Appointment {
        val apt = Appointment()
        apt.id = "apt-${UUID.randomUUID()}"
        apt.status = Appointment.AppointmentStatus.BOOKED
        apt.serviceType = listOf(
            CodeableConcept().addCoding(
                Coding().setSystem("https://www.sundhed.dk/appointments/type").setCode(item.appointmentType)
            ).apply { text = item.title }
        )
        item.documentId?.let {
            apt.identifier = listOf(
                Identifier().setSystem("https://www.sundhed.dk/appointments/documentId").setValue(it)
            )
        }
        item.startTime?.let { apt.start = java.util.Date.from(OffsetDateTime.parse(it).toInstant()) }
        item.endTime?.let { apt.end = java.util.Date.from(OffsetDateTime.parse(it).toInstant()) }
        apt.description = item.title

        item.patient?.let {
            apt.addParticipant(
                Appointment.AppointmentParticipantComponent().apply {
                    actor = Reference().apply {
                        setIdentifier(Identifier().setSystem("urn:dk:cpr").setValue(it.personIdentifier))
                        display = listOfNotNull(it.givenName, it.familyName).joinToString(" ")
                    }
                    status = Appointment.ParticipationStatus.ACCEPTED
                }
            )
        }
        item.performer?.let {
            apt.addParticipant(
                Appointment.AppointmentParticipantComponent().apply {
                    actor = Reference().apply {
                        display = listOfNotNull(it.organisation, it.address?.formatted).joinToString(" - ")
                    }
                    status = Appointment.ParticipationStatus.ACCEPTED
                }
            )
        }
        item.location?.let {
            apt.addParticipant(
                Appointment.AppointmentParticipantComponent().apply {
                    actor = Reference().apply {
                        display = listOfNotNull(it.organisation, it.address?.formatted).joinToString(" - ")
                    }
                    status = Appointment.ParticipationStatus.ACCEPTED
                }
            )
        }
        return apt
    }
}
