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
import org.craftedsw.contracts.WithdrawRequest
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.slf4j.LoggerFactory

enum class ScenarioTransactionType {
    DEPOSIT,
    WITHDRAW
}

data class ScenarioTransaction(
    val type: ScenarioTransactionType,
    val amountCents: Long,
    val expectedSuccess: Boolean
)

data class ExpectedStatementLine(
    val credit: String?,
    val debit: String?,
    val balance: String
)

class ScenarioLedger(val iban: String) {
    private val transactions = mutableListOf<ScenarioTransaction>()
    var expectedBalanceCents: Long = 0L
        private set
    var shouldAccountExist: Boolean = false
        private set

    fun recordDeposit(amountCents: Long): Boolean {
        val willSucceed = amountCents > 0
        transactions.add(ScenarioTransaction(ScenarioTransactionType.DEPOSIT, amountCents, willSucceed))
        if (willSucceed) {
            expectedBalanceCents += amountCents
            shouldAccountExist = true
        }
        return willSucceed
    }

    fun recordWithdraw(amountCents: Long): Boolean {
        val willSucceed = amountCents > 0 && expectedBalanceCents >= amountCents
        transactions.add(ScenarioTransaction(ScenarioTransactionType.WITHDRAW, amountCents, willSucceed))
        if (willSucceed) {
            expectedBalanceCents -= amountCents
            shouldAccountExist = true
        }
        return willSucceed
    }

    fun getExpectedStatementLines(): List<ExpectedStatementLine> {
        val lines = mutableListOf<ExpectedStatementLine>()
        var runningBal = 0L
        for (tx in transactions) {
            if (!tx.expectedSuccess) continue
            when (tx.type) {
                ScenarioTransactionType.DEPOSIT -> {
                    runningBal += tx.amountCents
                    lines.add(
                        ExpectedStatementLine(
                            credit = formatAmount(tx.amountCents),
                            debit = null,
                            balance = formatAmount(runningBal)
                        )
                    )
                }
                ScenarioTransactionType.WITHDRAW -> {
                    runningBal -= tx.amountCents
                    lines.add(
                        ExpectedStatementLine(
                            credit = null,
                            debit = formatAmount(tx.amountCents),
                            balance = formatAmount(runningBal)
                        )
                    )
                }
            }
        }
        // Statements in Bank Kata are ordered in reverse chronological order
        return lines.reversed()
    }

    fun formatExpectedSummary(): String {
        if (!shouldAccountExist) {
            return "Expected: Error (HTTP 400/422) | Statement: [empty / uninitialized] (0 lines)"
        }
        val lines = getExpectedStatementLines()
        val linesSummary = if (lines.isEmpty()) {
            "[]"
        } else {
            "[" + lines.joinToString(" | ") { l ->
                "credit: ${l.credit ?: "-"}, debit: ${l.debit ?: "-"}, bal: ${l.balance}"
            } + "]"
        }
        return "Expected: Balance ${formatAmount(expectedBalanceCents)} EUR | Statement (${lines.size} line${if (lines.size == 1) "" else "s"}): $linesSummary"
    }

