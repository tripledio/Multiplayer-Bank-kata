package org.craftedsw.swift.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.RegisterBankRequest
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.craftedsw.swift.simulator.TrafficSimulator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HubStateStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should restore banks, scores and ledger after a restart`() {
        val store = HubStateStore(tempDir.resolve("state.json"))
        val registry = BankRegistry()
        val ledger = AuditLedger()
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Bank Alpha", webhookUrl = "http://alpha"))
        registry.recordTransaction(Bic("BANKAXXX"), isSuccess = true, pointsAwarded = 15, amountCents = 5000)
        ledger.record(
            TransferRequest(transactionId = "tx-1", fromIban = "A", toIban = "B", amountCents = 5000),
            TransferResult("tx-1", TransferStatus.ACCEPTED, "ok")
        )
        ledger.record(
            TransferRequest(transactionId = "tx-2", fromIban = "A", toIban = "B", amountCents = 700),
            TransferResult("tx-2", TransferStatus.FAILED, "down")
        )
        store.save(store.snapshot(registry, ledger, TrafficSimulator(registry, TransferRouter(registry, ledger))))

        val restoredRegistry = BankRegistry()
        val restoredLedger = AuditLedger()
        val simulator = TrafficSimulator(restoredRegistry, TransferRouter(restoredRegistry, restoredLedger))
        store.restoreInto(store.load()!!, restoredRegistry, restoredLedger, simulator)

        assertThat(restoredRegistry.getAllBanks()).isEqualTo(registry.getAllBanks())
        assertThat(restoredLedger.getEntries()).isEqualTo(ledger.getEntries())
        assertThat(restoredLedger.getTotalSettledVolumeCents()).isEqualTo(5000)
        assertThat(restoredLedger.getTotalSuccessfulCount()).isEqualTo(1)
        assertThat(restoredLedger.getTotalFailedCount()).isEqualTo(1)
        assertThat(simulator.isSimulatorRunning()).isFalse()
    }

    @Test
    fun `should archive previous state when starting a new session`() {
        val file = tempDir.resolve("state.json")
        val store = HubStateStore(file)
        store.save(HubState())

        val backup = store.archive()

        assertThat(store.load()).isNull()
        assertThat(backup).isNotNull()
        assertThat(Files.exists(backup!!)).isTrue()
    }
}
