package dhroxy.mapper

import dhroxy.model.CoreOrganization
import dhroxy.model.CoreOrganizationResponse
import org.hl7.fhir.r4.model.*
import org.springframework.stereotype.Component
import java.util.*

@Component
class OrganizationMapper {
    fun toOrganizationBundle(payload: CoreOrganizationResponse?, requestUrl: String): Bundle {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            link = listOf(Bundle.BundleLinkComponent().apply {
                relation = "self"
                url = requestUrl
            })
        }
        payload?.organizations.orEmpty().forEach { org ->
            bundle.addEntry(Bundle.BundleEntryComponent().apply {
                val resource = mapOrganization(org)
                fullUrl = "urn:uuid:${resource.idElement.idPart}"
                this.resource = resource
            })
        }
        bundle.total = bundle.entry.size
        return bundle
    }

    private fun mapOrganization(record: CoreOrganization): Organization {
        val organization = Organization()
        val id = record.organizationId?.toString() ?: UUID.randomUUID().toString()
        organization.id = "org-$id"
        record.cvrNumber?.let {
            organization.identifier = listOf(
                Identifier().setSystem("urn:dk:cvr").setValue(it.toString())
            )
        }
        organization.name = record.displayName ?: record.name ?: "Organization $id"
        record.category?.let { cat ->
            organization.type = listOf(
                CodeableConcept().apply {
                    text = cat
                    addCoding(
                        Coding()
                            .setSystem("https://www.sundhed.dk/organization/category")
                            .setCode(cat.lowercase().replace(" ", "-"))
                            .setDisplay(cat)
                    )
                }
            )
        }
        organization.address = listOf(Address().apply {
            val line = listOfNotNull(record.street, record.houseNumberFrom, record.floor, record.door)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (line.isNotBlank()) addLine(line)
            city = record.city
            postalCode = record.zipCode?.toString()
            district = record.municipality
            country = "DK"
        })
        record.homepage?.let {
            organization.telecom = listOf(
                org.hl7.fhir.r4.model.ContactPoint().apply {
                    system = org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.URL
                    value = it
                }
            )
        }
        record.lastUpdated?.let {
            organization.meta = organization.meta.apply {
                lastUpdated = org.hl7.fhir.r4.model.InstantType(it).value
            }
        }
        return organization
    }
}
