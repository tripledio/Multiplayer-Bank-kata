package org.craftedsw.swift.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
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
import org.craftedsw.contracts.UpdateBankUrlRequest
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankHealthChecker
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
    fun `register bank should add bank to registry and return confirmation without auth`() = testApplication {
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
    fun `transfer should reject if destination bank not registered without requiring auth`() = testApplication {
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
    fun `unauthenticated access to facilitator routes should return 401 Unauthorized`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val rootRes = client.get("/")
        assertThat(rootRes.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(rootRes.headers[HttpHeaders.WWWAuthenticate]).contains("Basic")

        val scoreboardRes = client.get("/scoreboard")
        assertThat(scoreboardRes.status).isEqualTo(HttpStatusCode.Unauthorized)

        val metricsRes = client.get("/swift/metrics")
        assertThat(metricsRes.status).isEqualTo(HttpStatusCode.Unauthorized)

        val simulatorStatusRes = client.get("/swift/simulator/status")
        assertThat(simulatorStatusRes.status).isEqualTo(HttpStatusCode.Unauthorized)

        val simStartRes = client.post("/swift/simulator/start")
        assertThat(simStartRes.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `wrong credentials should return 401 Unauthorized`() = testApplication {
        application {
            swiftHubModule(credentials = FacilitatorCredentials("admin", "secret123"))
        }

        val client = createClient { }
        val response = client.get("/scoreboard") {
            basicAuth("admin", "wrong-password")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `metrics endpoint should return network summary with basic auth`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://localhost:8080"))
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/swift/metrics") {
            basicAuth("admin", "admin")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val scoreboard = response.body<ScoreboardState>()
        assertThat(scoreboard.banks).hasSize(1)
        assertThat(scoreboard.metrics.activeBanksCount).isEqualTo(1)
        assertThat(scoreboard.graph.nodes).hasSize(1)
        assertThat(scoreboard.graph.nodes.first().bic).isEqualTo("BANKAXXX")
    }

    @Test
    fun `graph endpoint should return bank nodes and transfer edges when authenticated`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://localhost:8080"))
        registry.register(RegisterBankRequest(bic = "BANKBXXX", name = "Bank Beta", webhookUrl = "http://localhost:8081"))

        val ledger = AuditLedger()
        ledger.record(
            TransferRequest(
                transactionId = "tx-1",
                fromIban = "BE68BANKA0001111111",
                toIban = "BE68BANKB0002222222",
                amountCents = 7500
            ),
            TransferResult("tx-1", TransferStatus.ACCEPTED, "Settled")
        )

        application {
            swiftHubModule(bankRegistry = registry, auditLedger = ledger)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/swift/graph") {
            basicAuth("admin", "admin")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val graph = response.body<org.craftedsw.contracts.BankGraph>()
        assertThat(graph.nodes).hasSize(2)
        assertThat(graph.edges).hasSize(1)
        val edge = graph.edges.first()
        assertThat(edge.sourceBic).isEqualTo("BANKAXXX")
        assertThat(edge.targetBic).isEqualTo("BANKBXXX")
        assertThat(edge.totalAmountCents).isEqualTo(7500L)
        assertThat(edge.successfulCount).isEqualTo(1L)
    }

    @Test
    fun `simulator endpoints should start, burst, and stop with basic auth`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val startRes = client.post("/swift/simulator/start?intervalMs=1000") {
            basicAuth("admin", "admin")
        }
        assertThat(startRes.status).isEqualTo(HttpStatusCode.OK)
        val startBody = startRes.body<SimulatorStatusResponse>()
        assertThat(startBody.status).isEqualTo("RUNNING")
        assertThat(startBody.intervalMs).isEqualTo(1000L)

        val runningStatus = client.get("/swift/simulator/status") {
            basicAuth("admin", "admin")
        }.body<SimulatorStatusResponse>()
        assertThat(runningStatus.status).isEqualTo("RUNNING")
        assertThat(runningStatus.intervalMs).isEqualTo(1000L)

        val burstRes = client.post("/swift/simulator/burst?count=3") {
            basicAuth("admin", "admin")
        }
        assertThat(burstRes.status).isEqualTo(HttpStatusCode.OK)
        val burstBody = burstRes.body<SimulatorStatusResponse>()
        assertThat(burstBody.status).isEqualTo("BURST_EXECUTED")

        val stopRes = client.post("/swift/simulator/stop") {
            basicAuth("admin", "admin")
        }
        assertThat(stopRes.status).isEqualTo(HttpStatusCode.OK)
        val stopBody = stopRes.body<SimulatorStatusResponse>()
        assertThat(stopBody.status).isEqualTo("STOPPED")

        val stoppedStatus = client.get("/swift/simulator/status") {
            basicAuth("admin", "admin")
        }.body<SimulatorStatusResponse>()
        assertThat(stoppedStatus.status).isEqualTo("STOPPED")
    }

    @Test
    fun `ledger endpoint should return serializable ledger response with entries when authenticated`() = testApplication {
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

        val response = client.get("/swift/ledger") {
            basicAuth("admin", "admin")
        }
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
    fun `root and scoreboard endpoints should serve HTML dashboard when authenticated`() = testApplication {
        application {
            swiftHubModule(credentials = FacilitatorCredentials("facilitator", "workshop2026"))
        }

        val client = createClient { }
        val rootRes = client.get("/") {
            basicAuth("facilitator", "workshop2026")
        }
        assertThat(rootRes.status).isEqualTo(HttpStatusCode.OK)
        val rootHtml = rootRes.body<String>()
        assertThat(rootHtml).contains("SWIFT Network Clearing House")

        val scoreboardRes = client.get("/scoreboard") {
            basicAuth("facilitator", "workshop2026")
        }
        assertThat(scoreboardRes.status).isEqualTo(HttpStatusCode.OK)
        val scoreboardHtml = scoreboardRes.body<String>()
        assertThat(scoreboardHtml).contains("SWIFT Network Clearing House")
        assertThat(scoreboardHtml).contains("Register Your Bank")
        assertThat(scoreboardHtml).contains("qrcode")
        assertThat(scoreboardHtml).contains("depositMoneyLineChart")
        assertThat(scoreboardHtml).contains("withdrawalMoneyLineChart")
        assertThat(scoreboardHtml).contains("pointsLineChart")
        assertThat(scoreboardHtml).contains("chart.js")
        assertThat(scoreboardHtml).contains("Deposits Money Timeline")
        assertThat(scoreboardHtml).contains("Withdrawals Money Timeline")
        assertThat(scoreboardHtml).contains("Participant Points Evolution")
        assertThat(scoreboardHtml).contains("Scenario 1 (Hidden)")
        assertThat(scoreboardHtml).contains("isScenarioGreenForAll")
        assertThat(scoreboardHtml).contains("getScenarioDisplayName")
        assertThat(scoreboardHtml).contains("Network health")
        assertThat(scoreboardHtml).contains("Edit URL")
        assertThat(scoreboardHtml).contains("editTunnelModal")
        assertThat(scoreboardHtml).contains("Transfer Network Graph")
        assertThat(scoreboardHtml).contains("Live Settlement Feed")
    }

    @Test
    fun `registration page endpoints should serve HTML registration page without auth`() = testApplication {
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
    fun `register bank without BIC should generate 8-character ASCII BIC without auth`() = testApplication {
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
    fun `post to register endpoint alias should register bank successfully without auth`() = testApplication {
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

    @Test
    fun `deposit scenario endpoints should return scenario execution status when authenticated`() = testApplication {
        application {
            swiftHubModule()
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val posRes = client.post("/swift/simulator/scenario/deposit-positive") {
            basicAuth("admin", "admin")
        }
        assertThat(posRes.status).isEqualTo(HttpStatusCode.OK)
        val posBody = posRes.body<SimulatorStatusResponse>()
        assertThat(posBody.status).isEqualTo("SCENARIO_EXECUTED")
        assertThat(posBody.results).isNotNull

        val negRes = client.post("/swift/simulator/scenario/deposit-negative") {
            basicAuth("admin", "admin")
        }
        assertThat(negRes.status).isEqualTo(HttpStatusCode.OK)
        val negBody = negRes.body<SimulatorStatusResponse>()
        assertThat(negBody.status).isEqualTo("SCENARIO_EXECUTED")
        assertThat(negBody.results).isNotNull

        val allRes = client.post("/swift/simulator/scenario/deposit") {
            basicAuth("admin", "admin")
        }
        assertThat(allRes.status).isEqualTo(HttpStatusCode.OK)
        val allBody = allRes.body<SimulatorStatusResponse>()
        assertThat(allBody.status).isEqualTo("SCENARIO_EXECUTED")
        assertThat(allBody.results).isNotNull

        val withdrawRes = client.post("/swift/simulator/scenario/withdraw") {
            basicAuth("admin", "admin")
        }
        assertThat(withdrawRes.status).isEqualTo(HttpStatusCode.OK)
        val withdrawBody = withdrawRes.body<SimulatorStatusResponse>()
        assertThat(withdrawBody.status).isEqualTo("SCENARIO_EXECUTED")
        assertThat(withdrawBody.results).isNotNull

        val runAllRes = client.post("/swift/simulator/scenario/all") {
            basicAuth("admin", "admin")
        }
        assertThat(runAllRes.status).isEqualTo(HttpStatusCode.OK)
        val runAllBody = runAllRes.body<SimulatorStatusResponse>()
        assertThat(runAllBody.status).isEqualTo("SCENARIO_EXECUTED")
        assertThat(runAllBody.results).isNotNull
    }

    @Test
    fun `delete bank should remove it from registry and return 404 when unknown when authenticated`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "https://alpha.loca.lt"))
        application {
            swiftHubModule(bankRegistry = registry)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.delete("/swift/banks/BANKAXXX") {
            basicAuth("admin", "admin")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.body<BankNodeInfo>().name).isEqualTo("Bank Alpha")
        assertThat(client.get("/swift/banks").body<List<BankNodeInfo>>()).isEmpty()

        assertThat(client.delete("/swift/banks/BANKAXXX") {
            basicAuth("admin", "admin")
        }.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `FacilitatorCredentials resolution supports multiple env variable conventions`() {
        // Default when no env vars
        val defaultCreds = FacilitatorCredentials.fromEnv { null }
        assertThat(defaultCreds.username).isEqualTo("admin")
        assertThat(defaultCreds.password).isEqualTo("admin")

        // Single FACILITATOR_AUTH with user:pass
        val userPass = FacilitatorCredentials.fromEnv { key ->
            if (key == "FACILITATOR_AUTH") "facilitator:supersecret" else null
        }
        assertThat(userPass.username).isEqualTo("facilitator")
        assertThat(userPass.password).isEqualTo("supersecret")

        // Single FACILITATOR_AUTH with just password (username = password)
        val sameUserPass = FacilitatorCredentials.fromEnv { key ->
            if (key == "FACILITATOR_AUTH") "mypassword" else null
        }
        assertThat(sameUserPass.username).isEqualTo("mypassword")
        assertThat(sameUserPass.password).isEqualTo("mypassword")

        // FACILITATOR_USERNAME and FACILITATOR_PASSWORD
        val separateVars = FacilitatorCredentials.fromEnv { key ->
            when (key) {
                "FACILITATOR_USERNAME" -> "moderator"
                "FACILITATOR_PASSWORD" -> "modpass"
                else -> null
            }
        }
        assertThat(separateVars.username).isEqualTo("moderator")
        assertThat(separateVars.password).isEqualTo("modpass")

        // Only password set (username matches password)
        val onlyPass = FacilitatorCredentials.fromEnv { key ->
            if (key == "FACILITATOR_PASSWORD") "secretkey" else null
        }
        assertThat(onlyPass.username).isEqualTo("secretkey")
        assertThat(onlyPass.password).isEqualTo("secretkey")
    }

    @Test
    fun `update bank webhook url endpoints should update url and require authentication`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "https://alpha-old.loca.lt"))
        val mockEngine = MockEngine {
            respond("""{"status":"UP"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val checker = BankHealthChecker(registry, HttpClient(mockEngine))
        application {
            swiftHubModule(bankRegistry = registry, bankHealthChecker = checker)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Unauthenticated PUT should fail
        val unauthRes = client.put("/swift/banks/BANKAXXX/url") {
            contentType(ContentType.Application.Json)
            setBody(UpdateBankUrlRequest(webhookUrl = "https://alpha-new.loca.lt"))
        }
        assertThat(unauthRes.status).isEqualTo(HttpStatusCode.Unauthorized)

        // Authenticated PUT
        val putRes = client.put("/swift/banks/BANKAXXX/url") {
            basicAuth("admin", "admin")
            contentType(ContentType.Application.Json)
            setBody(UpdateBankUrlRequest(webhookUrl = "https://alpha-new.loca.lt/"))
        }
        assertThat(putRes.status).isEqualTo(HttpStatusCode.OK)
        val updated = putRes.body<BankNodeInfo>()
        assertThat(updated.webhookUrl).isEqualTo("https://alpha-new.loca.lt")
        assertThat(updated.status).isEqualTo("HEALTHY")

        // Authenticated PATCH alias
        val patchRes = client.patch("/swift/banks/BANKAXXX") {
            basicAuth("admin", "admin")
            contentType(ContentType.Application.Json)
            setBody(UpdateBankUrlRequest(webhookUrl = "https://alpha-patched.loca.lt"))
        }
        assertThat(patchRes.status).isEqualTo(HttpStatusCode.OK)
        assertThat(patchRes.body<BankNodeInfo>().webhookUrl).isEqualTo("https://alpha-patched.loca.lt")

        // Non-existent bank
        val notFoundRes = client.put("/swift/banks/UNKNOWN/url") {
            basicAuth("admin", "admin")
            contentType(ContentType.Application.Json)
            setBody(UpdateBankUrlRequest(webhookUrl = "https://unknown.loca.lt"))
        }
        assertThat(notFoundRes.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `ping endpoints should return health check results`() = testApplication {
        val registry = BankRegistry()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "https://alpha.loca.lt"))
        val mockEngine = MockEngine {
            respond("""{"status":"UP"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val checker = BankHealthChecker(registry, HttpClient(mockEngine))
        application {
            swiftHubModule(bankRegistry = registry, bankHealthChecker = checker)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Global ping
        val globalPing = client.get("/swift/ping")
        assertThat(globalPing.status).isEqualTo(HttpStatusCode.OK)
        val allBanks = globalPing.body<List<BankNodeInfo>>()
        assertThat(allBanks).hasSize(1)
        assertThat(allBanks.first().status).isEqualTo("HEALTHY")

        // Single bank ping (requires auth)
        val bankPing = client.get("/swift/banks/BANKAXXX/ping") {
            basicAuth("admin", "admin")
        }
        assertThat(bankPing.status).isEqualTo(HttpStatusCode.OK)
        val pingedBank = bankPing.body<BankNodeInfo>()
        assertThat(pingedBank.bic).isEqualTo("BANKAXXX")
        assertThat(pingedBank.status).isEqualTo("HEALTHY")

        // Single bank ping unknown bank
        val unknownPing = client.get("/swift/banks/UNKNOWN/ping") {
            basicAuth("admin", "admin")
        }
        assertThat(unknownPing.status).isEqualTo(HttpStatusCode.NotFound)
    }
}
