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
import java.util.concurrent.ConcurrentHashMap
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
import org.craftedsw.contracts.ScenarioPhaseStatus
import org.craftedsw.contracts.ScenarioResult
import org.craftedsw.contracts.SimulatorPhasesStatusResponse
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.WithdrawRequest
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.slf4j.LoggerFactory

enum class ScenarioTransactionType {
    DEPOSIT,
    WITHDRAW,
    TRANSFER_IN,
    TRANSFER_OUT
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

    fun recordTransferOut(amountCents: Long): Boolean {
        val willSucceed = amountCents > 0 && expectedBalanceCents >= amountCents
        transactions.add(ScenarioTransaction(ScenarioTransactionType.TRANSFER_OUT, amountCents, willSucceed))
        if (willSucceed) {
            expectedBalanceCents -= amountCents
            shouldAccountExist = true
        }
        return willSucceed
    }

    fun recordTransferIn(amountCents: Long): Boolean {
        val willSucceed = amountCents > 0
        transactions.add(ScenarioTransaction(ScenarioTransactionType.TRANSFER_IN, amountCents, willSucceed))
        if (willSucceed) {
            expectedBalanceCents += amountCents
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
                ScenarioTransactionType.DEPOSIT, ScenarioTransactionType.TRANSFER_IN -> {
                    runningBal += tx.amountCents
                    lines.add(
                        ExpectedStatementLine(
                            credit = formatAmount(tx.amountCents),
                            debit = null,
                            balance = formatAmount(runningBal)
                        )
                    )
                }
                ScenarioTransactionType.WITHDRAW, ScenarioTransactionType.TRANSFER_OUT -> {
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

    // 3 Phase Runners
    private val depositsRunning = AtomicBoolean(false)
    private val depositsStartTime = AtomicLong(0)
    private val depositsElapsedSeconds = AtomicLong(0)
    private var depositsJob: Job? = null
    private val depositsLatestResults = ConcurrentHashMap<String, List<ScenarioResult>>()

    private val withdrawalsRunning = AtomicBoolean(false)
    private val withdrawalsStartTime = AtomicLong(0)
    private val withdrawalsElapsedSeconds = AtomicLong(0)
    private var withdrawalsJob: Job? = null
    private val withdrawalsLatestResults = ConcurrentHashMap<String, List<ScenarioResult>>()

    private val transfersRunning = AtomicBoolean(false)
    private val transfersStartTime = AtomicLong(0)
    private val transfersElapsedSeconds = AtomicLong(0)
    private var transfersJob: Job? = null
    private val transfersLatestResults = ConcurrentHashMap<String, List<ScenarioResult>>()

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

    // --- Phase Controls & Status ---

    fun togglePhase(phaseName: String): Boolean {
        return when (phaseName.uppercase()) {
            "DEPOSITS" -> {
                if (depositsRunning.get()) {
                    stopPhase("DEPOSITS")
                    false
                } else {
                    startPhase("DEPOSITS")
                    true
                }
            }
            "WITHDRAWALS" -> {
                if (withdrawalsRunning.get()) {
                    stopPhase("WITHDRAWALS")
                    false
                } else {
                    startPhase("WITHDRAWALS")
                    true
                }
            }
            "TRANSFERS" -> {
                if (transfersRunning.get()) {
                    stopPhase("TRANSFERS")
                    false
                } else {
                    startPhase("TRANSFERS")
                    true
                }
            }
            else -> false
        }
    }

    fun startPhase(phaseName: String) {
        when (phaseName.uppercase()) {
            "DEPOSITS" -> {
                if (depositsRunning.compareAndSet(false, true)) {
                    depositsStartTime.set(System.currentTimeMillis() - (depositsElapsedSeconds.get() * 1000L))
                    depositsJob = scope.launch {
                        while (isActive && depositsRunning.get()) {
                            try {
                                val results = runDepositsPhase()
                                depositsLatestResults["LATEST"] = results
                            } catch (e: Exception) {
                                logger.error("Error executing Deposits phase: ${e.message}", e)
                            }
                            delay(10000L) // 10 seconds interval
                        }
                    }
                }
            }
            "WITHDRAWALS" -> {
                if (withdrawalsRunning.compareAndSet(false, true)) {
                    withdrawalsStartTime.set(System.currentTimeMillis() - (withdrawalsElapsedSeconds.get() * 1000L))
                    withdrawalsJob = scope.launch {
                        while (isActive && withdrawalsRunning.get()) {
                            try {
                                val results = runWithdrawalsPhase()
                                withdrawalsLatestResults["LATEST"] = results
                            } catch (e: Exception) {
                                logger.error("Error executing Withdrawals phase: ${e.message}", e)
                            }
                            delay(10000L) // 10 seconds interval
                        }
                    }
                }
            }
            "TRANSFERS" -> {
                if (transfersRunning.compareAndSet(false, true)) {
                    transfersStartTime.set(System.currentTimeMillis() - (transfersElapsedSeconds.get() * 1000L))
                    transfersJob = scope.launch {
                        while (isActive && transfersRunning.get()) {
                            try {
                                val results = runTransfersPhase()
                                transfersLatestResults["LATEST"] = results
                            } catch (e: Exception) {
                                logger.error("Error executing Transfers phase: ${e.message}", e)
                            }
                            delay(10000L) // 10 seconds interval
                        }
                    }
                }
            }
        }
    }

    fun stopPhase(phaseName: String) {
        when (phaseName.uppercase()) {
            "DEPOSITS" -> {
                if (depositsRunning.compareAndSet(true, false)) {
                    val now = System.currentTimeMillis()
                    depositsElapsedSeconds.set((now - depositsStartTime.get()) / 1000L)
                    depositsJob?.cancel()
                    depositsJob = null
                }
            }
            "WITHDRAWALS" -> {
                if (withdrawalsRunning.compareAndSet(true, false)) {
                    val now = System.currentTimeMillis()
                    withdrawalsElapsedSeconds.set((now - withdrawalsStartTime.get()) / 1000L)
                    withdrawalsJob?.cancel()
                    withdrawalsJob = null
                }
            }
            "TRANSFERS" -> {
                if (transfersRunning.compareAndSet(true, false)) {
                    val now = System.currentTimeMillis()
                    transfersElapsedSeconds.set((now - transfersStartTime.get()) / 1000L)
                    transfersJob?.cancel()
                    transfersJob = null
                }
            }
        }
    }

    fun getPhasesStatus(): SimulatorPhasesStatusResponse {
        val now = System.currentTimeMillis()
        val depElapsed = if (depositsRunning.get()) (now - depositsStartTime.get()) / 1000L else depositsElapsedSeconds.get()
        val withElapsed = if (withdrawalsRunning.get()) (now - withdrawalsStartTime.get()) / 1000L else withdrawalsElapsedSeconds.get()
        val transElapsed = if (transfersRunning.get()) (now - transfersStartTime.get()) / 1000L else transfersElapsedSeconds.get()

        return SimulatorPhasesStatusResponse(
            deposits = ScenarioPhaseStatus(
                phase = "DEPOSITS",
                running = depositsRunning.get(),
                elapsedSeconds = depElapsed.coerceAtLeast(0),
                results = depositsLatestResults["LATEST"] ?: emptyList()
            ),
            withdrawals = ScenarioPhaseStatus(
                phase = "WITHDRAWALS",
                running = withdrawalsRunning.get(),
                elapsedSeconds = withElapsed.coerceAtLeast(0),
                results = withdrawalsLatestResults["LATEST"] ?: emptyList()
            ),
            transfers = ScenarioPhaseStatus(
                phase = "TRANSFERS",
                running = transfersRunning.get(),
                elapsedSeconds = transElapsed.coerceAtLeast(0),
                results = transfersLatestResults["LATEST"] ?: emptyList()
            )
        )
    }

    // --- Phase 1: Deposits ---
    suspend fun runDepositsPhase(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runPositiveDepositScenario())
        results.addAll(runNegativeDepositScenario())
        return results
    }

    // --- Phase 2: Withdrawals ---
    suspend fun runWithdrawalsPhase(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runPositiveWithdrawScenario())
        results.addAll(runNegativeWithdrawScenario())
        results.addAll(runOverdraftWithdrawScenario())
        return results
    }

    // --- Phase 3: Transfers ---
    suspend fun runTransfersPhase(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runIntraBankTransferScenario())
        results.addAll(runCrossBankTransferScenario())
        return results
    }

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

