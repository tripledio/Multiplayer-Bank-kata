package org.craftedsw.swift.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.BankNodeInfo
import org.craftedsw.contracts.HealthResponse
import org.craftedsw.contracts.LedgerResponse
import org.craftedsw.contracts.RegisterBankRequest
import org.craftedsw.contracts.RegisterBankResponse
import org.craftedsw.contracts.ScoreboardState
import org.craftedsw.contracts.SimulatorStatusResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.junit.jupiter.api.Test

class SwiftRoutesTest {

    @Test
    fun `health endpoint should return UP and SWIFTHUB bic`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/health")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<HealthResponse>()
        assertThat(body.status).isEqualTo("UP")
        assertThat(body.bic).isEqualTo("SWIFTHUB")
    }

    @Test
    fun `register bank should add bank to registry and return confirmation`() = testApplication {
        val registry = BankRegistry()
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/swift/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "https://alpha.loca.lt"))
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<RegisterBankResponse>()
        assertThat(body.status).isEqualTo("REGISTERED")
        assertThat(body.bic).isEqualTo("BANKAXXX")

        val banksResponse = client.get("/swift/banks")
        val banks = banksResponse.body<List<BankNodeInfo>>()
        assertThat(banks).hasSize(1)
        assertThat(banks.first().name).isEqualTo("Bank Alpha")
    }

    @Test
    fun `transfer should reject if destination bank not registered`() = testApplication {
        val registry = BankRegistry()
        val ledger = AuditLedger()
        application {
            swiftHubModule(bankRegistry = registry, auditLedger = ledger)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val transfer = TransferRequest(
            transactionId = "tx-123",
            fromIban = "BE68BANKA0001111111",
            toIban = "BE68UNKN0002222222",
            amountCents = 10000
        )

        val response = client.post("/swift/transfers") {
            contentType(ContentType.Application.Json)
            setBody(transfer)
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        val result = response.body<TransferResult>()
        assertThat(result.status).isEqualTo(TransferStatus.REJECTED)
        assertThat(result.message).contains("not registered")
    }

    @Test
    fun `metrics endpoint should return network summary`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://localhost:8080"))
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/swift/metrics")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val scoreboard = response.body<ScoreboardState>()
        assertThat(scoreboard.banks).hasSize(1)
        assertThat(scoreboard.metrics.activeBanksCount).isEqualTo(1)
    }

    @Test
    fun `simulator endpoints should start, burst, and stop`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val startRes = client.post("/swift/simulator/start?intervalMs=1000")
        assertThat(startRes.status).isEqualTo(HttpStatusCode.OK)
        val startBody = startRes.body<SimulatorStatusResponse>()
        assertThat(startBody.status).isEqualTo("RUNNING")
        assertThat(startBody.intervalMs).isEqualTo(1000L)

        val burstRes = client.post("/swift/simulator/burst?count=3")
        assertThat(burstRes.status).isEqualTo(HttpStatusCode.OK)
        val burstBody = burstRes.body<SimulatorStatusResponse>()
        assertThat(burstBody.status).isEqualTo("BURST_EXECUTED")

        val stopRes = client.post("/swift/simulator/stop")
        assertThat(stopRes.status).isEqualTo(HttpStatusCode.OK)
        val stopBody = stopRes.body<SimulatorStatusResponse>()
        assertThat(stopBody.status).isEqualTo("STOPPED")
    }

    @Test
    fun `ledger endpoint should return serializable ledger response with entries`() = testApplication {
        val ledger = AuditLedger()
        ledger.record(
            TransferRequest(
                transactionId = "tx-1",
                fromIban = "BE68BANKA0001111111",
                toIban = "BE68BANKB0002222222",
                amountCents = 50000
            ),
            TransferResult("tx-1", TransferStatus.ACCEPTED, "Settled")
        )

        application {
            swiftHubModule(auditLedger = ledger)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/swift/ledger")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val ledgerResponse = response.body<LedgerResponse>()
        assertThat(ledgerResponse.totalSettledVolumeCents).isEqualTo(50000L)
        assertThat(ledgerResponse.totalSuccessfulTransactions).isEqualTo(1L)
        assertThat(ledgerResponse.totalFailedTransactions).isEqualTo(0L)
        assertThat(ledgerResponse.isConservationOfMoneyVerified).isTrue()
        assertThat(ledgerResponse.recentEntries).hasSize(1)
        assertThat(ledgerResponse.recentEntries.first().transactionId).isEqualTo("tx-1")
    }

    @Test
    fun `root and scoreboard endpoints should serve HTML dashboard`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient { }
        val rootRes = client.get("/")
        assertThat(rootRes.status).isEqualTo(HttpStatusCode.OK)
        val rootHtml = rootRes.body<String>()
        assertThat(rootHtml).contains("SWIFT Network Clearing House")

        val scoreboardRes = client.get("/scoreboard")
        assertThat(scoreboardRes.status).isEqualTo(HttpStatusCode.OK)
        val scoreboardHtml = scoreboardRes.body<String>()
        assertThat(scoreboardHtml).contains("SWIFT Network Clearing House")
        assertThat(scoreboardHtml).contains("Register Your Bank")
        assertThat(scoreboardHtml).contains("qrcode")
    }

    @Test
    fun `registration page endpoints should serve HTML registration page`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient { }
        val registerRes = client.get("/register")
        assertThat(registerRes.status).isEqualTo(HttpStatusCode.OK)
        val registerHtml = registerRes.body<String>()
        assertThat(registerHtml).contains("Register Your Bank")
        assertThat(registerHtml).contains("Bank Name")
        assertThat(registerHtml).contains("Webhook URL")

        val swiftRegisterRes = client.get("/swift/register")
        assertThat(swiftRegisterRes.status).isEqualTo(HttpStatusCode.OK)
        val swiftRegisterHtml = swiftRegisterRes.body<String>()
        assertThat(swiftRegisterHtml).contains("Register Your Bank")
    }

    @Test
    fun `register bank without BIC should generate 8-character ASCII BIC`() = testApplication {
        val registry = BankRegistry()
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/swift/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterBankRequest(name = "Bank Gamma", webhookUrl = "https://gamma.loca.lt"))
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<RegisterBankResponse>()
        assertThat(body.status).isEqualTo("REGISTERED")
        assertThat(body.bic).hasSize(8)
        assertThat(body.bic).matches("^[A-Z0-9]{8}$")
        assertThat(body.bic).startsWith("BANK")
        assertThat(body.message).contains("Bank Gamma")

        val banks = client.get("/swift/banks").body<List<BankNodeInfo>>()
        assertThat(banks).hasSize(1)
        assertThat(banks.first().bic).isEqualTo(body.bic)
        assertThat(banks.first().name).isEqualTo("Bank Gamma")
    }

    @Test
    fun `post to register endpoint alias should register bank successfully`() = testApplication {
        val registry = BankRegistry()
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterBankRequest(name = "Delta Bank", webhookUrl = "https://delta.loca.lt"))
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<RegisterBankResponse>()
        assertThat(body.status).isEqualTo("REGISTERED")
        assertThat(body.bic).hasSize(8)
        assertThat(body.bic).startsWith("DELT")
    }
}
