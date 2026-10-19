package org.craftedsw.bank.api

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
import org.craftedsw.bank.client.SwiftClient
import org.craftedsw.bank.service.BankService
import org.craftedsw.contracts.BalanceResponse
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.ErrorResponse
import org.craftedsw.contracts.HealthResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.WithdrawRequest

fun Application.bankModule(
    bankService: BankService = BankService(),
    swiftClient: SwiftClient = SwiftClient(),
    swiftHubUrl: String? = null,
    bic: String = "BANKAXXX"
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
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(cause.message ?: "Unprocessable request"))
        }
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP", bic = bic))
        }

        get("/ping") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP", bic = bic))
        }

        get("/api/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP", bic = bic))
        }

        get("/api/ping") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP", bic = bic))
        }

        post("/api/deposit") {
            val request = call.receive<DepositRequest>()
            bankService.deposit(request).fold(
                onSuccess = { newBalance ->
                    call.respond(HttpStatusCode.OK, BalanceResponse(iban = request.iban, balanceCents = newBalance))
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Deposit failed"))
                }
            )
        }

        post("/api/withdraw") {
            val request = call.receive<WithdrawRequest>()
            bankService.withdraw(request).fold(
                onSuccess = { newBalance ->
                    call.respond(HttpStatusCode.OK, BalanceResponse(iban = request.iban, balanceCents = newBalance))
                },
                onFailure = { error ->
                    val status = if (error is IllegalStateException) HttpStatusCode.UnprocessableEntity else HttpStatusCode.BadRequest
                    call.respond(status, ErrorResponse(error.message ?: "Withdrawal failed"))
                }
            )
        }

        get("/api/statement") {
            val iban = call.request.queryParameters["iban"]
            if (iban.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required 'iban' parameter"))
                return@get
            }
            val statement = bankService.getStatement(iban)
            call.respond(HttpStatusCode.OK, statement)
        }

        get("/api/statement/{iban}") {
            val iban = call.parameters["iban"]
            if (iban.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required 'iban' parameter"))
                return@get
            }
            val statement = bankService.getStatement(iban)
            call.respond(HttpStatusCode.OK, statement)
        }

        post("/api/transfer-in") {
            val transfer = call.receive<TransferRequest>()
            val result = bankService.processIncomingTransfer(transfer)
            if (result.status == TransferStatus.ACCEPTED) {
                call.respond(HttpStatusCode.OK, result)
            } else {
                call.respond(HttpStatusCode.BadRequest, result)
            }
        }

        post("/api/transfer-out") {
            val transfer = call.receive<TransferRequest>()
            val hubUrl = swiftHubUrl ?: System.getenv("SWIFT_HUB_URL") ?: "http://localhost:9000"

            val withdrawResult = bankService.withdraw(
                WithdrawRequest(iban = transfer.fromIban, amountCents = transfer.amountCents)
            )

            withdrawResult.fold(
                onSuccess = {
                    val result = swiftClient.sendTransfer(hubUrl, transfer)
                    if (result.status != TransferStatus.ACCEPTED) {
                        // Compensate / refund
                        bankService.deposit(DepositRequest(iban = transfer.fromIban, amountCents = transfer.amountCents))
                    }
                    call.respond(HttpStatusCode.OK, result)
                },
                onFailure = { error ->
                    val status = if (error is IllegalStateException) HttpStatusCode.UnprocessableEntity else HttpStatusCode.BadRequest
                    call.respond(
                        status,
                        TransferResult(
                            transactionId = transfer.transactionId,
                            status = TransferStatus.REJECTED,
                            message = error.message ?: "Insufficient funds"
                        )
                    )
                }
            )
        }
    }
}
