package dhroxy.mapper

import dhroxy.model.*
import dhroxy.service.PatientSummaryData
import org.hl7.fhir.r4.model.*
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Mapper that creates an International Patient Summary (IPS) FHIR Document Bundle.
 *
 * The IPS is a minimal, non-exhaustive patient summary dataset, specialty-agnostic,
 * condition-independent, but readily usable by clinicians for the cross-border unscheduled care.
 */
@Component
class PatientSummaryMapper {

    companion object {
        // IPS Profile URLs
        private const val IPS_COMPOSITION_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips"
        private const val IPS_PATIENT_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips"
        private const val IPS_CONDITION_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips"
        private const val IPS_MEDICATION_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/MedicationStatement-uv-ips"
        private const val IPS_IMMUNIZATION_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/Immunization-uv-ips"
        private const val IPS_OBSERVATION_PROFILE = "http://hl7.org/fhir/uv/ips/StructureDefinition/Observation-results-laboratory-uv-ips"

        // Section codes from LOINC
        private const val LOINC_SYSTEM = "http://loinc.org"
        private const val PROBLEMS_SECTION_CODE = "11450-4"
        private const val MEDICATIONS_SECTION_CODE = "10160-0"
        private const val ALLERGIES_SECTION_CODE = "48765-2"
        private const val IMMUNIZATIONS_SECTION_CODE = "11369-6"
        private const val RESULTS_SECTION_CODE = "30954-2"
    }

    /**
     * Creates an IPS Document Bundle from the patient summary data.
     */
    fun toIpsBundle(data: PatientSummaryData, requestUrl: String): Bundle {
        val bundle = Bundle()
        bundle.id = "ips-${UUID.randomUUID()}"
        bundle.type = Bundle.BundleType.DOCUMENT
        bundle.timestamp = Date()
        bundle.identifier = Identifier()
            .setSystem("https://www.sundhed.dk/fhir/ips")
            .setValue(UUID.randomUUID().toString())

        // Track all resources and their references
        val resources = mutableListOf<Resource>()
        val patientRef: Reference

        // 1. Create Patient resource
        val patient = createPatient(data)
        patientRef = Reference("urn:uuid:${patient.id}")
        resources.add(patient)

        // 2. Create Condition resources (Problems section)
        val conditions = createConditions(data, patientRef)
        resources.addAll(conditions)

        // 3. Create MedicationStatement resources (Medications section)
        val medications = createMedicationStatements(data, patientRef)
        resources.addAll(medications)

        // 4. Create Immunization resources
        val immunizations = createImmunizations(data, patientRef)
        resources.addAll(immunizations)

        // 5. Create Observation resources (Results section)
        val observations = createObservations(data, patientRef)
        resources.addAll(observations)

        // 6. Create Composition resource (must be first entry)
        val composition = createComposition(
            patient = patient,
            patientRef = patientRef,
            conditions = conditions,
            medications = medications,
            immunizations = immunizations,
            observations = observations
        )

        // Add Composition as first entry, then all other resources
        bundle.addEntry(Bundle.BundleEntryComponent().apply {
            fullUrl = "urn:uuid:${composition.id}"
            resource = composition
        })

        resources.forEach { resource ->
            bundle.addEntry(Bundle.BundleEntryComponent().apply {
                fullUrl = "urn:uuid:${resource.id}"
                this.resource = resource
            })
        }

        return bundle
    }

    private fun createPatient(data: PatientSummaryData): Patient {
        val patient = Patient()
        patient.id = data.cpr?.let { "pat-$it" } ?: "pat-${UUID.randomUUID()}"
        patient.meta = Meta().addProfile(IPS_PATIENT_PROFILE)

        data.cpr?.let {
            patient.addIdentifier(Identifier()
                .setSystem("urn:dk:cpr")
                .setValue(it))
        }

        data.patient?.name?.let { fullName ->
            val nameParts = fullName.trim().split(" ").filter { it.isNotBlank() }
            patient.addName(HumanName().apply {
                if (nameParts.isNotEmpty()) {
                    family = nameParts.last()
                    given = nameParts.dropLast(1).map { StringType(it) }
                } else {
                    text = fullName
                }
            })
        }

        return patient
    }

