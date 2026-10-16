package org.craftedsw.swift.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.BalanceResponse
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.ErrorResponse
import org.craftedsw.contracts.RegisterBankRequest
import org.craftedsw.contracts.StatementLineDto
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrafficSimulatorTest {

    private lateinit var bankRegistry: BankRegistry
    private lateinit var transferRouter: TransferRouter
    private lateinit var simulator: TrafficSimulator

    @BeforeEach
    fun setUp() {
        bankRegistry = BankRegistry()
        transferRouter = mockk(relaxed = true)
        simulator = TrafficSimulator(bankRegistry, transferRouter)
    }

    @Test
    fun `should not simulate transfer if no banks registered`() = runTest {
        val result = simulator.simulateSingleTransfer()
        assertThat(result).isFalse()
        coVerify(exactly = 0) { transferRouter.routeTransfer(any()) }
    }

    @Test
    fun `should simulate transfer between registered banks`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank A", webhookUrl = "http://a:8080"))
        bankRegistry.register(RegisterBankRequest(bic = "BANKBXXX", name = "Bank B", webhookUrl = "http://b:8080"))

        coEvery { transferRouter.routeTransfer(any()) } returns TransferResult("tx-1", TransferStatus.ACCEPTED, "OK")

        val result = simulator.simulateSingleTransfer()
        assertThat(result).isTrue()
        coVerify(atLeast = 1) { transferRouter.routeTransfer(any()) }
    }

    @Test
    fun `should start and stop simulator`() {
        simulator.start(100)
        assertThat(simulator.isSimulatorRunning()).isTrue()

        simulator.stop()
        assertThat(simulator.isSimulatorRunning()).isFalse()
    }

    @Test
    fun `should execute positive deposit scenario successfully and assert 50 euro balance`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.endsWith("/api/deposit")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 5000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.contains("/api/statement")) {
                respond(
                    content = Json.encodeToString(
                        StatementResponse(
                            iban = "BE68BANKA0001234567",
                            lines = listOf(StatementLineDto(date = "16/10/2026", credit = "50.00", balance = "50.00")),
                            currentBalanceCents = 5000L
                        )
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond(content = "Not Found", status = HttpStatusCode.NotFound)
            }
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runPositiveDepositScenario()

        assertThat(results).hasSize(1)
        assertThat(results.first().passed).isTrue()
        assertThat(results.first().bic).isEqualTo("BANKAXXX")
        assertThat(results.first().scenario).isEqualTo("DEPOSIT_50_EUR")
        assertThat(bankRegistry.getBank(Bic("BANKAXXX"))?.totalMoneyCents).isEqualTo(5000L)
    }

    @Test
    fun `should execute negative deposit scenario successfully when bank returns error and does not end up with 0 euro`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.endsWith("/api/deposit")) {
                respond(
                    content = Json.encodeToString(ErrorResponse(error = "Deposit amount must be positive")),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.contains("/api/statement")) {
                respond(
                    content = Json.encodeToString(
                        StatementResponse(
                            iban = "BE68BANKA0001234567",
                            lines = emptyList(),
                            currentBalanceCents = 0L
                        )
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond(content = "Not Found", status = HttpStatusCode.NotFound)
            }
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runNegativeDepositScenario()

        assertThat(results).hasSize(1)
        assertThat(results.first().passed).isTrue()
        assertThat(results.first().bic).isEqualTo("BANKAXXX")
        assertThat(results.first().scenario).isEqualTo("DEPOSIT_NEGATIVE_50_EUR")
    }

    @Test
    fun `negative deposit scenario should fail if bank accepts negative deposit`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            respond(
                content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = -5000L)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runNegativeDepositScenario()

        assertThat(results).hasSize(1)
        assertThat(results.first().passed).isFalse()
    }
}
