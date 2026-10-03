package org.craftedsw.swift.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.craftedsw.swift.simulator.TrafficSimulator
import org.craftedsw.swift.ui.RegisterHtml
import org.craftedsw.swift.ui.ScoreboardHtml

fun Application.swiftHubModule(
    bankRegistry: BankRegistry = BankRegistry(),
    auditLedger: AuditLedger = AuditLedger(),
    transferRouter: TransferRouter = TransferRouter(bankRegistry, auditLedger),
    trafficSimulator: TrafficSimulator = TrafficSimulator(bankRegistry, transferRouter)
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

    routing {
        get("/") {
            call.respondText(ScoreboardHtml.render(), ContentType.Text.Html)
        }

        get("/scoreboard") {
            call.respondText(ScoreboardHtml.render(), ContentType.Text.Html)
        }

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
                )
            )
            call.respond(HttpStatusCode.OK, state)
        }

        post("/swift/simulator/start") {
            val intervalMs = call.request.queryParameters["intervalMs"]?.toLongOrNull() ?: 2000L
            trafficSimulator.start(intervalMs)
            call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "RUNNING", intervalMs = intervalMs))
        }

        post("/swift/simulator/stop") {
            trafficSimulator.stop()
            call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "STOPPED"))
        }

        post("/swift/simulator/burst") {
            val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 5
            val executed = trafficSimulator.triggerBurst(count)
            call.respond(HttpStatusCode.OK, SimulatorStatusResponse(status = "BURST_EXECUTED", count = executed))
        }
    }
}
