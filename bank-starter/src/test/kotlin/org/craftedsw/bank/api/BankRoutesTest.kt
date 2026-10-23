package org.craftedsw.bank.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.bank.debug.DebugStore
import org.craftedsw.contracts.DebugReport
import org.craftedsw.contracts.HealthResponse
import org.junit.jupiter.api.Test

class BankRoutesTest {

    @Test
    fun `health endpoint should return UP and configured BIC`() = testApplication {
        application {
            bankModule(bic = "TESTBICA")
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.get("/health")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val health = response.body<HealthResponse>()
        assertThat(health.status).isEqualTo("UP")
        assertThat(health.bic).isEqualTo("TESTBICA")
    }

    @Test
    fun `root and debug endpoints should render HTML debug dashboard`() = testApplication {
        application {
            bankModule(bic = "TESTBICA", bankName = "Alpha Bank")
        }

        val response = client.get("/")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText()
        assertThat(body).contains("Alpha Bank")
        assertThat(body).contains("TESTBICA")
        assertThat(body).contains("Bank Debug Dashboard")

        val debugResponse = client.get("/debug")
        assertThat(debugResponse.status).isEqualTo(HttpStatusCode.OK)
        assertThat(debugResponse.bodyAsText()).contains("Alpha Bank")
    }

    @Test
    fun `debug API should record, retrieve, and clear reports`() = testApplication {
        val debugStore = DebugStore()
        application {
            bankModule(debugStore = debugStore)
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // Initially empty
        val initialGet = client.get("/api/debug")
        assertThat(initialGet.status).isEqualTo(HttpStatusCode.OK)
        val initialReports: List<DebugReport> = initialGet.body()
        assertThat(initialReports).isEmpty()

        // Post a debug report
        val report = DebugReport(
            id = "test-123",
            scenario = "DEPOSIT_50_EUR",
            message = "Deposit failed with 400",
            httpRequest = "POST /api/deposit",
            expectedResponse = "HTTP 200 OK",
            actualResponse = "HTTP 400 Bad Request"
        )

        val postRes = client.post("/api/debug") {
            contentType(ContentType.Application.Json)
            setBody(report)
        }
        assertThat(postRes.status).isEqualTo(HttpStatusCode.OK)

        // Retrieve reports
        val getRes = client.get("/api/debug")
        assertThat(getRes.status).isEqualTo(HttpStatusCode.OK)
        val reports: List<DebugReport> = getRes.body()
        assertThat(reports).hasSize(1)
        assertThat(reports[0].id).isEqualTo("test-123")
        assertThat(reports[0].scenario).isEqualTo("DEPOSIT_50_EUR")

        // Clear reports
        val deleteRes = client.delete("/api/debug")
        assertThat(deleteRes.status).isEqualTo(HttpStatusCode.OK)

        val afterClearGet = client.get("/api/debug")
        val clearedReports: List<DebugReport> = afterClearGet.body()
        assertThat(clearedReports).isEmpty()
    }
}
