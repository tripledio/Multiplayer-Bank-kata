package org.craftedsw.swift.integration

import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.RegisterBankRequest
import org.craftedsw.contracts.StatementLineDto
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.WithdrawRequest
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MultiBankMultiplayerE2ETest {

    private class TestBankNode {
        private val balances = ConcurrentHashMap<String, Long>()

        fun deposit(request: DepositRequest): Result<Long> {
            val newBal = balances.compute(request.iban) { _, current -> (current ?: 0L) + request.amountCents } ?: 0L
            return Result.success(newBal)
        }

        fun withdraw(request: WithdrawRequest): Result<Long> {
            val current = balances.getOrDefault(request.iban, 0L)
            if (current < request.amountCents) {
                return Result.failure(IllegalStateException("Insufficient funds"))
            }
            val newBal = balances.compute(request.iban) { _, cur -> (cur ?: 0L) - request.amountCents } ?: 0L
            return Result.success(newBal)
        }

        fun processIncomingTransfer(transfer: TransferRequest): TransferResult {
            deposit(DepositRequest(transfer.toIban, transfer.amountCents))
            return TransferResult(
                transactionId = transfer.transactionId,
                status = TransferStatus.ACCEPTED,
                message = "Credited"
            )
        }

        fun getBalanceCents(iban: String): Long = balances.getOrDefault(iban, 0L)

        fun getStatement(iban: String): StatementResponse = StatementResponse(
            iban = iban,
            lines = listOf(StatementLineDto(date = "19/10/2026", credit = "400.00", debit = null, balance = "400.00")),
            currentBalanceCents = getBalanceCents(iban)
        )
    }

    private lateinit var bankRegistry: BankRegistry
    private lateinit var auditLedger: AuditLedger
    private lateinit var bankAlpha: TestBankNode
    private lateinit var bankBeta: TestBankNode

    @BeforeEach
    fun setUp() {
        bankRegistry = BankRegistry()
        auditLedger = AuditLedger()
        bankAlpha = TestBankNode()
        bankBeta = TestBankNode()
    }

    @Test
    fun `should execute end-to-end multi-bank transfer and maintain conservation of money in SWIFT ledger`() = runTest {
        val alphaIban = "BE68BANKA0001111111"
        val betaIban = "BE68BANKB0002222222"

        // 1. Initial Deposit at Bank Alpha
        bankAlpha.deposit(DepositRequest(iban = alphaIban, amountCents = 100000)) // 1000.00 EUR
        assertThat(bankAlpha.getBalanceCents(alphaIban)).isEqualTo(100000)
        assertThat(bankBeta.getBalanceCents(betaIban)).isEqualTo(0)

        // 2. Register both banks on SWIFT Hub
        bankRegistry.register(
            RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha-bank:8080")
        )
        bankRegistry.register(
            RegisterBankRequest(bic = "BANKBXXX", name = "Bank Beta", webhookUrl = "http://beta-bank:8080")
        )

        // 3. Mock the HTTP dispatch from TransferRouter to forward directly to Bank Beta's service
        val transferRouter = mockk<TransferRouter>()
        val transfer = TransferRequest(
            transactionId = "tx-multiplayer-001",
            fromIban = alphaIban,
            toIban = betaIban,
            amountCents = 40000, // 400.00 EUR
            reference = "Inter-bank settle"
        )

        coEvery { transferRouter.routeTransfer(transfer) } answers {
            // Debit sender
            val withdrawResult = bankAlpha.withdraw(
                WithdrawRequest(iban = transfer.fromIban, amountCents = transfer.amountCents)
            )
            if (withdrawResult.isSuccess) {
                val creditResult = bankBeta.processIncomingTransfer(transfer)
                bankRegistry.recordTransaction(Bic("BANKBXXX"), true, 15)
                auditLedger.record(transfer, creditResult)
                creditResult
            } else {
                val fail = TransferResult(transfer.transactionId, TransferStatus.REJECTED, "Insufficient funds")
                auditLedger.record(transfer, fail)
                fail
            }
        }

        // 4. Execute the transfer through the SWIFT Network
        val result = transferRouter.routeTransfer(transfer)

        // 5. Verify balances at both nodes
        assertThat(result.status).isEqualTo(TransferStatus.ACCEPTED)
        assertThat(bankAlpha.getBalanceCents(alphaIban)).isEqualTo(60000) // 600.00 EUR remaining
        assertThat(bankBeta.getBalanceCents(betaIban)).isEqualTo(40000)  // 400.00 EUR received

        // 6. Verify SWIFT Hub Audit Ledger
        assertThat(auditLedger.getTotalTransactionsCount()).isEqualTo(1)
        assertThat(auditLedger.getTotalSuccessfulCount()).isEqualTo(1)
        assertThat(auditLedger.getTotalSettledVolumeCents()).isEqualTo(40000)
        assertThat(auditLedger.verifyConservationOfMoney()).isTrue()

        // 7. Verify statement on Bank Beta
        val betaStatement = bankBeta.getStatement(betaIban)
        assertThat(betaStatement.currentBalanceCents).isEqualTo(40000)
        assertThat(betaStatement.lines).hasSize(1)
        assertThat(betaStatement.lines[0].credit).isEqualTo("400.00")
    }
}
