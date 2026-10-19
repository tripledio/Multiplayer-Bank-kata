package org.craftedsw.swift.router

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.RegisterBankRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BankHealthCheckerTest {

    private lateinit var bankRegistry: BankRegistry

    @BeforeEach
    fun setUp() {
        bankRegistry = BankRegistry()
    }

    @Test
    fun `should mark bank HEALTHY and record latency when health endpoint returns 200 OK`() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/health") {
                respond(
                    content = """{"status":"UP","bic":"BANKAXXX"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        val bank = bankRegistry.register(
            RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "https://alpha.loca.lt")
        )

        val result = checker.checkBank(bank)

        assertThat(result.status).isEqualTo("HEALTHY")
        assertThat(result.pingLatencyMs).isNotNull()
        assertThat(result.statusMessage).startsWith("OK")

        val inRegistry = bankRegistry.getBank(Bic("BANKAXXX"))
        assertThat(inRegistry?.status).isEqualTo("HEALTHY")
        assertThat(inRegistry?.pingLatencyMs).isNotNull()
    }

    @Test
    fun `should fallback to ping endpoint when health returns 404 and succeed`() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/ping") {
                respond(
                    content = """{"status":"UP","bic":"BANKBXXX"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        val bank = bankRegistry.register(
            RegisterBankRequest(bic = "BANKBXXX", name = "Bank Beta", webhookUrl = "https://beta.loca.lt")
        )

        val result = checker.checkBank(bank)

        assertThat(result.status).isEqualTo("HEALTHY")
        assertThat(result.statusMessage).startsWith("OK")
    }

    @Test
    fun `should mark bank DOWN when tunnel returns 503 Service Unavailable`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "503 Service Unavailable",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        val bank = bankRegistry.register(
            RegisterBankRequest(bic = "BANKCXXX", name = "Bank Gamma", webhookUrl = "https://gamma.loca.lt")
        )

        val result = checker.checkBank(bank)

        assertThat(result.status).isEqualTo("DOWN")
        assertThat(result.pingLatencyMs).isNull()
        assertThat(result.statusMessage).isEqualTo("HTTP 503 Service Unavailable")

        val inRegistry = bankRegistry.getBank(Bic("BANKCXXX"))
        assertThat(inRegistry?.status).isEqualTo("DOWN")
        assertThat(inRegistry?.statusMessage).isEqualTo("HTTP 503 Service Unavailable")
    }

    @Test
    fun `should mark bank DOWN when tunnel returns 502 Bad Gateway`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "502 Bad Gateway",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        val bank = bankRegistry.register(
            RegisterBankRequest(bic = "BANKDXXX", name = "Bank Delta", webhookUrl = "https://delta.loca.lt")
        )

        val result = checker.checkBank(bank)

        assertThat(result.status).isEqualTo("DOWN")
        assertThat(result.statusMessage).isEqualTo("HTTP 502 Bad Gateway")
    }

    @Test
    fun `should mark bank DOWN when connection fails or throws exception`() = runTest {
        val mockEngine = MockEngine {
            throw java.net.ConnectException("Connection refused to tunnel")
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        val bank = bankRegistry.register(
            RegisterBankRequest(bic = "BANKEXXX", name = "Bank Epsilon", webhookUrl = "https://epsilon.loca.lt")
        )

        val result = checker.checkBank(bank)

        assertThat(result.status).isEqualTo("DOWN")
        assertThat(result.statusMessage).contains("Connection refused")
    }

    @Test
    fun `should check all registered banks concurrently`() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.host.contains("alpha")) {
                respond("""{"status":"UP"}""", HttpStatusCode.OK)
            } else {
                respond("503 Service Unavailable", HttpStatusCode.ServiceUnavailable)
            }
        }
        val client = HttpClient(mockEngine)
        val checker = BankHealthChecker(bankRegistry, client)

        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Alpha", webhookUrl = "https://alpha.loca.lt"))
        bankRegistry.register(RegisterBankRequest(bic = "BANKBXXX", name = "Beta", webhookUrl = "https://beta.loca.lt"))

        val results = checker.checkAllBanks()

        assertThat(results).hasSize(2)
        val alpha = results.first { it.bic == "BANKAXXX" }
        val beta = results.first { it.bic == "BANKBXXX" }

        assertThat(alpha.status).isEqualTo("HEALTHY")
        assertThat(beta.status).isEqualTo("DOWN")
    }
}
