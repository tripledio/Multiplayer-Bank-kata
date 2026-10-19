package org.craftedsw.swift.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.ErrorResponse
import org.craftedsw.contracts.HealthResponse
import org.craftedsw.contracts.LedgerResponse
import org.craftedsw.contracts.NetworkMetrics
import org.craftedsw.contracts.RegisterBankRequest
import org.craftedsw.contracts.RegisterBankResponse
import org.craftedsw.contracts.ScoreboardState
import org.craftedsw.contracts.SimulatorStatusResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.UpdateBankUrlRequest
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.craftedsw.swift.simulator.TrafficSimulator
import org.craftedsw.swift.ui.RegisterHtml
import org.craftedsw.swift.ui.ScoreboardHtml

data class FacilitatorCredentials(
    val username: String = "admin",
    val password: String = "admin"
) {
    companion object {
        fun fromEnv(env: (String) -> String? = { System.getenv(it) }): FacilitatorCredentials {
            val singleAuth = env("FACILITATOR_AUTH")
                ?: env("FACILITATOR_CREDENTIALS")
                ?: env("SCOREBOARD_AUTH")
                ?: env("SWIFT_HUB_AUTH")

            if (!singleAuth.isNullOrBlank()) {
                val parts = singleAuth.split(":", limit = 2)
                return if (parts.size == 2) {
                    FacilitatorCredentials(parts[0], parts[1])
                } else {
                    FacilitatorCredentials(parts[0], parts[0])
                }
            }

            val user = env("FACILITATOR_USERNAME")
                ?: env("FACILITATOR_USER")
                ?: env("SCOREBOARD_USERNAME")
                ?: env("SCOREBOARD_USER")
                ?: env("SWIFT_HUB_USER")
                ?: env("ADMIN_USER")

            val pass = env("FACILITATOR_PASSWORD")
                ?: env("FACILITATOR_PASS")
                ?: env("FACILITATOR_SECRET")
                ?: env("SCOREBOARD_PASSWORD")
                ?: env("SCOREBOARD_PASS")
                ?: env("SWIFT_HUB_PASSWORD")
                ?: env("ADMIN_PASSWORD")

            return when {
                user != null && pass != null -> FacilitatorCredentials(user, pass)
                user != null -> FacilitatorCredentials(user, user)
                pass != null -> FacilitatorCredentials(pass, pass)
                else -> FacilitatorCredentials("admin", "admin")
            }
        }
    }
}