    private fun createConditions(data: PatientSummaryData, patientRef: Reference): List<Condition> {
        val conditions = mutableListOf<Condition>()

        // Map conditions from diagnoser endpoint
        data.conditions?.diagnoser.orEmpty().forEach { entry ->
            createConditionFromDiagnose(entry, patientRef)?.let { conditions.add(it) }
        }

        // Map conditions from forloebsoversigt
        data.forloeb?.forloeb.orEmpty().forEach { entry ->
            createConditionFromForloeb(entry, patientRef)?.let { conditions.add(it) }
        }

        // If no conditions, add "no known problems" entry as required by IPS
        if (conditions.isEmpty()) {
            conditions.add(createNoKnownProblemsCondition(patientRef))
        }

        return conditions
    }

    private fun createConditionFromDiagnose(entry: DiagnoseEntry, patientRef: Reference): Condition? {
        val codeDisplay = entry.diagnoseNavn ?: entry.diagnoseKode
        if (codeDisplay.isNullOrBlank() && entry.diagnoseKode.isNullOrBlank()) return null

        return Condition().apply {
            id = "cond-diag-${safeId(entry.diagnoseKode ?: UUID.randomUUID().toString())}"
            meta = Meta().addProfile(IPS_CONDITION_PROFILE)

            code = CodeableConcept().apply {
                text = codeDisplay
                entry.diagnoseKode?.let {
                    addCoding(Coding()
                        .setSystem("https://www.sundhed.dk/diagnosekode")
                        .setCode(it)
                        .setDisplay(entry.diagnoseNavn))
                }
            }

            clinicalStatus = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .setCode(if (entry.datoTil.isNullOrBlank()) "active" else "resolved"))
            }

