package dhroxy.mapper

import dhroxy.model.AppointmentItem
import dhroxy.model.AppointmentPerson
import dhroxy.model.AppointmentsResponse
import dhroxy.model.LocationDetailed
import dhroxy.model.PerformerDetailed
import org.hl7.fhir.r4.model.Appointment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppointmentMapperTest {
    private val mapper = AppointmentMapper()

    @Test
    fun `maps appointments to bundle and filters by date`() {
        val apt = AppointmentItem(
            appointmentType = "consult",
            title = "Consultation",
            documentId = "doc-1",
            startTime = "2024-01-15T10:00:00+01:00",
            endTime = "2024-01-15T10:30:00+01:00",
            patient = AppointmentPerson(
                familyName = "Belias",
                givenName = "Elias",
                personIdentifier = "1207130201"
            ),
            performer = PerformerDetailed(
                organisation = "Dr. Doe"
            ),
            location = LocationDetailed(
                organisation = "Clinic A"
            )
        )
        val payload = AppointmentsResponse(appointments = listOf(apt))
        val bundle = mapper.toAppointmentBundle(payload, "http://localhost/fhir/Appointment")
        val filtered = mapper.filterByDate(bundle, "2024-01-01T00:00:00+01:00", "2024-01-31T23:59:59+01:00")

        assertEquals(1, filtered.entry.size)
        val mapped = filtered.entryFirstRep.resource as Appointment
        assertEquals(Appointment.AppointmentStatus.BOOKED, mapped.status)
        assertEquals("Consultation", mapped.description)
    }
}
