package org.craftedsw.swift.router

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.craftedsw.contracts.BankNodeInfo
import org.craftedsw.contracts.Bic
import org.slf4j.LoggerFactory

class BankHealthChecker(
    private val bankRegistry: BankRegistry,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val logger = LoggerFactory.getLogger(BankHealthChecker::class.java)
    private val isRunning = AtomicBoolean(false)
    private var job: Job? = null

    suspend fun checkBank(bank: BankNodeInfo): BankNodeInfo {
        val url = bank.webhookUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            return bankRegistry.updateStatus(
                Bic(bank.bic),
                status = "DOWN",
                latencyMs = null,
                statusMessage = "No URL configured"
            ) ?: bank.copy(status = "DOWN", pingLatencyMs = null, statusMessage = "No URL configured")
        }

        val startTime = System.currentTimeMillis()
        return try {
            var response: HttpResponse = httpClient.get("$url/health")
            if (response.status == HttpStatusCode.NotFound) {
                // Fallback to /ping
                response = httpClient.get("$url/ping")
            }

            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val statusCode = response.status.value

            when {
                response.status.value in 200..299 -> {
                    bankRegistry.updateStatus(
                        Bic(bank.bic),
                        status = "HEALTHY",
                        latencyMs = durationMs,
                        statusMessage = "OK (${durationMs}ms)"
                    ) ?: bank.copy(status = "HEALTHY", pingLatencyMs = durationMs, statusMessage = "OK (${durationMs}ms)")
                }
                response.status == HttpStatusCode.ServiceUnavailable ||
                response.status == HttpStatusCode.BadGateway ||
                response.status == HttpStatusCode.GatewayTimeout -> {
                    val msg = "HTTP $statusCode ${response.status.description}"
                    logger.warn("Health check for ${bank.name} (${bank.bic}) failed: $msg")
                    bankRegistry.updateStatus(
                        Bic(bank.bic),
                        status = "DOWN",
                        latencyMs = null,
                        statusMessage = msg
                    ) ?: bank.copy(status = "DOWN", pingLatencyMs = null, statusMessage = msg)
                }
                else -> {
                    val msg = "HTTP $statusCode ${response.status.description}"
                    bankRegistry.updateStatus(
                        Bic(bank.bic),
                        status = "DOWN",
                        latencyMs = null,
                        statusMessage = msg
                    ) ?: bank.copy(status = "DOWN", pingLatencyMs = null, statusMessage = msg)
                }
            }
        } catch (e: Exception) {
            val errorMsg = when {
                e is io.ktor.client.plugins.HttpRequestTimeoutException -> "Request timeout"
                e is java.net.ConnectException -> "Connection refused"
                e is java.net.UnknownHostException -> "Unknown host"
                e.message?.isNotBlank() == true -> e.message!!.take(60)
                else -> "Connection failed"
            }
            logger.debug("Health check exception for ${bank.name} (${bank.bic}): $errorMsg")
            bankRegistry.updateStatus(
                Bic(bank.bic),
                status = "DOWN",
                latencyMs = null,
                statusMessage = "Unreachable: $errorMsg"
            ) ?: bank.copy(status = "DOWN", pingLatencyMs = null, statusMessage = "Unreachable: $errorMsg")
        }
    }

    suspend fun checkAllBanks(): List<BankNodeInfo> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        if (banks.isEmpty()) return@coroutineScope emptyList()

        val deferred = banks.map { bank ->
            async { checkBank(bank) }
        }
        deferred.awaitAll()
    }

    fun startPeriodicCheck(intervalSeconds: Long = 3L) {
        if (isRunning.compareAndSet(false, true)) {
            logger.info("Starting background Bank Health Checker (interval: ${intervalSeconds}s)...")
            job = scope.launch {
                while (isActive && isRunning.get()) {
                    try {
                        checkAllBanks()
                    } catch (e: Exception) {
                        logger.error("Error during bank health check cycle: ${e.message}", e)
                    }
                    delay(intervalSeconds * 1000L)
                }
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            logger.info("Stopping Bank Health Checker...")
            job?.cancel()
            job = null
        }
    }

    fun isPeriodicCheckRunning(): Boolean = isRunning.get()

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 2000
                connectTimeoutMillis = 1500
                socketTimeoutMillis = 1500
            }
        }
    }
}