            verificationStatus = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
                    .setCode("confirmed"))
            }

            entry.datoFra?.let { setOnset(DateTimeType(parseDate(it))) }
            entry.datoTil?.let { setAbatement(DateTimeType(parseDate(it))) }

            subject = patientRef
        }
    }

    private fun createConditionFromForloeb(entry: ForloebEntry, patientRef: Reference): Condition? {
        val codeDisplay = entry.diagnoseNavn ?: entry.diagnoseKode
        if (codeDisplay.isNullOrBlank() && entry.diagnoseKode.isNullOrBlank()) return null

        return Condition().apply {
            id = "cond-forloeb-${safeId(entry.idNoegle?.noegle ?: UUID.randomUUID().toString())}"
            meta = Meta().addProfile(IPS_CONDITION_PROFILE)

            code = CodeableConcept().apply {
                text = codeDisplay
                entry.diagnoseKode?.let {
                    addCoding(Coding()
                        .setSystem("https://www.sundhed.dk/diagnosekode")
                        .setCode(it)
                        .setDisplay(entry.diagnoseNavn))
                }
            }

            clinicalStatus = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .setCode(if (entry.datoTil.isNullOrBlank()) "active" else "resolved"))
            }

            verificationStatus = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
                    .setCode("confirmed"))
            }

            entry.datoFra?.let { setOnset(DateTimeType(parseDate(it))) }
            entry.datoTil?.let { setAbatement(DateTimeType(parseDate(it))) }

            subject = patientRef
        }
    }

    private fun createNoKnownProblemsCondition(patientRef: Reference): Condition {
        return Condition().apply {
            id = "cond-no-known-${UUID.randomUUID()}"
            meta = Meta().addProfile(IPS_CONDITION_PROFILE)

            code = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://hl7.org/fhir/uv/ips/CodeSystem/absent-unknown-uv-ips")
                    .setCode("no-known-problems")
                    .setDisplay("No known problems"))
            }

            clinicalStatus = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .setCode("active"))
            }

            subject = patientRef
        }
    }

    private fun createMedicationStatements(data: PatientSummaryData, patientRef: Reference): List<MedicationStatement> {
        val medications = data.medications.mapNotNull { entry ->
            createMedicationStatement(entry, patientRef)
        }

        // If no medications, create "no known medications" entry
        if (medications.isEmpty()) {
            return listOf(createNoKnownMedicationsStatement(patientRef))
        }

        return medications
    }

    private fun createMedicationStatement(entry: MedicationCardEntry, patientRef: Reference): MedicationStatement? {
        return MedicationStatement().apply {
            id = "medstmt-${safeId(entry.ordinationId ?: UUID.randomUUID().toString())}"
            meta = Meta().addProfile(IPS_MEDICATION_PROFILE)

            entry.ordinationId?.let {
                addIdentifier(Identifier()
                    .setSystem("https://www.sundhed.dk/medication/ordination")
                    .setValue(it))
            }

            status = mapMedicationStatus(entry.status?.enumStr)

            medication = CodeableConcept().apply {
                text = entry.drugMedication ?: entry.activeSubstance ?: "Medication"
                entry.activeSubstance?.let { substance ->
                    addCoding(Coding()
                        .setSystem("https://www.sundhed.dk/medication/active-substance")
                        .setCode(substance)
                        .setDisplay(substance))
                }
            }

            subject = patientRef

            entry.dosage?.let { dosageText ->
                dosage = listOf(org.hl7.fhir.r4.model.Dosage().apply {
                    setText(dosageText)
                    entry.cause?.let { setPatientInstruction(it) }
                })
            }

            effective = Period().apply {
                entry.startDate?.let { start = parseDate(it) }
                (entry.endDate ?: entry.dosageEndDate)?.let { end = parseDate(it) }
            }
        }
    }

    private fun createNoKnownMedicationsStatement(patientRef: Reference): MedicationStatement {
        return MedicationStatement().apply {
            id = "medstmt-no-known-${UUID.randomUUID()}"
            meta = Meta().addProfile(IPS_MEDICATION_PROFILE)

            status = MedicationStatement.MedicationStatementStatus.UNKNOWN

            medication = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://hl7.org/fhir/uv/ips/CodeSystem/absent-unknown-uv-ips")
                    .setCode("no-known-medications")
                    .setDisplay("No known medications"))
            }

            subject = patientRef
        }
    }

    private fun mapMedicationStatus(raw: String?): MedicationStatement.MedicationStatementStatus {
        return when (raw?.lowercase()) {
            "active", null -> MedicationStatement.MedicationStatementStatus.ACTIVE
            "completed" -> MedicationStatement.MedicationStatementStatus.COMPLETED
            "stopped", "ended" -> MedicationStatement.MedicationStatementStatus.STOPPED
            "entered-in-error" -> MedicationStatement.MedicationStatementStatus.ENTEREDINERROR
            else -> MedicationStatement.MedicationStatementStatus.UNKNOWN
        }
    }

    private fun createImmunizations(data: PatientSummaryData, patientRef: Reference): List<Immunization> {
        val immunizations = data.immunizations.mapNotNull { record ->
            createImmunization(record, patientRef)
        }

        // If no immunizations, create "no information" entry
        if (immunizations.isEmpty()) {
            return listOf(createNoKnownImmunization(patientRef))
        }

        return immunizations
    }

    private fun createImmunization(record: VaccinationRecord, patientRef: Reference): Immunization? {
        return Immunization().apply {
            id = "imm-${safeId(record.vaccinationIdentifier?.toString() ?: UUID.randomUUID().toString())}"
            meta = Meta().addProfile(IPS_IMMUNIZATION_PROFILE)

            record.vaccinationIdentifier?.let {
                addIdentifier(Identifier()
                    .setSystem("https://www.sundhed.dk/vaccination/id")
                    .setValue(it.toString()))
            }

            status = when {
                record.negativeConsent == true -> Immunization.ImmunizationStatus.NOTDONE
                record.activeStatus == true -> Immunization.ImmunizationStatus.COMPLETED
                record.activeStatus == false -> Immunization.ImmunizationStatus.NOTDONE
                else -> Immunization.ImmunizationStatus.COMPLETED
            }

            vaccineCode = CodeableConcept().apply {
                text = record.vaccine ?: "Unknown vaccine"
            }

            record.effectuatedDateTime?.let {
                occurrence = DateTimeType(parseDate(it))
                recorded = parseDate(it)
            }

            patient = patientRef

            record.effectuatedBy?.let {
                addPerformer(Immunization.ImmunizationPerformerComponent().apply {
                    actor = Reference().apply { display = it }
                })
            }
        }
    }

    private fun createNoKnownImmunization(patientRef: Reference): Immunization {
        return Immunization().apply {
            id = "imm-no-known-${UUID.randomUUID()}"
            meta = Meta().addProfile(IPS_IMMUNIZATION_PROFILE)

            status = Immunization.ImmunizationStatus.NOTDONE

            vaccineCode = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem("http://hl7.org/fhir/uv/ips/CodeSystem/absent-unknown-uv-ips")
                    .setCode("no-immunization-info")
                    .setDisplay("No information about immunizations"))
            }

            patient = patientRef
        }
    }

    private fun createObservations(data: PatientSummaryData, patientRef: Reference): List<Observation> {
        val svaroversigt = data.observations?.svaroversigt ?: return emptyList()
        val rekById = svaroversigt.rekvisitioner.associateBy { it.id }

        return svaroversigt.laboratorieresultater.mapNotNull { result ->
            createObservation(result, rekById[result.rekvisitionsId], patientRef)
        }
    }

    private fun createObservation(
        result: Laboratorieresultat,
        rekvisition: Rekvisition?,
        patientRef: Reference
    ): Observation? {
        val undersoegelse = result.undersoegelser.firstOrNull()

        return Observation().apply {
            id = "lab-${safeId(result.rekvisitionsId ?: result.proevenummerLaboratorie ?: UUID.randomUUID().toString())}"
            meta = Meta().addProfile(IPS_OBSERVATION_PROFILE)

            result.rekvisitionsId?.let {
                addIdentifier(Identifier()
                    .setSystem("https://www.sundhed.dk/labsvar/rekvisition")
                    .setValue(it))
            }

            status = mapObservationStatus(result.resultatStatuskode, result.resultatStatus)

            category = listOf(CodeableConcept().addCoding(Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("laboratory")
                .setDisplay("Laboratory")))

            code = CodeableConcept().apply {
                text = undersoegelse?.undersoegelsesNavn
                    ?: result.analysetypeId
                    ?: result.resultattype
                    ?: result.vaerditype
                undersoegelse?.analyseKode?.let { kode ->
                    addCoding(Coding()
                        .setSystem("https://www.sundhed.dk/codes/labsvar")
                        .setCode(kode)
                        .setDisplay(undersoegelse.undersoegelsesNavn))
                }
            }

            // Set value
            extractNumericValue(undersoegelse?.quantitativeFindings)?.let { setValue(it) }
                ?: result.vaerdi?.let { setValue(StringType(it)) }

            result.referenceIntervalTekst?.let {
                referenceRange = listOf(Observation.ObservationReferenceRangeComponent().apply {
                    text = it
                })
            }

            result.resultatdato?.let { effective = DateTimeType(parseDate(it)) }
                ?: rekvisition?.proevetagningstidspunkt?.let { effective = DateTimeType(parseDate(it)) }

            subject = patientRef
        }
    }

    private fun extractNumericValue(qf: QuantitativeFindings?): Quantity? {
        val dataRows = qf?.data ?: return null
        if (dataRows.size < 2) return null
        val row = dataRows[1]
        if (row.size < 10) return null
        val value = row[9]?.toString()?.trim().orEmpty()
        if (value.isBlank() || value.equals("Ikke påvist", ignoreCase = true)) return null
        val numericValue = value.toBigDecimalOrNull() ?: return null
        return Quantity().apply {
            this.value = numericValue
            row.getOrNull(10)?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { unit = it }
        }
    }

    private fun mapObservationStatus(statusCode: String?, statusText: String?): Observation.ObservationStatus {
        return when (statusCode ?: statusText) {
            "SvarEndeligt", "KompletSvar" -> Observation.ObservationStatus.FINAL
            "Foreloebigt" -> Observation.ObservationStatus.PRELIMINARY
            "Annulleret" -> Observation.ObservationStatus.CANCELLED
            else -> Observation.ObservationStatus.UNKNOWN
        }
    }

    private fun createComposition(
        patient: Patient,
        patientRef: Reference,
        conditions: List<Condition>,
        medications: List<MedicationStatement>,
        immunizations: List<Immunization>,
        observations: List<Observation>
    ): Composition {
        return Composition().apply {
            id = "composition-${UUID.randomUUID()}"
            meta = Meta().addProfile(IPS_COMPOSITION_PROFILE)

            status = Composition.CompositionStatus.FINAL

            type = CodeableConcept().apply {
                addCoding(Coding()
                    .setSystem(LOINC_SYSTEM)
                    .setCode("60591-5")
                    .setDisplay("Patient summary Document"))
            }

            subject = patientRef
            date = Date()

            author = listOf(Reference().apply {
                display = "sundhed.dk via dhroxy"
            })

            title = "International Patient Summary"

            // Add sections
            addSection(createProblemsSection(conditions))
            addSection(createMedicationsSection(medications))
            addSection(createAllergiesSection(patientRef)) // Empty/unknown since not available in source
            addSection(createImmunizationsSection(immunizations))
            if (observations.isNotEmpty()) {
                addSection(createResultsSection(observations))
            }
        }
    }

    private fun createProblemsSection(conditions: List<Condition>): Composition.SectionComponent {
        return Composition.SectionComponent().apply {
            title = "Problem List"
            code = CodeableConcept().addCoding(Coding()
                .setSystem(LOINC_SYSTEM)
                .setCode(PROBLEMS_SECTION_CODE)
                .setDisplay("Problem list - Reported"))
            entry = conditions.map { Reference("urn:uuid:${it.id}") }
        }
    }

    private fun createMedicationsSection(medications: List<MedicationStatement>): Composition.SectionComponent {
        return Composition.SectionComponent().apply {
            title = "Medication Summary"
            code = CodeableConcept().addCoding(Coding()
                .setSystem(LOINC_SYSTEM)
                .setCode(MEDICATIONS_SECTION_CODE)
                .setDisplay("History of Medication use Narrative"))
            entry = medications.map { Reference("urn:uuid:${it.id}") }
        }
    }

    private fun createAllergiesSection(patientRef: Reference): Composition.SectionComponent {
        return Composition.SectionComponent().apply {
            title = "Allergies and Intolerances"
            code = CodeableConcept().addCoding(Coding()
                .setSystem(LOINC_SYSTEM)
                .setCode(ALLERGIES_SECTION_CODE)
                .setDisplay("Allergies and adverse reactions Document"))
            // No allergy data available from sundhed.dk - indicate as unknown
            emptyReason = CodeableConcept().addCoding(Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/list-empty-reason")
                .setCode("unavailable")
                .setDisplay("Unavailable"))
        }
    }

    private fun createImmunizationsSection(immunizations: List<Immunization>): Composition.SectionComponent {
        return Composition.SectionComponent().apply {
            title = "Immunizations"
            code = CodeableConcept().addCoding(Coding()
                .setSystem(LOINC_SYSTEM)
                .setCode(IMMUNIZATIONS_SECTION_CODE)
                .setDisplay("History of Immunization Narrative"))
            entry = immunizations.map { Reference("urn:uuid:${it.id}") }
        }
    }

    private fun createResultsSection(observations: List<Observation>): Composition.SectionComponent {
        return Composition.SectionComponent().apply {
            title = "Results"
            code = CodeableConcept().addCoding(Coding()
                .setSystem(LOINC_SYSTEM)
                .setCode(RESULTS_SECTION_CODE)
                .setDisplay("Relevant diagnostic tests/laboratory data Narrative"))
            entry = observations.map { Reference("urn:uuid:${it.id}") }
        }
    }

    private fun parseDate(dateTime: String): Date {
        return runCatching { Date.from(OffsetDateTime.parse(dateTime).toInstant()) }
            .recoverCatching {
                val dt = LocalDateTime.parse(dateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                Date.from(dt.atZone(ZoneOffset.UTC).toInstant())
            }
            .getOrElse { Date() }
    }

    private fun safeId(raw: String): String =
        raw.lowercase()
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')
            .take(64)
}