    companion object {
        fun formatAmount(cents: Long): String {
            val isNegative = cents < 0
            val absCents = Math.abs(cents)
            val euros = absCents / 100
            val remainder = (absCents % 100).toString().padStart(2, '0')
            return "${if (isNegative) "-" else ""}$euros.$remainder"
        }

        fun formatActualSummary(statusCode: HttpStatusCode, statement: StatementResponse?): String {
            if (statement == null || statusCode != HttpStatusCode.OK) {
                return "Actual: HTTP ${statusCode.value}"
            }
            val lines = statement.lines
            val linesSummary = if (lines.isEmpty()) {
                "[]"
            } else {
                "[" + lines.joinToString(" | ") { l ->
                    "credit: ${l.credit ?: "-"}, debit: ${l.debit ?: "-"}, bal: ${l.balance}"
                } + "]"
            }
            return "Actual: Balance ${formatAmount(statement.currentBalanceCents)} EUR | Statement (${lines.size} line${if (lines.size == 1) "" else "s"}): $linesSummary"
        }
    }
}

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
            val ledger = ScenarioLedger(iban)
            val depositAmountCents = 5000L // 50.00 EUR
            ledger.recordDeposit(depositAmountCents)
            val expectedStr = ledger.formatExpectedSummary()

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
                            message = "Deposit failed with status ${depositResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: Deposit HTTP ${depositResponse.status.value} ($errorBody)"
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
                            message = "Expected balance $depositAmountCents cents, but deposit response returned ${depositBody.balanceCents} cents",
                            expected = expectedStr,
                            actual = "Actual: Deposit balance returned ${ScenarioLedger.formatAmount(depositBody.balanceCents)} EUR"
                        )
                    )
                    continue
                }

                // Verify statement/balance for the newly created IBAN
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -5L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = "Statement query failed with status ${statementResponse.status.value}",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                    continue
                }

                val expectedLines = ledger.getExpectedStatementLines()
                val linesMatch = statement.lines.size == expectedLines.size &&
                    statement.lines.zip(expectedLines).all { (actualLine, expLine) ->
                        (actualLine.credit?.trim() == expLine.credit?.trim()) &&
                        (actualLine.debit?.trim() == expLine.debit?.trim()) &&
                        (actualLine.balance.trim() == expLine.balance.trim())
                    }

                if (statement.currentBalanceCents == depositAmountCents && linesMatch) {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 20L, depositAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = true,
                            message = "Successfully deposited 50.00 EUR to $iban and verified statement matched ledger",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    val mismatchReason = if (statement.currentBalanceCents != depositAmountCents) {
                        "Expected statement balance $depositAmountCents cents, but got ${statement.currentBalanceCents} cents"
                    } else {
                        "Statement lines do not match expected format"
                    }
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_EUR",
                            passed = false,
                            message = mismatchReason,
                            expected = expectedStr,
                            actual = actualStr
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
                        message = "Exception during test: ${e.message}",
                        expected = expectedStr,
                        actual = "Actual: Exception: ${e.message}"
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
            val ledger = ScenarioLedger(iban)
            val negativeDepositAmountCents = -5000L // -50.00 EUR
            ledger.recordDeposit(negativeDepositAmountCents)
            val expectedStr = ledger.formatExpectedSummary()

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
                            message = "Expected bank to reject negative deposit, but received 200 OK",
                            expected = expectedStr,
                            actual = "Actual: Deposit accepted with HTTP 200 OK"
                        )
                    )
                    continue
                }

                // Verify that it doesn't end up with 0 euro in the account
                // When an account was never created or deposit failed, querying statement should either return 400/404 or have no lines / not be an initialized 0 balance account.
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                val accountHasZero = if (statement != null) {
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
                            message = "Deposit of negative amount failed, but account was created with 0 EUR statement",
                            expected = expectedStr,
                            actual = actualStr
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
                            message = "Central service received expected error (${depositResponse.status.value}) and account was not initialized with 0 EUR",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${depositResponse.status.value} (Rejected) | Statement: ${if (statement == null) "HTTP ${statementResponse.status.value}" else "${statement.lines.size} lines"}"
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
                        message = "Exception during test: ${e.message}",
                        expected = expectedStr,
                        actual = "Actual: Exception: ${e.message}"
                    )
                )
            }
        }

        return results
    }

    suspend fun runDepositWithdrawScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
            val ledger = ScenarioLedger(iban)
            val depositAmountCents = 5000L // 50.00 EUR
            val withdrawAmountCents = 2000L // 20.00 EUR
            ledger.recordDeposit(depositAmountCents)
            ledger.recordWithdraw(withdrawAmountCents)
            val expectedStr = ledger.formatExpectedSummary()

            try {
                // 1. Deposit 50 EUR
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
                            scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                            passed = false,
                            message = "Initial deposit of 50.00 EUR failed with status ${depositResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                        )
                    )
                    continue
                }

                // 2. Withdraw 20 EUR
                val withdrawResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                    contentType(ContentType.Application.Json)
                    setBody(WithdrawRequest(iban = iban, amountCents = withdrawAmountCents))
                }

                if (withdrawResponse.status != HttpStatusCode.OK) {
                    val errorBody = withdrawResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                            passed = false,
                            message = "Withdrawal of 20.00 EUR failed with status ${withdrawResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: Withdrawal HTTP ${withdrawResponse.status.value} ($errorBody)"
                        )
                    )
                    continue
                }

                // 3. Query statement and compare actual vs expected
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -5L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                            passed = false,
                            message = "Statement query failed with status ${statementResponse.status.value}",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                    continue
                }

                val expectedLines = ledger.getExpectedStatementLines()
                val linesMatch = statement.lines.size == expectedLines.size &&
                    statement.lines.zip(expectedLines).all { (actualLine, expLine) ->
                        (actualLine.credit?.trim() == expLine.credit?.trim()) &&
                        (actualLine.debit?.trim() == expLine.debit?.trim()) &&
                        (actualLine.balance.trim() == expLine.balance.trim())
                    }

                if (statement.currentBalanceCents == ledger.expectedBalanceCents && linesMatch) {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 25L, withdrawAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                            passed = true,
                            message = "Successfully deposited 50.00 EUR, withdrew 20.00 EUR, and verified statement matched ledger",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                    val mismatchReason = if (statement.currentBalanceCents != ledger.expectedBalanceCents) {
                        "Expected statement balance ${ledger.expectedBalanceCents} cents, but got ${statement.currentBalanceCents} cents"
                    } else {
                        "Statement lines do not match expected format"
                    }
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                            passed = false,
                            message = mismatchReason,
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Deposit & withdraw scenario failed for ${bank.name}: ${e.message}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, -10L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "DEPOSIT_50_WITHDRAW_20_EUR",
                        passed = false,
                        message = "Exception during test: ${e.message}",
                        expected = expectedStr,
                        actual = "Actual: Exception: ${e.message}"
                    )
                )
            }
        }

        return results
    }

    suspend fun runAllScenarios(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runPositiveDepositScenario())
        results.addAll(runNegativeDepositScenario())
        results.addAll(runDepositWithdrawScenario())
        return results
    }

    suspend fun runAllDepositScenarios(): List<ScenarioResult> {
        return runAllScenarios()
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
