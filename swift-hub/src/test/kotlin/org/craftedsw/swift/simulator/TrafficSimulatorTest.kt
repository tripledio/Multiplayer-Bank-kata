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
        assertThat(results.first().expected).contains("Balance 50.00 EUR")
        assertThat(results.first().actual).contains("Balance 50.00 EUR")
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
        assertThat(results.first().expected).contains("Expected: Error")
        assertThat(results.first().actual).contains("Actual: HTTP 400")
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
        assertThat(results.first().expected).contains("Expected: Error")
        assertThat(results.first().actual).contains("Actual: Deposit accepted with HTTP 200 OK")
    }

    @Test
    fun `negative deposit scenario should fail and mark bank DOWN when tunnel returns 503`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKDOWN", name = "Bank Down", webhookUrl = "http://down:8080"))

        val mockEngine = MockEngine {
            respond(
                content = "503 Service Unavailable",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runNegativeDepositScenario()

        assertThat(results).hasSize(1)
        assertThat(results.first().passed).isFalse()
        assertThat(results.first().message).contains("503")

        val bankNode = bankRegistry.getBank(Bic("BANKDOWN"))
        assertThat(bankNode?.status).isEqualTo("DOWN")
        assertThat(bankNode?.statusMessage).isEqualTo("HTTP 503 Service Unavailable")
    }

    @Test
    fun `negative withdrawal scenario should fail and mark bank DOWN when tunnel returns 502`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANK502X", name = "Bank 502", webhookUrl = "http://down:8080"))

        val mockEngine = MockEngine {
            respond(
                content = "502 Bad Gateway",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runNegativeWithdrawScenario()

        assertThat(results).hasSize(1)
        assertThat(results.first().passed).isFalse()
        assertThat(results.first().message).contains("502")

        val bankNode = bankRegistry.getBank(Bic("BANK502X"))
        assertThat(bankNode?.status).isEqualTo("DOWN")
        assertThat(bankNode?.statusMessage).isEqualTo("HTTP 502 Bad Gateway")
    }

    @Test
    fun `should execute deposit and withdraw scenario successfully comparing ledger to actual statement`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.endsWith("/api/deposit")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 5000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.endsWith("/api/withdraw")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 3000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.contains("/api/statement")) {
                respond(
                    content = Json.encodeToString(
                        StatementResponse(
                            iban = "BE68BANKA0001234567",
                            lines = listOf(
                                StatementLineDto(date = "16/10/2026", debit = "20.00", balance = "30.00"),
                                StatementLineDto(date = "16/10/2026", credit = "50.00", balance = "50.00")
                            ),
                            currentBalanceCents = 3000L
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
        val results = sim.runDepositWithdrawScenario()

        assertThat(results).hasSize(1)
        val r = results.first()
        assertThat(r.passed).isTrue()
        assertThat(r.scenario).isEqualTo("WITHDRAW_HAPPY_20_EUR")
        assertThat(r.expected).contains("Balance 30.00 EUR | Statement (2 lines)")
        assertThat(r.actual).contains("Balance 30.00 EUR | Statement (2 lines)")
    }

    @Test
    fun `deposit and withdraw scenario should fail if bank statement lines do not match expected ledger`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.endsWith("/api/deposit")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 5000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.endsWith("/api/withdraw")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 3000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.contains("/api/statement")) {
                // Bank returns incorrect statement lines
                respond(
                    content = Json.encodeToString(
                        StatementResponse(
                            iban = "BE68BANKA0001234567",
                            lines = listOf(
                                StatementLineDto(date = "16/10/2026", credit = "10.00", balance = "10.00")
                            ),
                            currentBalanceCents = 3000L
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
        val results = sim.runDepositWithdrawScenario()

        assertThat(results).hasSize(1)
        val r = results.first()
        assertThat(r.passed).isFalse()
        assertThat(r.expected).contains("Balance 30.00 EUR | Statement (2 lines)")
        assertThat(r.actual).contains("Balance 30.00 EUR | Statement (1 line)")
    }

    @Test
    fun `should execute intra-bank transfer scenario successfully`() = runTest {
        bankRegistry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha:8080"))

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.endsWith("/api/deposit")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 5000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.endsWith("/api/withdraw")) {
                respond(
                    content = Json.encodeToString(BalanceResponse(iban = "BE68BANKA0001234567", balanceCents = 3000L)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.endsWith("/api/transfer-in")) {
                respond(
                    content = Json.encodeToString(TransferResult("tx-1", TransferStatus.ACCEPTED, "OK")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (url.contains("/api/statement")) {
                val isFrom = request.url.toString().contains("0001") || request.url.toString().contains("0002") || request.url.toString().contains("0003") || request.url.toString().contains("0004")
                if (isFrom) {
                    respond(
                        content = Json.encodeToString(
                            StatementResponse(
                                iban = "BE68BANKA0001234567",
                                lines = listOf(
                                    StatementLineDto(date = "16/10/2026", debit = "20.00", balance = "30.00"),
                                    StatementLineDto(date = "16/10/2026", credit = "50.00", balance = "50.00")
                                ),
                                currentBalanceCents = 3000L
                            )
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                } else {
                    respond(
                        content = Json.encodeToString(
                            StatementResponse(
                                iban = "BE68BANKA0005678901",
                                lines = listOf(
                                    StatementLineDto(date = "16/10/2026", credit = "20.00", balance = "20.00")
                                ),
                                currentBalanceCents = 2000L
                            )
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            } else {
                respond(content = "Not Found", status = HttpStatusCode.NotFound)
            }
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json() }
        }

        val sim = TrafficSimulator(bankRegistry, transferRouter, mockHttpClient)
        val results = sim.runIntraBankTransferScenario()

        assertThat(results).hasSize(1)
        val r = results.first()
        assertThat(r.passed).isTrue()
        assertThat(r.scenario).isEqualTo("INTRA_BANK_TRANSFER")
    }

    @Test
    fun `should support phase start stop toggle and report status`() {
        val sim = TrafficSimulator(bankRegistry, transferRouter)
        val initialStatus = sim.getPhasesStatus()
        assertThat(initialStatus.deposits.running).isFalse()
        assertThat(initialStatus.withdrawals.running).isFalse()
        assertThat(initialStatus.transfers.running).isFalse()

        sim.togglePhase("DEPOSITS")
        assertThat(sim.getPhasesStatus().deposits.running).isTrue()

        sim.togglePhase("DEPOSITS")
        assertThat(sim.getPhasesStatus().deposits.running).isFalse()
    }

    @Test
    fun `ScenarioLedger should track running balance and expected statement lines`() {
        val ledger = ScenarioLedger("BE68BANKA0001234567")
        ledger.recordDeposit(5000L)
        ledger.recordWithdraw(2000L)

        assertThat(ledger.expectedBalanceCents).isEqualTo(3000L)
        assertThat(ledger.shouldAccountExist).isTrue()

        val lines = ledger.getExpectedStatementLines()
        assertThat(lines).hasSize(2)
        // Reverse chronological order
        assertThat(lines[0].debit).isEqualTo("20.00")
        assertThat(lines[0].credit).isNull()
        assertThat(lines[0].balance).isEqualTo("30.00")

        assertThat(lines[1].credit).isEqualTo("50.00")
        assertThat(lines[1].debit).isNull()
        assertThat(lines[1].balance).isEqualTo("50.00")

        assertThat(ledger.formatExpectedSummary()).contains("Expected: Balance 30.00 EUR | Statement (2 lines)")
    }
}
