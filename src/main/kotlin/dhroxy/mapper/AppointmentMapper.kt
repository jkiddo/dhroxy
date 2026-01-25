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
        val startInstant = start?.let { parseToInstant(it, isEndDate = false) }
        val endInstant = end?.let { parseToInstant(it, isEndDate = true) }
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

    /**
     * Parses a date string to Instant, handling partial dates.
     * - "1950" -> 1950-01-01T00:00:00Z (start) or 1950-12-31T23:59:59Z (end)
     * - "1950-06" -> 1950-06-01T00:00:00Z (start) or 1950-06-30T23:59:59Z (end)
     * - "1950-06-15" -> 1950-06-15T00:00:00Z (start) or 1950-06-15T23:59:59Z (end)
     * - Full ISO-8601 datetime -> parsed directly
     */
    private fun parseToInstant(date: String, isEndDate: Boolean): java.time.Instant {
        return try {
            OffsetDateTime.parse(date).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            val localDate = parseToLocalDate(date, isEndDate)
            val time = if (isEndDate) java.time.LocalTime.MAX else java.time.LocalTime.MIN
            localDate.atTime(time).toInstant(java.time.ZoneOffset.UTC)
        }
    }

    private fun parseToLocalDate(date: String, isEndDate: Boolean): java.time.LocalDate {
        val datePart = if (date.contains('T')) date.substringBefore('T') else date
        val parts = datePart.split("-")
        return when (parts.size) {
            1 -> {
                val year = parts[0].toInt()
                if (isEndDate) java.time.LocalDate.of(year, 12, 31) else java.time.LocalDate.of(year, 1, 1)
            }
            2 -> {
                val yearMonth = java.time.YearMonth.parse(datePart)
                if (isEndDate) yearMonth.atEndOfMonth() else yearMonth.atDay(1)
            }
            else -> java.time.LocalDate.parse(datePart)
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
