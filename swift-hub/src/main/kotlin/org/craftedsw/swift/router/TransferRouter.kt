package org.craftedsw.swift.router

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.Iban
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.slf4j.LoggerFactory

class TransferRouter(
    private val bankRegistry: BankRegistry,
    private val auditLedger: AuditLedger,
    private val httpClient: HttpClient = defaultHttpClient()
) {

    private val logger = LoggerFactory.getLogger(TransferRouter::class.java)

    suspend fun routeTransfer(transfer: TransferRequest): TransferResult {
        val txId = transfer.transactionId.ifBlank { "tx-${System.currentTimeMillis()}-${(1000..9999).random()}" }
        val enrichedTransfer = transfer.copy(transactionId = txId)

        if (enrichedTransfer.amountCents <= 0) {
            val rejected = TransferResult(txId, TransferStatus.REJECTED, "Amount must be greater than zero")
            auditLedger.record(enrichedTransfer, rejected)
            return rejected
        }

        val destIban = Iban(enrichedTransfer.toIban)
        val destBic = destIban.extractBic() ?: Bic(findBicFromIban(enrichedTransfer.toIban))
        val destNode = bankRegistry.getBank(destBic)

        if (destNode == null) {
            val rejected = TransferResult(
                txId,
                TransferStatus.REJECTED,
                "Destination bank for BIC '${destBic.value}' is not registered on SWIFT network"
            )
            auditLedger.record(enrichedTransfer, rejected)
            return rejected
        }

        val startTime = System.currentTimeMillis()
        val result = try {
            val response: TransferResult = httpClient.post("${destNode.webhookUrl}/api/transfer-in") {
                contentType(ContentType.Application.Json)
                setBody(enrichedTransfer)
            }.body()

            val durationMs = System.currentTimeMillis() - startTime
            val points = if (response.status == TransferStatus.ACCEPTED) {
                if (durationMs < 500) 15L else 10L
            } else {
                -5L
            }
            bankRegistry.recordTransaction(
                destBic,
                response.status == TransferStatus.ACCEPTED,
                points,
                if (response.status == TransferStatus.ACCEPTED) enrichedTransfer.amountCents else 0L
            )
            response
        } catch (e: Exception) {
            logger.warn("Transfer to ${destNode.name} (${destNode.bic}) failed: ${e.message}")
            bankRegistry.recordTransaction(destBic, false, -10L)
            TransferResult(
                transactionId = txId,
                status = TransferStatus.FAILED,
                message = "Destination node unreachable: ${e.message}"
            )
        }

        auditLedger.record(enrichedTransfer, result)
        return result
    }

    private fun findBicFromIban(iban: String): String {
        return if (iban.length >= 8) iban.substring(0, 8) else iban
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 3000
                connectTimeoutMillis = 2000
                socketTimeoutMillis = 2000
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }
        }
    }
}