    private fun checkResponseTunnelStatus(bic: Bic, status: HttpStatusCode): Boolean {
        if (status.value in 500..599 || status == HttpStatusCode.NotFound) {
            bankRegistry.updateStatus(bic, "DOWN", null, "HTTP ${status.value} ${status.description}")
            return true
        }
        return false
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
                    checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                    val errorBody = depositResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
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
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
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
                checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)
                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
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
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L, depositAmountCents)
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
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
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
                val err = e.message ?: "Connection failed"
                logger.warn("Positive deposit scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "DEPOSIT_50_EUR",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
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

                if (checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)) {
                    val errorBody = depositResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Negative deposit test failed with server/tunnel error ${depositResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${depositResponse.status.value}"
                        )
                    )
                    continue
                }

                // Check that bank rejected the negative deposit (e.g. 400 Bad Request, 422 Unprocessable Entity)
                val receivedClientError = depositResponse.status.value in 400..499

                if (!receivedClientError) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Expected bank to reject negative deposit with 400/422, but received ${depositResponse.status}",
                            expected = expectedStr,
                            actual = "Actual: Deposit accepted with HTTP ${depositResponse.status}"
                        )
                    )
                    continue
                }

                // Verify that it doesn't end up with 0 euro in the account
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "DEPOSIT_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Statement check failed with server/tunnel error ${statementResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${statementResponse.status.value}"
                        )
                    )
                    continue
                }

                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                val accountHasZero = if (statement != null) {
                    statement.lines.isNotEmpty() && statement.currentBalanceCents == 0L
                } else {
                    false
                }

                if (accountHasZero) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
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
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
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
                val err = e.message ?: "Connection failed"
                logger.warn("Negative deposit scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "DEPOSIT_NEGATIVE_50_EUR",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    // Phase 2: Happy withdrawal (Deposit 50 + Withdraw 20 -> balance 30)
    suspend fun runPositiveWithdrawScenario(): List<ScenarioResult> {
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
                    checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                    val errorBody = depositResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_HAPPY_20_EUR",
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
                    checkResponseTunnelStatus(Bic(bank.bic), withdrawResponse.status)
                    val errorBody = withdrawResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_HAPPY_20_EUR",
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
                checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)
                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_HAPPY_20_EUR",
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
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L, withdrawAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_HAPPY_20_EUR",
                            passed = true,
                            message = "Successfully deposited 50.00 EUR, withdrew 20.00 EUR, and verified statement matched ledger",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    val mismatchReason = if (statement.currentBalanceCents != ledger.expectedBalanceCents) {
                        "Expected statement balance ${ledger.expectedBalanceCents} cents, but got ${statement.currentBalanceCents} cents"
                    } else {
                        "Statement lines do not match expected format"
                    }
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_HAPPY_20_EUR",
                            passed = false,
                            message = mismatchReason,
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection failed"
                logger.warn("Deposit & withdraw scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "WITHDRAW_HAPPY_20_EUR",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    // Phase 2: Failure withdrawal - negative amount (-50 EUR) should fail and not create money
    suspend fun runNegativeWithdrawScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
            val ledger = ScenarioLedger(iban)
            val negativeWithdrawAmountCents = -5000L // -50.00 EUR
            ledger.recordWithdraw(negativeWithdrawAmountCents)
            val expectedStr = ledger.formatExpectedSummary()

            try {
                val withdrawResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                    contentType(ContentType.Application.Json)
                    setBody(WithdrawRequest(iban = iban, amountCents = negativeWithdrawAmountCents))
                }

                if (checkResponseTunnelStatus(Bic(bank.bic), withdrawResponse.status)) {
                    val errorBody = withdrawResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Negative withdrawal test failed with server/tunnel error ${withdrawResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${withdrawResponse.status.value}"
                        )
                    )
                    continue
                }

                val receivedClientError = withdrawResponse.status.value in 400..499

                if (!receivedClientError) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Expected bank to reject negative withdrawal with 400/422, but received ${withdrawResponse.status}",
                            expected = expectedStr,
                            actual = "Actual: Withdrawal accepted with HTTP ${withdrawResponse.status}"
                        )
                    )
                    continue
                }

                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Statement check failed with server/tunnel error ${statementResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${statementResponse.status.value}"
                        )
                    )
                    continue
                }

                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                val accountHasPositive = if (statement != null) {
                    statement.currentBalanceCents > 0L
                } else {
                    false
                }

                if (accountHasPositive) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_NEGATIVE_50_EUR",
                            passed = false,
                            message = "Negative withdrawal created positive balance on account",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_NEGATIVE_50_EUR",
                            passed = true,
                            message = "Central service received expected error (${withdrawResponse.status.value}) and no positive balance was created",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${withdrawResponse.status.value} (Rejected) | Statement: ${if (statement == null) "HTTP ${statementResponse.status.value}" else "${statement.lines.size} lines"}"
                        )
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection failed"
                logger.warn("Negative withdraw scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "WITHDRAW_NEGATIVE_50_EUR",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    // Phase 2: Failure withdrawal - withdraw more than is on account (Deposit 20 EUR + Withdraw 50 EUR -> should fail)
    suspend fun runOverdraftWithdrawScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
            val ledger = ScenarioLedger(iban)
            val depositAmountCents = 2000L // 20.00 EUR
            val overWithdrawAmountCents = 5000L // 50.00 EUR
            ledger.recordDeposit(depositAmountCents)
            ledger.recordWithdraw(overWithdrawAmountCents) // Expected to fail, so expected balance remains 20 EUR
            val expectedStr = ledger.formatExpectedSummary()

            try {
                // 1. Initial deposit of 20 EUR
                val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(iban = iban, amountCents = depositAmountCents))
                }

                if (depositResponse.status != HttpStatusCode.OK) {
                    checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                    val errorBody = depositResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = false,
                            message = "Initial deposit of 20.00 EUR failed: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                        )
                    )
                    continue
                }

                // 2. Withdraw 50 EUR (should be rejected)
                val withdrawResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                    contentType(ContentType.Application.Json)
                    setBody(WithdrawRequest(iban = iban, amountCents = overWithdrawAmountCents))
                }

                if (checkResponseTunnelStatus(Bic(bank.bic), withdrawResponse.status)) {
                    val errorBody = withdrawResponse.bodyAsText()
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = false,
                            message = "Overdraft withdrawal test failed with server/tunnel error ${withdrawResponse.status.value}: $errorBody",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${withdrawResponse.status.value}"
                        )
                    )
                    continue
                }

                val receivedClientError = withdrawResponse.status.value in 400..499

                if (!receivedClientError) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = false,
                            message = "Expected bank to reject withdrawal of 50.00 EUR when balance is 20.00 EUR, but received ${withdrawResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: Withdrawal accepted with HTTP ${withdrawResponse.status.value}"
                        )
                    )
                    continue
                }

                // 3. Verify balance remains 20 EUR
                val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
                if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = false,
                            message = "Statement check failed with server/tunnel error ${statementResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: HTTP ${statementResponse.status.value}"
                        )
                    )
                    continue
                }

                val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
                val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

                if (statement != null && statement.currentBalanceCents == depositAmountCents) {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = true,
                            message = "Central service received expected error (${withdrawResponse.status.value}) and balance remained intact at 20.00 EUR",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                            passed = false,
                            message = "Withdrawal was rejected but statement balance changed from 20.00 EUR",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection failed"
                logger.warn("Overdraft withdraw scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    // Phase 3: Intra-bank transfer (within same bank between 2 IBANs)
    suspend fun runIntraBankTransferScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        for (bank in banks) {
            val ibanFrom = "BE68${bank.bic}000${(1000000..4999999).random()}"
            val ibanTo = "BE68${bank.bic}000${(5000000..9999999).random()}"
            val initialDepositCents = 5000L // 50.00 EUR
            val transferAmountCents = 2000L // 20.00 EUR

            val ledgerFrom = ScenarioLedger(ibanFrom)
            ledgerFrom.recordDeposit(initialDepositCents)
            ledgerFrom.recordTransferOut(transferAmountCents)

            val ledgerTo = ScenarioLedger(ibanTo)
            ledgerTo.recordTransferIn(transferAmountCents)

            val expectedStr = "From: ${ledgerFrom.formatExpectedSummary()} | To: ${ledgerTo.formatExpectedSummary()}"

            try {
                // 1. Deposit 50 EUR to from-account
                val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(iban = ibanFrom, amountCents = initialDepositCents))
                }

                if (depositResponse.status != HttpStatusCode.OK) {
                    checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "INTRA_BANK_TRANSFER",
                            passed = false,
                            message = "Initial deposit to sender IBAN failed: ${depositResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                        )
                    )
                    continue
                }

                // 2. Perform transfer from IBAN1 to IBAN2 (Intra-bank transfer)
                // We use transfer-out on bank or transfer-in directly
                val transferRequest = TransferRequest(
                    transactionId = "intra-${System.currentTimeMillis()}-${(100..999).random()}",
                    fromIban = ibanFrom,
                    toIban = ibanTo,
                    amountCents = transferAmountCents,
                    timestamp = Instant.now().toString(),
                    reference = "Intra-bank test transfer"
                )

                // Debit sender via withdraw and credit receiver via transfer-in/deposit
                val withdrawSenderResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                    contentType(ContentType.Application.Json)
                    setBody(WithdrawRequest(iban = ibanFrom, amountCents = transferAmountCents))
                }

                val transferInReceiverResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/transfer-in") {
                    contentType(ContentType.Application.Json)
                    setBody(transferRequest)
                }

                if (withdrawSenderResponse.status != HttpStatusCode.OK || transferInReceiverResponse.status != HttpStatusCode.OK) {
                    checkResponseTunnelStatus(Bic(bank.bic), withdrawSenderResponse.status)
                    checkResponseTunnelStatus(Bic(bank.bic), transferInReceiverResponse.status)
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "INTRA_BANK_TRANSFER",
                            passed = false,
                            message = "Intra-bank transfer failed: Sender debit status=${withdrawSenderResponse.status.value}, Receiver credit status=${transferInReceiverResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: Debit HTTP ${withdrawSenderResponse.status.value}, Credit HTTP ${transferInReceiverResponse.status.value}"
                        )
                    )
                    continue
                }

                // 3. Verify sender and receiver statements and balances
                val stmtFromResp: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$ibanFrom")
                val stmtToResp: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$ibanTo")
                checkResponseTunnelStatus(Bic(bank.bic), stmtFromResp.status)
                checkResponseTunnelStatus(Bic(bank.bic), stmtToResp.status)

                val stmtFrom: StatementResponse? = if (stmtFromResp.status == HttpStatusCode.OK) stmtFromResp.body() else null
                val stmtTo: StatementResponse? = if (stmtToResp.status == HttpStatusCode.OK) stmtToResp.body() else null

                val actualStr = "From: ${ScenarioLedger.formatActualSummary(stmtFromResp.status, stmtFrom)} | To: ${ScenarioLedger.formatActualSummary(stmtToResp.status, stmtTo)}"

                val fromOk = stmtFrom != null && stmtFrom.currentBalanceCents == ledgerFrom.expectedBalanceCents
                val toOk = stmtTo != null && stmtTo.currentBalanceCents == ledgerTo.expectedBalanceCents

                if (fromOk && toOk) {
                    bankRegistry.recordTransaction(Bic(bank.bic), true, 1L, transferAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "INTRA_BANK_TRANSFER",
                            passed = true,
                            message = "Intra-bank transfer successfully updated both accounts (Sender balance: 30.00 EUR, Receiver balance: 20.00 EUR)",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bank.bic,
                            bankName = bank.name,
                            scenario = "INTRA_BANK_TRANSFER",
                            passed = false,
                            message = "Intra-bank transfer verification failed: FromBalance=${stmtFrom?.currentBalanceCents} (expected ${ledgerFrom.expectedBalanceCents}), ToBalance=${stmtTo?.currentBalanceCents} (expected ${ledgerTo.expectedBalanceCents})",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection failed"
                logger.warn("Intra-bank transfer scenario failed for ${bank.name}: $err")
                bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bank.bic,
                        bankName = bank.name,
                        scenario = "INTRA_BANK_TRANSFER",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    // Phase 3: Inter-bank transfer (cross-bank over SWIFT Hub)
    suspend fun runCrossBankTransferScenario(): List<ScenarioResult> {
        val banks = bankRegistry.getAllBanks()
        val results = mutableListOf<ScenarioResult>()

        if (banks.isEmpty()) return results

        for (bankA in banks) {
            // Find another registered bank or use self if only 1 bank is registered
            val bankB = banks.firstOrNull { it.bic != bankA.bic } ?: bankA

            val ibanFrom = "BE68${bankA.bic}000${(1000000..4999999).random()}"
            val ibanTo = "BE68${bankB.bic}000${(5000000..9999999).random()}"
            val initialDepositCents = 5000L // 50.00 EUR
            val transferAmountCents = 2000L // 20.00 EUR

            val ledgerFrom = ScenarioLedger(ibanFrom)
            ledgerFrom.recordDeposit(initialDepositCents)
            ledgerFrom.recordTransferOut(transferAmountCents)

            val ledgerTo = ScenarioLedger(ibanTo)
            ledgerTo.recordTransferIn(transferAmountCents)

            val expectedStr = "From (${bankA.bic}): ${ledgerFrom.formatExpectedSummary()} | To (${bankB.bic}): ${ledgerTo.formatExpectedSummary()}"

            try {
                // 1. Deposit 50 EUR to Bank A
                val depositResponse: HttpResponse = httpClient.post("${bankA.webhookUrl}/api/deposit") {
                    contentType(ContentType.Application.Json)
                    setBody(DepositRequest(iban = ibanFrom, amountCents = initialDepositCents))
                }

                if (depositResponse.status != HttpStatusCode.OK) {
                    checkResponseTunnelStatus(Bic(bankA.bic), depositResponse.status)
                    bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bankA.bic,
                            bankName = bankA.name,
                            scenario = "CROSS_BANK_TRANSFER",
                            passed = false,
                            message = "Initial deposit to sender IBAN at ${bankA.name} failed: ${depositResponse.status.value}",
                            expected = expectedStr,
                            actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                        )
                    )
                    continue
                }

                // 2. Perform cross-bank transfer via SWIFT Router
                val transferRequest = TransferRequest(
                    transactionId = "swift-${System.currentTimeMillis()}-${(100..999).random()}",
                    fromIban = ibanFrom,
                    toIban = ibanTo,
                    amountCents = transferAmountCents,
                    timestamp = Instant.now().toString(),
                    reference = "Cross-bank test transfer"
                )

                // Debit sender Bank A via withdraw
                val withdrawSenderResponse: HttpResponse = httpClient.post("${bankA.webhookUrl}/api/withdraw") {
                    contentType(ContentType.Application.Json)
                    setBody(WithdrawRequest(iban = ibanFrom, amountCents = transferAmountCents))
                }

                // Route through SWIFT hub router to Bank B
                val routerResult = transferRouter.routeTransfer(transferRequest)

                if (withdrawSenderResponse.status != HttpStatusCode.OK || routerResult.status != TransferStatus.ACCEPTED) {
                    checkResponseTunnelStatus(Bic(bankA.bic), withdrawSenderResponse.status)
                    bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bankA.bic,
                            bankName = bankA.name,
                            scenario = "CROSS_BANK_TRANSFER",
                            passed = false,
                            message = "Cross-bank transfer routing failed: Debit status=${withdrawSenderResponse.status.value}, Router status=${routerResult.status} (${routerResult.message})",
                            expected = expectedStr,
                            actual = "Actual: Debit HTTP ${withdrawSenderResponse.status.value}, SWIFT Router: ${routerResult.status}"
                        )
                    )
                    continue
                }

                // 3. Verify Bank A and Bank B statements and balances
                val stmtFromResp: HttpResponse = httpClient.get("${bankA.webhookUrl}/api/statement/$ibanFrom")
                val stmtToResp: HttpResponse = httpClient.get("${bankB.webhookUrl}/api/statement/$ibanTo")
                checkResponseTunnelStatus(Bic(bankA.bic), stmtFromResp.status)
                checkResponseTunnelStatus(Bic(bankB.bic), stmtToResp.status)

                val stmtFrom: StatementResponse? = if (stmtFromResp.status == HttpStatusCode.OK) stmtFromResp.body() else null
                val stmtTo: StatementResponse? = if (stmtToResp.status == HttpStatusCode.OK) stmtToResp.body() else null

                val actualStr = "From (${bankA.bic}): ${ScenarioLedger.formatActualSummary(stmtFromResp.status, stmtFrom)} | To (${bankB.bic}): ${ScenarioLedger.formatActualSummary(stmtToResp.status, stmtTo)}"

                val fromOk = stmtFrom != null && stmtFrom.currentBalanceCents == ledgerFrom.expectedBalanceCents
                val toOk = stmtTo != null && stmtTo.currentBalanceCents == ledgerTo.expectedBalanceCents

                if (fromOk && toOk) {
                    bankRegistry.recordTransaction(Bic(bankA.bic), true, 1L, transferAmountCents)
                    results.add(
                        ScenarioResult(
                            bic = bankA.bic,
                            bankName = bankA.name,
                            scenario = "CROSS_BANK_TRANSFER",
                            passed = true,
                            message = "Cross-bank transfer over SWIFT Hub successfully settled (${bankA.bic} sender balance: 30.00 EUR, ${bankB.bic} receiver balance: 20.00 EUR)",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                } else {
                    bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                    results.add(
                        ScenarioResult(
                            bic = bankA.bic,
                            bankName = bankA.name,
                            scenario = "CROSS_BANK_TRANSFER",
                            passed = false,
                            message = "Cross-bank transfer verification failed: FromBalance=${stmtFrom?.currentBalanceCents} (expected ${ledgerFrom.expectedBalanceCents}), ToBalance=${stmtTo?.currentBalanceCents} (expected ${ledgerTo.expectedBalanceCents})",
                            expected = expectedStr,
                            actual = actualStr
                        )
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection failed"
                logger.warn("Cross-bank transfer scenario failed for ${bankA.name}: $err")
                bankRegistry.updateStatus(Bic(bankA.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
                bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                results.add(
                    ScenarioResult(
                        bic = bankA.bic,
                        bankName = bankA.name,
                        scenario = "CROSS_BANK_TRANSFER",
                        passed = false,
                        message = "Exception during test: $err",
                        expected = expectedStr,
                        actual = "Actual: Exception: $err"
                    )
                )
            }
        }

        return results
    }

    suspend fun runDepositWithdrawScenario(): List<ScenarioResult> {
        return runPositiveWithdrawScenario()
    }

    suspend fun runAllScenarios(): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        results.addAll(runDepositsPhase())
        results.addAll(runWithdrawalsPhase())
        results.addAll(runTransfersPhase())
        return results
    }

    suspend fun runAllDepositScenarios(): List<ScenarioResult> {
        return runDepositsPhase()
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
