package dhroxy.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppointmentsResponse(
    @JsonProperty("appointments")
    val appointments: List<AppointmentItem> = emptyList(),
    @JsonProperty("canOverrideConsent")
    val canOverrideConsent: Boolean? = null,
    @JsonProperty("consentFilterApplied")
    val consentFilterApplied: Boolean? = null,
    @JsonProperty("errorText")
    val errorText: String? = null,
    @JsonProperty("reference")
    val reference: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppointmentItem(
    @JsonProperty("appointmentColorClass")
    val appointmentColorClass: String? = null,
    @JsonProperty("appointmentType")
    val appointmentType: String? = null,
    @JsonProperty("documentId")
    val documentId: String? = null,
    @JsonProperty("endTime")
    val endTime: String? = null,
    @JsonProperty("endTimeDetailed")
    val endTimeDetailed: TimeDetailed? = null,
    @JsonProperty("endTimeNotDefined")
    val endTimeNotDefined: Boolean? = null,
    @JsonProperty("location")
    val location: LocationDetailed? = null,
    @JsonProperty("patient")
    val patient: AppointmentPerson? = null,
    @JsonProperty("performer")
    val performer: PerformerDetailed? = null,
    @JsonProperty("startTime")
    val startTime: String? = null,
    @JsonProperty("startTimeDetailed")
    val startTimeDetailed: TimeDetailed? = null,
    @JsonProperty("title")
    val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimeDetailed(
    @JsonProperty("weekNo")
    val weekNo: Int? = null,
    @JsonProperty("timeFormatted")
    val timeFormatted: String? = null,
    @JsonProperty("dateFormatted")
    val dateFormatted: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AddressDetailed(
    @JsonProperty("street")
    val street: String? = null,
    @JsonProperty("postalCode")
    val postalCode: String? = null,
    @JsonProperty("city")
    val city: String? = null,
    @JsonProperty("country")
    val country: String? = null,
    @JsonProperty("formatted")
    val formatted: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationDetailed(
    @JsonProperty("organisation")
    val organisation: String? = null,
    @JsonProperty("address")
    val address: AddressDetailed? = null,
    @JsonProperty("unitType")
    val unitType: String? = null,
    @JsonProperty("ward")
    val ward: String? = null,
    @JsonProperty("phone")
    val phone: String? = null,
    @JsonProperty("sorId")
    val sorId: String? = null,
    @JsonProperty("addressPhoneFormatted")
    val addressPhoneFormatted: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PerformerDetailed(
    @JsonProperty("organisation")
    val organisation: String? = null,
    @JsonProperty("address")
    val address: AddressDetailed? = null,
    @JsonProperty("unitType")
    val unitType: String? = null,
    @JsonProperty("ward")
    val ward: String? = null,
    @JsonProperty("phone")
    val phone: String? = null,
    @JsonProperty("sorId")
    val sorId: String? = null,
    @JsonProperty("addressPhoneFormatted")
    val addressPhoneFormatted: String? = null
)

data class AppointmentLocation(
    @JsonProperty("address")
    val address: String? = null,
    @JsonProperty("postalCode")
    val postalCode: String? = null,
    @JsonProperty("title")
    val title: String? = null
)

data class AppointmentPerson(
    @JsonProperty("address")
    val address: String? = null,
    @JsonProperty("familyName")
    val familyName: String? = null,
    @JsonProperty("givenName")
    val givenName: String? = null,
    @JsonProperty("personIdentifier")
    val personIdentifier: String? = null
)

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class AppointmentsRequest(
    @JsonProperty("FromDate")
    val fromDate: String? = null,
    @JsonProperty("ToDate")
    val toDate: String? = null,
    @JsonProperty("Version")
    val version: Int = 2,
    @JsonProperty("WithPartials")
    val withPartials: Boolean = true
)
