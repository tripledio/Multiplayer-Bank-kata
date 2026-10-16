package org.craftedsw.swift.router

import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuditLedgerTest {

    private lateinit var auditLedger: AuditLedger

    @BeforeEach
    fun setUp() {
        auditLedger = AuditLedger()
    }

    @Test
    fun `should record transactions and update volume and count`() {
        val transfer1 = TransferRequest(
            transactionId = "tx-1",
            fromIban = "BE68BANKA0001234567",
            toIban = "BE68BANKB0007654321",
            amountCents = 50000
        )
        val result1 = TransferResult("tx-1", TransferStatus.ACCEPTED, "OK")

        val transfer2 = TransferRequest(
            transactionId = "tx-2",
            fromIban = "BE68BANKB0007654321",
            toIban = "BE68BANKA0001234567",
            amountCents = 20000
        )
        val result2 = TransferResult("tx-2", TransferStatus.FAILED, "Timeout")

        auditLedger.record(transfer1, result1)
        auditLedger.record(transfer2, result2)

        assertThat(auditLedger.getTotalTransactionsCount()).isEqualTo(2)
        assertThat(auditLedger.getTotalSuccessfulCount()).isEqualTo(1)
        assertThat(auditLedger.getTotalFailedCount()).isEqualTo(1)
        assertThat(auditLedger.getTotalSettledVolumeCents()).isEqualTo(50000)
        assertThat(auditLedger.verifyConservationOfMoney()).isTrue()
    }

    @Test
    fun `should build bank graph with nodes and transfer edges and amounts`() {
        val registry = BankRegistry()
        registry.register(org.craftedsw.contracts.RegisterBankRequest("BANKAXXX", "Bank Alpha", "http://alpha:8080"))
        registry.register(org.craftedsw.contracts.RegisterBankRequest("BANKBXXX", "Bank Beta", "http://beta:8080"))

        val transfer1 = TransferRequest("tx-1", "BE68BANKA0001234567", "BE68BANKB0007654321", 5000)
        val transfer2 = TransferRequest("tx-2", "BE68BANKA0001234567", "BE68BANKB0007654321", 3000)
        val transfer3 = TransferRequest("tx-3", "BE68BANKB0007654321", "BE68BANKA0001234567", 2000)
        val transfer4 = TransferRequest("tx-4", "BE68BANKA0001234567", "BE68BANKB0007654321", 1000)

        auditLedger.record(transfer1, TransferResult("tx-1", TransferStatus.ACCEPTED, "OK"))
        auditLedger.record(transfer2, TransferResult("tx-2", TransferStatus.ACCEPTED, "OK"))
        auditLedger.record(transfer3, TransferResult("tx-3", TransferStatus.ACCEPTED, "OK"))
        auditLedger.record(transfer4, TransferResult("tx-4", TransferStatus.REJECTED, "Error"))

        val graph = auditLedger.getGraph(registry)

        assertThat(graph.nodes).hasSize(2)
        val alphaNode = graph.nodes.find { it.bic == "BANKAXXX" }
        assertThat(alphaNode).isNotNull
        assertThat(alphaNode?.name).isEqualTo("Bank Alpha")

        assertThat(graph.edges).hasSize(2)
        val alphaToBeta = graph.edges.find { it.sourceBic == "BANKAXXX" && it.targetBic == "BANKBXXX" }
        assertThat(alphaToBeta).isNotNull
        assertThat(alphaToBeta?.totalAmountCents).isEqualTo(8000L)
        assertThat(alphaToBeta?.transactionCount).isEqualTo(3L)
        assertThat(alphaToBeta?.successfulCount).isEqualTo(2L)

        val betaToAlpha = graph.edges.find { it.sourceBic == "BANKBXXX" && it.targetBic == "BANKAXXX" }
        assertThat(betaToAlpha).isNotNull
        assertThat(betaToAlpha?.totalAmountCents).isEqualTo(2000L)
        assertThat(betaToAlpha?.transactionCount).isEqualTo(1L)
        assertThat(betaToAlpha?.successfulCount).isEqualTo(1L)
    }
}
