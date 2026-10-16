package org.craftedsw.swift.simulator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.craftedsw.contracts.BalanceResponse
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.ScenarioResult
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.slf4j.LoggerFactory

class TrafficSimulator(
    private val bankRegistry: BankRegistry,
    private val transferRouter: TransferRouter,
    private val httpClient: HttpClient = TransferRouter.defaultHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val logger = LoggerFactory.getLogger(TrafficSimulator::class.java)
    private val isRunning = AtomicBoolean(false)
    private val intervalMillis = AtomicLong(2000)
    private var job: Job? = null

    fun start(intervalMs: Long = 2000) {
        intervalMillis.set(intervalMs.coerceAtLeast(100))
        if (isRunning.compareAndSet(false, true)) {
            logger.info("Starting Traffic Simulator with interval ${intervalMillis.get()}ms...")
            job = scope.launch {
                while (isActive && isRunning.get()) {
                    simulateSingleTransfer()
                    delay(intervalMillis.get())
                }
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            logger.info("Stopping Traffic Simulator...")
            job?.cancel()
            job = null
        }
    }

    fun isSimulatorRunning(): Boolean = isRunning.get()

    fun getIntervalMs(): Long = intervalMillis.get()

    suspend fun triggerBurst(count: Int): Int {
        val banks = bankRegistry.getAllBanks()
        if (banks.isEmpty()) return 0

        var executed = 0
        for (i in 1..count) {
            simulateSingleTransfer()
            executed++
            delay(50) // Micro-burst spacing
        }
        return executed
    }

    suspend fun runPositiveDepositScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
            val depositAmountCents = 5000L // 50.00 EUR

            try {
                val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(iban = iban, amountCents = depositAmountCents))
                }

                if (depositResponse.status != HttpStatusCode.OK) {
                    val errorBody = depositResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = "Deposit failed with status ${depositResponse.status.value}: $errorBody"
                        )
                    )
                    continue
                }

                val depositBody: BalanceResponse = depositResponse.body()
                if (depositBody.balanceCents != depositAmountCents) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = "Expected balance $depositAmountCents cents, but deposit response returned ${depositBody.balanceCents} cents"
                        )
                    )
                    continue
                }

                // Verify statement/balance for the newly created IBAN
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                if (statementResponse.status != HttpStatusCode.OK) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -5L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = "Statement query failed with status ${statementResponse.status.value}"
                        )
                    )
                    continue
                }

                val statement: StatementResponse = statementResponse.body()
                if (statement.currentBalanceCents == depositAmountCents) {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 20L, depositAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = true,
                            message = "Successfully deposited 50.00 EUR to $iban and verified 50.00 EUR balance"
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = "Expected statement balance $depositAmountCents cents, but got ${statement.currentBalanceCents} cents"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Positive deposit scenario failed for ${bank.name}: ${e.message}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "DEPOSIT_50_EUR",
                        passed = false,
                        message = "Exception during test: ${e.message}"
                    )
                )
            }
        }

        return results
    }

    suspend fun runNegativeDepositScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
            val negativeDepositAmountCents = -5000L // -50.00 EUR

            try {
                val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(iban = iban, amountCents = negativeDepositAmountCents))
                }

                // Check that central service receives an error (e.g. 400 Bad Request, or non-2xx status)
                val receivedError = depositResponse.status != HttpStatusCode.OK

                if (!receivedError) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -15L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Expected bank to reject negative deposit, but received 200 OK"
                        )
                    )
                    continue
                }

                // Verify that it doesn't end up with 0 euro in the account
                // When an account was never created or deposit failed, querying statement should either return 400/404 or have no lines / not be an initialized 0 balance account.
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                val accountHasZero = if (statementResponse.status == HttpStatusCode.OK) {
                    val statement: StatementResponse = statementResponse.body()
                    statement.lines.isNotEmpty() && statement.currentBalanceCents == 0L
                } else {
                    false
                }

                if (accountHasZero) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Deposit of negative amount failed, but account was created with 0 EUR"
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 20L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = true,
                            message = "Central service received expected error (${depositResponse.status.value}) and account was not initialized with 0 EUR"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Negative deposit scenario failed for ${bank.name}: ${e.message}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "DEPOSIT_NEGATIVE_50_EUR",
                        passed = false,
                        message = "Exception during test: ${e.message}"
                    )
                )
            }
        }

        return results
    }

    suspend fun runAllDepositScenarios(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runPositiveDepositScenario())
        results.addAll(runNegativeDepositScenario())
        return results
    }

    suspend fun simulateSingleTransfer(): Boolean {
        val banks = bankRegistry.getAllBanks()
        if (banks.isEmpty()) return false

        val senderBank = banks.random()
        val receiverBank = banks.random()

        val senderIban = "BE68${senderBank.bic}000${(1000000..9999999).random()}"
        val receiverIban = "BE68${receiverBank.bic}000${(1000000..9999999).random()}"
        val amountCents = (1000..50000).random().toLong() // 10.00 EUR to 500.00 EUR

        val transfer = TransferRequest(
            transactionId = "sim-${System.currentTimeMillis()}-${(100..999).random()}",
            fromIban = senderIban,
            toIban = receiverIban,
            amountCents = amountCents,
            timestamp = Instant.now().toString(),
            reference = "Simulated Transfer"
        )

        transferRouter.routeTransfer(transfer)
        return true
    }
}
