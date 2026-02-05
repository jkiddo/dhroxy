package dhroxy.conformance

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import dhroxy.controller.support.ProviderTestConfig
import org.hl7.fhir.r4.model.CapabilityStatement
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.io.File

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ProviderTestConfig::class)
class IpaConformanceTest {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: IGenericClient

    private val log = LoggerFactory.getLogger(IpaConformanceTest::class.java)

    @BeforeEach
    fun setup() {
        client = FhirContext.forR4().newRestfulGenericClient("http://localhost:$port/fhir")
    }

    data class CheckResult(val resource: String, val check: String, val conformance: String, val passed: Boolean)

    @Test
    fun `verify IPA conformance and generate report`() {
        val capabilityStatement = client.capabilities().ofType(CapabilityStatement::class.java).execute()
        val serverRest = capabilityStatement.rest.firstOrNull { it.mode == RestfulCapabilityMode.SERVER }
        val resources = serverRest?.resource ?: emptyList()

        val results = mutableListOf<CheckResult>()

        // Build a lookup map: resource type -> CapabilityStatement.rest.resource entry
        val resourceMap = resources.associateBy { it.type }

        // --- Patient (SHALL) ---
        results += checkResource(resourceMap, "Patient", "SHALL") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "_id"),
                checkSearchParam(res, "identifier"),
            )
        }

        // --- AllergyIntolerance (SHOULD) ---
        results += checkResource(resourceMap, "AllergyIntolerance", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
            )
        }

        // --- Condition (SHOULD) ---
        results += checkResource(resourceMap, "Condition", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
            )
        }

        // --- DocumentReference (SHOULD) ---
        results += checkResource(resourceMap, "DocumentReference", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "_id"),
                checkSearchParam(res, "patient"),
                checkOperation(res, "\$docref"),
            )
        }

        // --- Immunization (SHOULD) ---
        results += checkResource(resourceMap, "Immunization", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
            )
        }

        // --- Medication (SHOULD) ---
        results += checkResource(resourceMap, "Medication", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
            )
        }

        // --- MedicationRequest (SHOULD) ---
        results += checkResource(resourceMap, "MedicationRequest", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
            )
        }

        // --- MedicationStatement (SHOULD) ---
        results += checkResource(resourceMap, "MedicationStatement", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
            )
        }

        // --- Observation (SHOULD) ---
        results += checkResource(resourceMap, "Observation", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
                checkInteraction(res, "search-type"),
                checkSearchParam(res, "patient"),
                checkSearchParam(res, "category"),
                checkSearchParam(res, "code"),
            )
        }

        // --- Practitioner (SHOULD) ---
        results += checkResource(resourceMap, "Practitioner", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
            )
        }

        // --- PractitionerRole (SHOULD) ---
        results += checkResource(resourceMap, "PractitionerRole", "SHOULD") { res ->
            listOf(
                checkInteraction(res, "read"),
            )
        }

        // Log warnings for failures
        val failures = results.filter { !it.passed }
        if (failures.isNotEmpty()) {
            log.warn("IPA conformance gaps found: ${failures.size} of ${results.size} checks failed")
            failures.forEach { log.warn("  FAIL [${it.conformance}] ${it.resource}: ${it.check}") }
        } else {
            log.info("All ${results.size} IPA conformance checks passed")
        }

        // Write markdown report
        writeReport(results)
    }

    private fun checkResource(
        resourceMap: Map<String, CapabilityStatement.CapabilityStatementRestResourceComponent>,
        resourceType: String,
        conformance: String,
        detailChecks: (CapabilityStatement.CapabilityStatementRestResourceComponent) -> List<CheckResult>,
    ): List<CheckResult> {
        val res = resourceMap[resourceType]
        val results = mutableListOf<CheckResult>()
        results += CheckResult(resourceType, "resource listed", conformance, res != null)
        if (res != null) {
            results += detailChecks(res)
        } else {
            // If resource is missing, skip detail checks — the "resource listed" failure covers it
        }
        return results
    }

    private fun checkInteraction(
        res: CapabilityStatement.CapabilityStatementRestResourceComponent,
        interactionCode: String,
    ): CheckResult {
        val found = res.interaction.any { it.code?.toCode() == interactionCode }
        return CheckResult(res.type, "interaction: $interactionCode", "", found)
    }

    private fun checkSearchParam(
        res: CapabilityStatement.CapabilityStatementRestResourceComponent,
        paramName: String,
    ): CheckResult {
        val found = res.searchParam.any { it.name == paramName }
        return CheckResult(res.type, "search param: $paramName", "", found)
    }

    private fun checkOperation(
        res: CapabilityStatement.CapabilityStatementRestResourceComponent,
        operationName: String,
    ): CheckResult {
        val found = res.operation.any { it.name == operationName || it.name == operationName.removePrefix("\$") }
        return CheckResult(res.type, "operation: $operationName", "", found)
    }

    private fun writeReport(results: List<CheckResult>) {
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        val total = results.size

        val sb = StringBuilder()
        sb.appendLine("# IPA Conformance Report")
        sb.appendLine()
        sb.appendLine("**Date:** ${java.time.LocalDateTime.now()}")
        sb.appendLine("**Total checks:** $total | **Passed:** $passed | **Failed:** $failed")
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()
        sb.appendLine("| Resource | Check | Conformance | Status |")
        sb.appendLine("|----------|-------|-------------|--------|")

        results.forEach { r ->
            val status = if (r.passed) "PASS" else "FAIL"
            val conformance = r.conformance.ifEmpty { "" }
            sb.appendLine("| ${r.resource} | ${r.check} | $conformance | $status |")
        }

        sb.appendLine()
        if (failed > 0) {
            sb.appendLine("## Gaps")
            sb.appendLine()
            results.filter { !it.passed }.forEach { r ->
                val tag = if (r.conformance.isNotEmpty()) " (${r.conformance})" else ""
                sb.appendLine("- **${r.resource}**: ${r.check}$tag")
            }
        } else {
            sb.appendLine("All IPA conformance checks passed.")
        }

        val reportDir = File("build/reports")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "ipa-conformance.md")
        reportFile.writeText(sb.toString())
        log.info("IPA conformance report written to ${reportFile.absolutePath}")
    }
}
