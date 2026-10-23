package org.craftedsw.bank

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.craftedsw.bank.api.bankModule
import org.craftedsw.bank.client.SwiftClient
import org.craftedsw.bank.service.BankService
import org.craftedsw.contracts.RegisterBankRequest
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("BankApplication")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val bic = System.getenv("BIC") ?: "BANKAXXX"
    val bankName = System.getenv("BANK_NAME") ?: "Bank Alpha"
    val swiftHubUrl = System.getenv("SWIFT_HUB_URL")
    val tunnelUrl = System.getenv("PUBLIC_TUNNEL_URL")

    val bankService = BankService()
    val swiftClient = SwiftClient()

    logger.info("Starting Bank node '$bankName' (BIC: $bic) on port $port...")
    logger.info("Participant Debug UI available at: http://localhost:$port/")

    if (!swiftHubUrl.isNullOrBlank() && !tunnelUrl.isNullOrBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(1500) // Brief delay to let Netty bind
            try {
                logger.info("Registering with SWIFT Hub at $swiftHubUrl with tunnel URL $tunnelUrl...")
                val response = swiftClient.registerBank(
                    swiftHubUrl = swiftHubUrl,
                    request = RegisterBankRequest(
                        bic = bic,
                        name = bankName,
                        webhookUrl = tunnelUrl
                    )
                )
                logger.info("Registration successful: ${response.message}")
            } catch (e: Exception) {
                logger.warn("Could not register with SWIFT Hub on startup: ${e.message}")
            }
        }
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        bankModule(
            bankService = bankService,
            swiftClient = swiftClient,
            swiftHubUrl = swiftHubUrl,
            bic = bic,
            bankName = bankName
        )
    }.start(wait = true)
}