fun Application.swiftHubModule(
    bankRegistry: BankRegistry = BankRegistry(),
    auditLedger: AuditLedger = AuditLedger(),
    transferRouter: TransferRouter = TransferRouter(bankRegistry, auditLedger),
    trafficSimulator: TrafficSimulator = TrafficSimulator(bankRegistry, transferRouter),
    credentials: FacilitatorCredentials = FacilitatorCredentials.fromEnv()
) {
    install(AutoHeadResponse)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid request"))
        }
    }

    install(Authentication) {
        basic(name = "facilitator-auth") {
            realm = "SWIFT Hub Facilitator Scoreboard"
            validate { userPasswordCredential ->
                if (userPasswordCredential.name == credentials.username &&
                    userPasswordCredential.password == credentials.password
                ) {
                    UserIdPrincipal(userPasswordCredential.name)
                } else {
                    null
                }
            }
        }
    }

    routing {
        // Public routes - accessible without authentication
        get("/register") {
            call.respondText(RegisterHtml.render(), ContentType.Text.Html)
        }

        get("/swift/register") {
            call.respondText(RegisterHtml.render(), ContentType.Text.Html)
        }

        get("/health") {
            call.respond(HealthResponse(status = "UP", bic = "SWIFTHUB"))
        }

        post("/swift/register") {
            val request = call.receive<RegisterBankRequest>()
            val node = bankRegistry.register(request)
            call.respond(
                HttpStatusCode.OK,
                RegisterBankResponse(
                    status = "REGISTERED",
                    bic = node.bic,
                    message = "Bank '${node.name}' successfully registered on SWIFT network."
                )
            )
        }

        post("/register") {
            val request = call.receive<RegisterBankRequest>()
            val node = bankRegistry.register(request)
            call.respond(
                HttpStatusCode.OK,
                RegisterBankResponse(
                    status = "REGISTERED",
                    bic = node.bic,
                    message = "Bank '${node.name}' successfully registered on SWIFT network."
                )
            )
        }

        get("/swift/banks") {
            call.respond(HttpStatusCode.OK, bankRegistry.getAllBanks())
        }

        post("/swift/transfers") {
            val transfer = call.receive<TransferRequest>()
            val result = transferRouter.routeTransfer(transfer)
            val statusCode = when (result.status) {
                TransferStatus.ACCEPTED -> HttpStatusCode.OK
                TransferStatus.REJECTED -> HttpStatusCode.BadRequest
                TransferStatus.FAILED -> HttpStatusCode.BadGateway
                TransferStatus.TIMEOUT -> HttpStatusCode.GatewayTimeout
            }
            call.respond(statusCode, result)
        }

        // Protected facilitator routes - requires basic authentication
        authenticate("facilitator-auth") {
            get("/") {
                call.respondText(ScoreboardHtml.render(), ContentType.Text.Html)
            }

            get("/scoreboard") {
                call.respondText(ScoreboardHtml.render(), ContentType.Text.Html)
            }

            delete("/swift/banks/{bic}") {
                val bic = call.parameters["bic"].orEmpty()
                val removed = bankRegistry.remove(Bic(bic))
                if (removed == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Bank '$bic' is not registered"))
                } else {
                    call.respond(HttpStatusCode.OK, removed)
                }
            }

            put("/swift/banks/{bic}/url") {
                val bic = call.parameters["bic"].orEmpty()
                val request = call.receive<UpdateBankUrlRequest>()
                val updated = bankRegistry.updateWebhookUrl(Bic(bic), request.webhookUrl)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Bank '$bic' is not registered"))
                } else {
                    call.respond(HttpStatusCode.OK, updated)
                }
            }

            post("/swift/banks/{bic}/url") {
                val bic = call.parameters["bic"].orEmpty()
                val request = call.receive<UpdateBankUrlRequest>()
                val updated = bankRegistry.updateWebhookUrl(Bic(bic), request.webhookUrl)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Bank '$bic' is not registered"))
                } else {
                    call.respond(HttpStatusCode.OK, updated)
                }
            }

            patch("/swift/banks/{bic}") {
                val bic = call.parameters["bic"].orEmpty()
                val request = call.receive<UpdateBankUrlRequest>()
                val updated = bankRegistry.updateWebhookUrl(Bic(bic), request.webhookUrl)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Bank '$bic' is not registered"))
                } else {
                    call.respond(HttpStatusCode.OK, updated)
                }
            }

            get("/swift/ledger") {
                call.respond(
                    HttpStatusCode.OK,
                    LedgerResponse(
                        totalSettledVolumeCents = auditLedger.getTotalSettledVolumeCents(),
                        totalSuccessfulTransactions = auditLedger.getTotalSuccessfulCount(),
                        totalFailedTransactions = auditLedger.getTotalFailedCount(),
                        isConservationOfMoneyVerified = auditLedger.verifyConservationOfMoney(),
                        recentEntries = auditLedger.getRecentEntries(30)
                    )
                )
            }

            get("/swift/metrics") {
                val allBanks = bankRegistry.getAllBanks()
                val totalTx = auditLedger.getTotalTransactionsCount()
                val failedTx = auditLedger.getTotalFailedCount()
                val errorRate = if (totalTx > 0) (failedTx.toDouble() / totalTx.toDouble()) * 100.0 else 0.0

                val state = ScoreboardState(
                    banks = allBanks.sortedByDescending { it.score },
                    metrics = NetworkMetrics(
                        activeBanksCount = allBanks.size,
                        totalVolumeCents = auditLedger.getTotalSettledVolumeCents(),
                        totalTransactions = totalTx,
                        throughputTps = 0.0,
                        errorRatePercentage = errorRate
                    ),
                    graph = auditLedger.getGraph(bankRegistry)
                )
                call.respond(HttpStatusCode.OK, state)
            }

            get("/swift/graph") {
                val graph = auditLedger.getGraph(bankRegistry)
                call.respond(HttpStatusCode.OK, graph)
            }

            get("/swift/simulator/status") {
                val status = if (trafficSimulator.isSimulatorRunning()) "RUNNING" else "STOPPED"
                call.respond(
                    HttpStatusCode.OK,
                    SimulatorStatusResponse(
                        status = status,
                        intervalMs = trafficSimulator.getIntervalMs(),
                        phases = trafficSimulator.getPhasesStatus()
                    )
                )
            }

            get("/swift/simulator/phases") {
                call.respond(HttpStatusCode.OK, trafficSimulator.getPhasesStatus())
            }

            post("/swift/simulator/phase/{phase}/toggle") {
                val phase = call.parameters["phase"].orEmpty()
                val isNowRunning = trafficSimulator.togglePhase(phase)
                call.respond(
                    HttpStatusCode.OK,
                    SimulatorStatusResponse(
                        status = if (isNowRunning) "RUNNING" else "STOPPED",
                        phases = trafficSimulator.getPhasesStatus()
                    )
                )
            }

            post("/swift/simulator/phase/{phase}/start") {
                val phase = call.parameters["phase"].orEmpty()
                trafficSimulator.startPhase(phase)
                call.respond(
                    HttpStatusCode.OK,
                    SimulatorStatusResponse(status = "RUNNING", phases = trafficSimulator.getPhasesStatus())
                )
            }

            post("/swift/simulator/phase/{phase}/stop") {
                val phase = call.parameters["phase"].orEmpty()
                trafficSimulator.stopPhase(phase)
                call.respond(
                    HttpStatusCode.OK,
                    SimulatorStatusResponse(status = "STOPPED", phases = trafficSimulator.getPhasesStatus())
                )
            }

            post("/swift/simulator/start") {
                val intervalMs = call.request.queryParameters["intervalMs"]?.toLongOrNull() ?: 2000L
                trafficSimulator.start(intervalMs)
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "RUNNING", intervalMs = intervalMs, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/stop") {
                trafficSimulator.stop()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "STOPPED", phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/burst") {
                val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 5
                val executed = trafficSimulator.triggerBurst(count)
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "BURST_EXECUTED", count = executed, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/deposit-positive") {
                val results = trafficSimulator.runPositiveDepositScenario()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/deposit-negative") {
                val results = trafficSimulator.runNegativeDepositScenario()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/deposit") {
                val results = trafficSimulator.runDepositsPhase()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/withdraw") {
                val results = trafficSimulator.runWithdrawalsPhase()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/transfer") {
                val results = trafficSimulator.runTransfersPhase()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }

            post("/swift/simulator/scenario/all") {
                val results = trafficSimulator.runAllScenarios()
                call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "SCENARIO_EXECUTED", results = results, phases = trafficSimulator.getPhasesStatus()))
            }
        }
    }
}
