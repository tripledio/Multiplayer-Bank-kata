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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.craftedsw.contracts.BalanceResponse
import org.craftedsw.contracts.BankNodeInfo
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.DebugReport
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val bankDispatcher: CoroutineDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
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
    suspend fun runDepositsPhase(): List<ScenarioResult> = coroutineScope {
        val posDeferred = async(bankDispatcher) { runPositiveDepositScenario() }
        val negDeferred = async(bankDispatcher) { runNegativeDepositScenario() }
        posDeferred.await() + negDeferred.await()
    }

    // --- Phase 2: Withdrawals ---
    suspend fun runWithdrawalsPhase(): List<ScenarioResult> = coroutineScope {
        val posDeferred = async(bankDispatcher) { runPositiveWithdrawScenario() }
        val negDeferred = async(bankDispatcher) { runNegativeWithdrawScenario() }
        val overDeferred = async(bankDispatcher) { runOverdraftWithdrawScenario() }
        posDeferred.await() + negDeferred.await() + overDeferred.await()
    }

    // --- Phase 3: Transfers ---
    suspend fun runTransfersPhase(): List<ScenarioResult> = coroutineScope {
        val intraDeferred = async(bankDispatcher) { runIntraBankTransferScenario() }
        val crossDeferred = async(bankDispatcher) { runCrossBankTransferScenario() }
        intraDeferred.await() + crossDeferred.await()
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

    private suspend fun sendDebugFailure(
        webhookUrl: String,
        scenario: String,
        message: String,
        httpRequest: String,
        expectedResponse: String,
        actualResponse: String
    ) {
        try {
            val report = DebugReport(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                scenario = scenario,
                message = message,
                httpRequest = httpRequest,
                expectedResponse = expectedResponse,
                actualResponse = actualResponse,
                passed = false
            )
            httpClient.post("$webhookUrl/api/debug") {
                contentType(ContentType.Application.Json)
                setBody(report)
            }
        } catch (e: Exception) {
            logger.debug("Could not deliver debug report to $webhookUrl: ${e.message}")
        }
    }

    private fun formatDepositHttp(webhookUrl: String, title: String, iban: String, amountCents: Long): String {
        return """
### $title
POST $webhookUrl/api/deposit
Content-Type: application/json

{
  "iban": "$iban",
  "amountCents": $amountCents
}
        """.trimIndent()
    }

    private fun formatWithdrawHttp(webhookUrl: String, title: String, iban: String, amountCents: Long): String {
        return """
### $title
POST $webhookUrl/api/withdraw
Content-Type: application/json

{
  "iban": "$iban",
  "amountCents": $amountCents
}
        """.trimIndent()
    }

    private fun formatStatementHttp(webhookUrl: String, title: String, iban: String): String {
        return """
### $title
GET $webhookUrl/api/statement/$iban
        """.trimIndent()
    }

    private fun formatTransferInHttp(webhookUrl: String, title: String, req: TransferRequest): String {
        return """
### $title
POST $webhookUrl/api/transfer-in
Content-Type: application/json

{
  "transactionId": "${req.transactionId}",
  "fromIban": "${req.fromIban}",
  "toIban": "${req.toIban}",
  "amountCents": ${req.amountCents},
  "timestamp": "${req.timestamp}",
  "reference": "${req.reference}"
}
        """.trimIndent()
    }

    suspend fun runPositiveDepositScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runPositiveDepositForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runPositiveDepositForBank(bank: BankNodeInfo): ScenarioResult {
        val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
        val ledger = ScenarioLedger(iban)
        val depositAmountCents = 5000L // 50.00 EUR
        ledger.recordDeposit(depositAmountCents)
        val expectedStr = ledger.formatExpectedSummary()

        return try {
            val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = iban, amountCents = depositAmountCents))
            }

            if (depositResponse.status != HttpStatusCode.OK) {
                checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                val errorBody = depositResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Deposit failed with status ${depositResponse.status.value}: $errorBody"
                val actual = "Actual: Deposit HTTP ${depositResponse.status.value} ($errorBody)"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_50_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Deposit 50.00 EUR", iban, depositAmountCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$iban\",\n  \"balanceCents\": $depositAmountCents\n}",
                    actualResponse = "HTTP ${depositResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            val depositBody: BalanceResponse = depositResponse.body()
            if (depositBody.balanceCents != depositAmountCents) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Expected balance $depositAmountCents cents, but deposit response returned ${depositBody.balanceCents} cents"
                val actual = "Actual: Deposit balance returned ${ScenarioLedger.formatAmount(depositBody.balanceCents)} EUR"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_50_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Deposit 50.00 EUR", iban, depositAmountCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$iban\",\n  \"balanceCents\": $depositAmountCents\n}",
                    actualResponse = "HTTP 200 OK\n{\n  \"iban\": \"${depositBody.iban}\",\n  \"balanceCents\": ${depositBody.balanceCents}\n}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            // Verify statement/balance for the newly created IBAN
            val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
            checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)
            val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
            val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

            if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Statement query failed with status ${statementResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_50_EUR",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement for Account", iban),
                    expectedResponse = "HTTP 200 OK\n$expectedStr",
                    actualResponse = "HTTP ${statementResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
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
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_50_EUR",
                    passed = true,
                    message = "Successfully deposited 50.00 EUR to $iban and verified statement matched ledger",
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val mismatchReason = if (statement.currentBalanceCents != depositAmountCents) {
                    "Expected statement balance $depositAmountCents cents, but got ${statement.currentBalanceCents} cents"
                } else {
                    "Statement lines do not match expected format"
                }
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_50_EUR",
                    message = mismatchReason,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement for Account", iban),
                    expectedResponse = "HTTP 200 OK\n$expectedStr",
                    actualResponse = "HTTP 200 OK\n$actualStr"
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_50_EUR",
                    passed = false,
                    message = mismatchReason,
                    expected = expectedStr,
                    actual = actualStr
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Positive deposit scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "DEPOSIT_50_EUR",
                message = "Exception during test: $err",
                httpRequest = formatDepositHttp(bank.webhookUrl, "Deposit 50.00 EUR", iban, depositAmountCents),
                expectedResponse = "HTTP 200 OK",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "DEPOSIT_50_EUR",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    suspend fun runNegativeDepositScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runNegativeDepositForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runNegativeDepositForBank(bank: BankNodeInfo): ScenarioResult {
        val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
        val ledger = ScenarioLedger(iban)
        val negativeDepositAmountCents = -5000L // -50.00 EUR
        ledger.recordDeposit(negativeDepositAmountCents)
        val expectedStr = ledger.formatExpectedSummary()

        return try {
            val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = iban, amountCents = negativeDepositAmountCents))
            }

            if (checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)) {
                val errorBody = depositResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Negative deposit test failed with server/tunnel error ${depositResponse.status.value}: $errorBody"
                val actual = "Actual: HTTP ${depositResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Attempt Negative Deposit (-50.00 EUR)", iban, negativeDepositAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity\n(Rejection of negative deposit)",
                    actualResponse = "HTTP ${depositResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            // Check that bank rejected the negative deposit (e.g. 400 Bad Request, 422 Unprocessable Entity)
            val receivedClientError = depositResponse.status.value in 400..499

            if (!receivedClientError) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Expected bank to reject negative deposit with 400/422, but received ${depositResponse.status}"
                val actual = "Actual: Deposit accepted with HTTP ${depositResponse.status}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Attempt Negative Deposit (-50.00 EUR)", iban, negativeDepositAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity\n(Negative deposit should be rejected)",
                    actualResponse = "HTTP ${depositResponse.status.value} (Deposit was improperly accepted)"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            // Verify that it doesn't end up with 0 euro in the account
            val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
            if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Statement check failed with server/tunnel error ${statementResponse.status.value}"
                val actual = "Actual: HTTP ${statementResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement for Rejected Account", iban),
                    expectedResponse = "HTTP 400 / HTTP 404 or empty statement (No account created)",
                    actualResponse = "HTTP ${statementResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
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
                val failureMsg = "Deposit of negative amount failed, but account was created with 0 EUR statement"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Attempt Negative Deposit (-50.00 EUR)", iban, negativeDepositAmountCents),
                    expectedResponse = "No account created or empty lines on rejected deposit",
                    actualResponse = "Account exists with 0.00 EUR statement and lines: ${statement?.lines}"
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "DEPOSIT_NEGATIVE_50_EUR",
                    passed = true,
                    message = "Central service received expected error (${depositResponse.status.value}) and account was not initialized with 0 EUR",
                    expected = expectedStr,
                    actual = "Actual: HTTP ${depositResponse.status.value} (Rejected) | Statement: ${if (statement == null) "HTTP ${statementResponse.status.value}" else "${statement.lines.size} lines"}"
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Negative deposit scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "DEPOSIT_NEGATIVE_50_EUR",
                message = "Exception during test: $err",
                httpRequest = formatDepositHttp(bank.webhookUrl, "Attempt Negative Deposit (-50.00 EUR)", iban, negativeDepositAmountCents),
                expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "DEPOSIT_NEGATIVE_50_EUR",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    // Phase 2: Happy withdrawal (Deposit 50 + Withdraw 20 -> balance 30)
    suspend fun runPositiveWithdrawScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runPositiveWithdrawForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runPositiveWithdrawForBank(bank: BankNodeInfo): ScenarioResult {
        val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
        val ledger = ScenarioLedger(iban)
        val depositAmountCents = 5000L // 50.00 EUR
        val withdrawAmountCents = 2000L // 20.00 EUR
        ledger.recordDeposit(depositAmountCents)
        ledger.recordWithdraw(withdrawAmountCents)
        val expectedStr = ledger.formatExpectedSummary()

        return try {
            // 1. Deposit 50 EUR
            val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = iban, amountCents = depositAmountCents))
            }

            if (depositResponse.status != HttpStatusCode.OK) {
                checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                val errorBody = depositResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Initial deposit of 50.00 EUR failed with status ${depositResponse.status.value}: $errorBody"
                val actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Initial Deposit for Withdrawal Test", iban, depositAmountCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$iban\",\n  \"balanceCents\": $depositAmountCents\n}",
                    actualResponse = "HTTP ${depositResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
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
                val failureMsg = "Withdrawal of 20.00 EUR failed with status ${withdrawResponse.status.value}: $errorBody"
                val actual = "Actual: Withdrawal HTTP ${withdrawResponse.status.value} ($errorBody)"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Withdraw 20.00 EUR (after 50.00 EUR deposit)", iban, withdrawAmountCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$iban\",\n  \"balanceCents\": 3000\n}",
                    actualResponse = "HTTP ${withdrawResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            // 3. Query statement and compare actual vs expected
            val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
            checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)
            val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
            val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

            if (statementResponse.status != HttpStatusCode.OK || statement == null) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Statement query failed with status ${statementResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement after Withdrawal", iban),
                    expectedResponse = "HTTP 200 OK\n$expectedStr",
                    actualResponse = "HTTP ${statementResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
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
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    passed = true,
                    message = "Successfully deposited 50.00 EUR, withdrew 20.00 EUR, and verified statement matched ledger",
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val mismatchReason = if (statement.currentBalanceCents != ledger.expectedBalanceCents) {
                    "Expected statement balance ${ledger.expectedBalanceCents} cents, but got ${statement.currentBalanceCents} cents"
                } else {
                    "Statement lines do not match expected format"
                }
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    message = mismatchReason,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement after Withdrawal", iban),
                    expectedResponse = "HTTP 200 OK\n$expectedStr",
                    actualResponse = "HTTP 200 OK\n$actualStr"
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_HAPPY_20_EUR",
                    passed = false,
                    message = mismatchReason,
                    expected = expectedStr,
                    actual = actualStr
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Deposit & withdraw scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "WITHDRAW_HAPPY_20_EUR",
                message = "Exception during test: $err",
                httpRequest = formatWithdrawHttp(bank.webhookUrl, "Withdraw 20.00 EUR", iban, withdrawAmountCents),
                expectedResponse = "HTTP 200 OK",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "WITHDRAW_HAPPY_20_EUR",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    // Phase 2: Failure withdrawal - negative amount (-50 EUR) should fail and not create money
    suspend fun runNegativeWithdrawScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runNegativeWithdrawForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runNegativeWithdrawForBank(bank: BankNodeInfo): ScenarioResult {
        val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
        val ledger = ScenarioLedger(iban)
        val negativeWithdrawAmountCents = -5000L // -50.00 EUR
        ledger.recordWithdraw(negativeWithdrawAmountCents)
        val expectedStr = ledger.formatExpectedSummary()

        return try {
            val withdrawResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                contentType(ContentType.Application.Json)
                setBody(WithdrawRequest(iban = iban, amountCents = negativeWithdrawAmountCents))
            }

            if (checkResponseTunnelStatus(Bic(bank.bic), withdrawResponse.status)) {
                val errorBody = withdrawResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Negative withdrawal test failed with server/tunnel error ${withdrawResponse.status.value}: $errorBody"
                val actual = "Actual: HTTP ${withdrawResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Negative Withdrawal (-50.00 EUR)", iban, negativeWithdrawAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity\n(Negative withdrawal should be rejected)",
                    actualResponse = "HTTP ${withdrawResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            val receivedClientError = withdrawResponse.status.value in 400..499

            if (!receivedClientError) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Expected bank to reject negative withdrawal with 400/422, but received ${withdrawResponse.status}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Negative Withdrawal (-50.00 EUR)", iban, negativeWithdrawAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity",
                    actualResponse = "HTTP ${withdrawResponse.status.value} (Withdrawal was improperly accepted)"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Withdrawal accepted with HTTP ${withdrawResponse.status}"
                )
            }

            val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
            if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Statement check failed with server/tunnel error ${statementResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement for Rejected Withdrawal", iban),
                    expectedResponse = "HTTP 400 / HTTP 404 or empty statement",
                    actualResponse = "HTTP ${statementResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: HTTP ${statementResponse.status.value}"
                )
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
                val failureMsg = "Negative withdrawal created positive balance on account"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Negative Withdrawal (-50.00 EUR)", iban, negativeWithdrawAmountCents),
                    expectedResponse = "Balance <= 0 or rejected",
                    actualResponse = "Balance = ${statement?.currentBalanceCents} cents"
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_NEGATIVE_50_EUR",
                    passed = true,
                    message = "Central service received expected error (${withdrawResponse.status.value}) and no positive balance was created",
                    expected = expectedStr,
                    actual = "Actual: HTTP ${withdrawResponse.status.value} (Rejected) | Statement: ${if (statement == null) "HTTP ${statementResponse.status.value}" else "${statement.lines.size} lines"}"
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Negative withdraw scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "WITHDRAW_NEGATIVE_50_EUR",
                message = "Exception during test: $err",
                httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Negative Withdrawal (-50.00 EUR)", iban, negativeWithdrawAmountCents),
                expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "WITHDRAW_NEGATIVE_50_EUR",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    // Phase 2: Failure withdrawal - withdraw more than is on account (Deposit 20 EUR + Withdraw 50 EUR -> should fail)
    suspend fun runOverdraftWithdrawScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runOverdraftWithdrawForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runOverdraftWithdrawForBank(bank: BankNodeInfo): ScenarioResult {
        val iban = "BE68${bank.bic}000${(1000000..9999999).random()}"
        val ledger = ScenarioLedger(iban)
        val depositAmountCents = 2000L // 20.00 EUR
        val overWithdrawAmountCents = 5000L // 50.00 EUR
        ledger.recordDeposit(depositAmountCents)
        ledger.recordWithdraw(overWithdrawAmountCents) // Expected to fail, so expected balance remains 20 EUR
        val expectedStr = ledger.formatExpectedSummary()

        return try {
            // 1. Initial deposit of 20 EUR
            val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = iban, amountCents = depositAmountCents))
            }

            if (depositResponse.status != HttpStatusCode.OK) {
                checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                val errorBody = depositResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Initial deposit of 20.00 EUR failed: $errorBody"
                val actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Deposit 20.00 EUR (Overdraft Test Setup)", iban, depositAmountCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$iban\",\n  \"balanceCents\": $depositAmountCents\n}",
                    actualResponse = "HTTP ${depositResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            // 2. Withdraw 50 EUR (should be rejected)
            val withdrawResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/withdraw") {
                contentType(ContentType.Application.Json)
                setBody(WithdrawRequest(iban = iban, amountCents = overWithdrawAmountCents))
            }

            if (checkResponseTunnelStatus(Bic(bank.bic), withdrawResponse.status)) {
                val errorBody = withdrawResponse.bodyAsText()
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Overdraft withdrawal test failed with server/tunnel error ${withdrawResponse.status.value}: $errorBody"
                val actual = "Actual: HTTP ${withdrawResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Overdraft Withdrawal (50.00 EUR when balance is 20.00 EUR)", iban, overWithdrawAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity\n(Insufficient funds)",
                    actualResponse = "HTTP ${withdrawResponse.status.value}\n$errorBody"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actual
                )
            }

            val receivedClientError = withdrawResponse.status.value in 400..499

            if (!receivedClientError) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Expected bank to reject withdrawal of 50.00 EUR when balance is 20.00 EUR, but received ${withdrawResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    message = failureMsg,
                    httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Overdraft Withdrawal (50.00 EUR when balance is 20.00 EUR)", iban, overWithdrawAmountCents),
                    expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity (Insufficient funds)",
                    actualResponse = "HTTP ${withdrawResponse.status.value} (Overdraft was improperly permitted)"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Withdrawal accepted with HTTP ${withdrawResponse.status.value}"
                )
            }

            // 3. Verify balance remains 20 EUR
            val statementResponse: HttpResponse = httpClient.get("${bank.webhookUrl}/api/statement/$iban")
            if (checkResponseTunnelStatus(Bic(bank.bic), statementResponse.status)) {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Statement check failed with server/tunnel error ${statementResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement after Rejected Overdraft", iban),
                    expectedResponse = "HTTP 200 OK with balance 20.00 EUR",
                    actualResponse = "HTTP ${statementResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: HTTP ${statementResponse.status.value}"
                )
            }

            val statement: StatementResponse? = if (statementResponse.status == HttpStatusCode.OK) statementResponse.body() else null
            val actualStr = ScenarioLedger.formatActualSummary(statementResponse.status, statement)

            if (statement != null && statement.currentBalanceCents == depositAmountCents) {
                bankRegistry.recordTransaction(Bic(bank.bic), true, 1L)
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = true,
                    message = "Central service received expected error (${withdrawResponse.status.value}) and balance remained intact at 20.00 EUR",
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Withdrawal was rejected but statement balance changed from 20.00 EUR"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    message = failureMsg,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement after Rejected Overdraft", iban),
                    expectedResponse = "Balance must remain intact at 20.00 EUR",
                    actualResponse = "Statement balance: ${statement?.currentBalanceCents} cents"
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Overdraft withdraw scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                message = "Exception during test: $err",
                httpRequest = formatWithdrawHttp(bank.webhookUrl, "Attempt Overdraft Withdrawal", iban, overWithdrawAmountCents),
                expectedResponse = "HTTP 400 Bad Request or HTTP 422 Unprocessable Entity",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "WITHDRAW_INSUFFICIENT_FUNDS",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    // Phase 3: Intra-bank transfer (within same bank between 2 IBANs)
    suspend fun runIntraBankTransferScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        banks.map { bank ->
            async(bankDispatcher) {
                runIntraBankTransferForBank(bank)
            }
        }.awaitAll()
    }

    private suspend fun runIntraBankTransferForBank(bank: BankNodeInfo): ScenarioResult {
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

        return try {
            // 1. Deposit 50 EUR to from-account
            val depositResponse: HttpResponse = httpClient.post("${bank.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = ibanFrom, amountCents = initialDepositCents))
            }

            if (depositResponse.status != HttpStatusCode.OK) {
                checkResponseTunnelStatus(Bic(bank.bic), depositResponse.status)
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val failureMsg = "Initial deposit to sender IBAN failed: ${depositResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "INTRA_BANK_TRANSFER",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bank.webhookUrl, "Deposit 50.00 EUR to Sender Account", ibanFrom, initialDepositCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$ibanFrom\",\n  \"balanceCents\": $initialDepositCents\n}",
                    actualResponse = "HTTP ${depositResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "INTRA_BANK_TRANSFER",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                )
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
                val failureMsg = "Intra-bank transfer failed: Sender debit status=${withdrawSenderResponse.status.value}, Receiver credit status=${transferInReceiverResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "INTRA_BANK_TRANSFER",
                    message = failureMsg,
                    httpRequest = if (withdrawSenderResponse.status != HttpStatusCode.OK) {
                        formatWithdrawHttp(bank.webhookUrl, "Debit Sender for Intra-Bank Transfer", ibanFrom, transferAmountCents)
                    } else {
                        formatTransferInHttp(bank.webhookUrl, "Credit Receiver for Intra-Bank Transfer", transferRequest)
                    },
                    expectedResponse = "HTTP 200 OK (Accepted debit & incoming transfer webhook)",
                    actualResponse = "Debit: HTTP ${withdrawSenderResponse.status.value}, Credit: HTTP ${transferInReceiverResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "INTRA_BANK_TRANSFER",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Debit HTTP ${withdrawSenderResponse.status.value}, Credit HTTP ${transferInReceiverResponse.status.value}"
                )
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
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "INTRA_BANK_TRANSFER",
                    passed = true,
                    message = "Intra-bank transfer successfully updated both accounts (Sender balance: 30.00 EUR, Receiver balance: 20.00 EUR)",
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
                val mismatchReason = "Intra-bank transfer verification failed: FromBalance=${stmtFrom?.currentBalanceCents} (expected ${ledgerFrom.expectedBalanceCents}), ToBalance=${stmtTo?.currentBalanceCents} (expected ${ledgerTo.expectedBalanceCents})"
                sendDebugFailure(
                    webhookUrl = bank.webhookUrl,
                    scenario = "INTRA_BANK_TRANSFER",
                    message = mismatchReason,
                    httpRequest = formatStatementHttp(bank.webhookUrl, "Query Statement for Sender", ibanFrom),
                    expectedResponse = expectedStr,
                    actualResponse = actualStr
                )
                ScenarioResult(
                    bic = bank.bic,
                    bankName = bank.name,
                    scenario = "INTRA_BANK_TRANSFER",
                    passed = false,
                    message = mismatchReason,
                    expected = expectedStr,
                    actual = actualStr
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Intra-bank transfer scenario failed for ${bank.name}: $err")
            bankRegistry.updateStatus(Bic(bank.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bank.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bank.webhookUrl,
                scenario = "INTRA_BANK_TRANSFER",
                message = "Exception during test: $err",
                httpRequest = formatTransferInHttp(bank.webhookUrl, "Intra-Bank Transfer", TransferRequest(fromIban = ibanFrom, toIban = ibanTo, amountCents = transferAmountCents)),
                expectedResponse = "HTTP 200 OK",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bank.bic,
                bankName = bank.name,
                scenario = "INTRA_BANK_TRANSFER",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    // Phase 3: Inter-bank transfer (cross-bank over SWIFT Hub)
    suspend fun runCrossBankTransferScenario(): List<ScenarioResult> = coroutineScope {
        val banks = bankRegistry.getAllBanks()
        if (banks.isEmpty()) return@coroutineScope emptyList<ScenarioResult>()

        banks.map { bankA ->
            async(bankDispatcher) {
                runCrossBankTransferForBank(bankA, banks)
            }
        }.awaitAll()
    }

    private suspend fun runCrossBankTransferForBank(bankA: BankNodeInfo, banks: List<BankNodeInfo>): ScenarioResult {
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

        return try {
            // 1. Deposit 50 EUR to Bank A
            val depositResponse: HttpResponse = httpClient.post("${bankA.webhookUrl}/api/deposit") {
                contentType(ContentType.Application.Json)
                setBody(DepositRequest(iban = ibanFrom, amountCents = initialDepositCents))
            }

            if (depositResponse.status != HttpStatusCode.OK) {
                checkResponseTunnelStatus(Bic(bankA.bic), depositResponse.status)
                bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                val failureMsg = "Initial deposit to sender IBAN at ${bankA.name} failed: ${depositResponse.status.value}"
                sendDebugFailure(
                    webhookUrl = bankA.webhookUrl,
                    scenario = "CROSS_BANK_TRANSFER",
                    message = failureMsg,
                    httpRequest = formatDepositHttp(bankA.webhookUrl, "Deposit 50.00 EUR to Bank A Sender Account", ibanFrom, initialDepositCents),
                    expectedResponse = "HTTP 200 OK\n{\n  \"iban\": \"$ibanFrom\",\n  \"balanceCents\": $initialDepositCents\n}",
                    actualResponse = "HTTP ${depositResponse.status.value}"
                )
                return ScenarioResult(
                    bic = bankA.bic,
                    bankName = bankA.name,
                    scenario = "CROSS_BANK_TRANSFER",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Deposit HTTP ${depositResponse.status.value}"
                )
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
                val failureMsg = "Cross-bank transfer routing failed: Debit status=${withdrawSenderResponse.status.value}, Router status=${routerResult.status} (${routerResult.message})"
                val httpReq = if (withdrawSenderResponse.status != HttpStatusCode.OK) {
                    formatWithdrawHttp(bankA.webhookUrl, "Debit Bank A for Outgoing Cross-Bank Transfer", ibanFrom, transferAmountCents)
                } else {
                    formatTransferInHttp(bankB.webhookUrl, "Incoming SWIFT Transfer to Bank B", transferRequest)
                }
                sendDebugFailure(
                    webhookUrl = if (withdrawSenderResponse.status != HttpStatusCode.OK) bankA.webhookUrl else bankB.webhookUrl,
                    scenario = "CROSS_BANK_TRANSFER",
                    message = failureMsg,
                    httpRequest = httpReq,
                    expectedResponse = "HTTP 200 OK (ACCEPTED)",
                    actualResponse = "Debit status: HTTP ${withdrawSenderResponse.status.value}, Router status: ${routerResult.status} (${routerResult.message})"
                )
                return ScenarioResult(
                    bic = bankA.bic,
                    bankName = bankA.name,
                    scenario = "CROSS_BANK_TRANSFER",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = "Actual: Debit HTTP ${withdrawSenderResponse.status.value}, SWIFT Router: ${routerResult.status}"
                )
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
                ScenarioResult(
                    bic = bankA.bic,
                    bankName = bankA.name,
                    scenario = "CROSS_BANK_TRANSFER",
                    passed = true,
                    message = "Cross-bank transfer over SWIFT Hub successfully settled (${bankA.bic} sender balance: 30.00 EUR, ${bankB.bic} receiver balance: 20.00 EUR)",
                    expected = expectedStr,
                    actual = actualStr
                )
            } else {
                bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
                val failureMsg = "Cross-bank transfer verification failed: FromBalance=${stmtFrom?.currentBalanceCents} (expected ${ledgerFrom.expectedBalanceCents}), ToBalance=${stmtTo?.currentBalanceCents} (expected ${ledgerTo.expectedBalanceCents})"
                if (!fromOk) {
                    sendDebugFailure(
                        webhookUrl = bankA.webhookUrl,
                        scenario = "CROSS_BANK_TRANSFER",
                        message = failureMsg,
                        httpRequest = formatStatementHttp(bankA.webhookUrl, "Query Statement for Bank A Sender", ibanFrom),
                        expectedResponse = "Balance ${ledgerFrom.expectedBalanceCents} cents",
                        actualResponse = "Balance ${stmtFrom?.currentBalanceCents} cents"
                    )
                }
                if (!toOk) {
                    sendDebugFailure(
                        webhookUrl = bankB.webhookUrl,
                        scenario = "CROSS_BANK_TRANSFER",
                        message = failureMsg,
                        httpRequest = formatStatementHttp(bankB.webhookUrl, "Query Statement for Bank B Receiver", ibanTo),
                        expectedResponse = "Balance ${ledgerTo.expectedBalanceCents} cents",
                        actualResponse = "Balance ${stmtTo?.currentBalanceCents} cents"
                    )
                }
                ScenarioResult(
                    bic = bankA.bic,
                    bankName = bankA.name,
                    scenario = "CROSS_BANK_TRANSFER",
                    passed = false,
                    message = failureMsg,
                    expected = expectedStr,
                    actual = actualStr
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "Connection failed"
            logger.warn("Cross-bank transfer scenario failed for ${bankA.name}: $err")
            bankRegistry.updateStatus(Bic(bankA.bic), "DOWN", null, "Unreachable: ${err.take(50)}")
            bankRegistry.recordTransaction(Bic(bankA.bic), false, 0L)
            sendDebugFailure(
                webhookUrl = bankA.webhookUrl,
                scenario = "CROSS_BANK_TRANSFER",
                message = "Exception during test: $err",
                httpRequest = formatWithdrawHttp(bankA.webhookUrl, "Debit Bank A Sender", ibanFrom, transferAmountCents),
                expectedResponse = "HTTP 200 OK",
                actualResponse = "Exception: $err"
            )
            ScenarioResult(
                bic = bankA.bic,
                bankName = bankA.name,
                scenario = "CROSS_BANK_TRANSFER",
                passed = false,
                message = "Exception during test: $err",
                expected = expectedStr,
                actual = "Actual: Exception: $err"
            )
        }
    }

    suspend fun runDepositWithdrawScenario(): List<ScenarioResult> {
        return runPositiveWithdrawScenario()
    }

    suspend fun runAllScenarios(): List<ScenarioResult> = coroutineScope {
        val depDeferred = async(bankDispatcher) { runDepositsPhase() }
        val withDeferred = async(bankDispatcher) { runWithdrawalsPhase() }
        val transDeferred = async(bankDispatcher) { runTransfersPhase() }
        depDeferred.await() + withDeferred.await() + transDeferred.await()
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
