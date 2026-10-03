package org.craftedsw.contracts

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Bic(val value: String) {
    init {
        require(value.isNotBlank()) { "BIC must not be blank" }
    }
}

@Serializable
@JvmInline
value class Iban(val value: String) {
    init {
        require(value.isNotBlank()) { "IBAN must not be blank" }
    }

    fun extractBic(): Bic? {
        // Standard workshop IBAN format: CCXXBBBBBBBAAAAAAAAAA (e.g. BE68BANKA0001234567 -> BANKAXXX or 8 chars)
        return if (value.length >= 12) {
            val potentialBic = value.substring(4, 12).padEnd(8, 'X')
            Bic(potentialBic)
        } else {
            null
        }
    }
}

@Serializable
enum class TransferStatus {
    ACCEPTED,
    REJECTED,
    FAILED,
    TIMEOUT
}

@Serializable
data class RegisterBankRequest(
    val bic: String = "",
    val name: String,
    val webhookUrl: String
)

@Serializable
data class RegisterBankResponse(
    val status: String,
    val bic: String,
    val message: String
)

@Serializable
data class TransferRequest(
    val transactionId: String = "",
    val fromIban: String,
    val toIban: String,
    val amountCents: Long,
    val timestamp: String = "",
    val reference: String = ""
)

@Serializable
data class TransferResult(
    val transactionId: String,
    val status: TransferStatus,
    val message: String = ""
)

@Serializable
data class DepositRequest(
    val iban: String,
    val amountCents: Long
)

@Serializable
data class WithdrawRequest(
    val iban: String,
    val amountCents: Long
)

@Serializable
data class BalanceResponse(
    val iban: String,
    val balanceCents: Long
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val bic: String
)

@Serializable
data class StatementLineDto(
    val date: String,
    val credit: String? = null,
    val debit: String? = null,
    val balance: String
)

@Serializable
data class StatementResponse(
    val iban: String,
    val lines: List<StatementLineDto>,
    val currentBalanceCents: Long
)

@Serializable
data class BankNodeInfo(
    val bic: String,
    val name: String,
    val webhookUrl: String,
    val status: String,
    val totalTransactions: Long = 0,
    val successfulTransactions: Long = 0,
    val failedTransactions: Long = 0,
    val score: Long = 0,
    val totalMoneyCents: Long = 0,
    val lastSeenTimestamp: Long = 0
)

@Serializable
data class NetworkMetrics(
    val activeBanksCount: Int,
    val totalVolumeCents: Long,
    val totalTransactions: Long,
    val throughputTps: Double,
    val errorRatePercentage: Double
)

@Serializable
data class ScoreboardState(
    val banks: List<BankNodeInfo>,
    val metrics: NetworkMetrics,
    val recentTransfers: List<TransferRequest> = emptyList()
)

@Serializable
data class LedgerEntry(
    val transactionId: String,
    val fromIban: String,
    val toIban: String,
    val amountCents: Long,
    val status: TransferStatus,
    val timestamp: Long,
    val message: String
)

@Serializable
data class LedgerResponse(
    val totalSettledVolumeCents: Long,
    val totalSuccessfulTransactions: Long,
    val totalFailedTransactions: Long,
    val isConservationOfMoneyVerified: Boolean,
    val recentEntries: List<LedgerEntry>
)

@Serializable
data class SimulatorStatusResponse(
    val status: String,
    val intervalMs: Long? = null,
    val count: Int? = null
)
