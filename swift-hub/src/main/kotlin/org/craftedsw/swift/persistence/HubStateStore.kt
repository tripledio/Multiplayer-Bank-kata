package org.craftedsw.swift.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.craftedsw.contracts.BankNodeInfo
import org.craftedsw.contracts.LedgerEntry
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.simulator.TrafficSimulator

@Serializable
data class HubState(
    val banks: List<BankNodeInfo> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val simulatorRunning: Boolean = false,
    val simulatorIntervalMs: Long = 2000
)

class HubStateStore(private val file: Path) {

    private val json = Json { ignoreUnknownKeys = true }

    fun location(): Path = file.toAbsolutePath()

    fun load(): HubState? {
        if (!Files.exists(file)) return null
        return json.decodeFromString(HubState.serializer(), Files.readString(file))
    }

    fun save(state: HubState) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(HubState.serializer(), state))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Moves the existing state aside so a fresh session starts without destroying the old one. */
    fun archive(): Path? {
        if (!Files.exists(file)) return null
        val backup = file.resolveSibling("${file.fileName}.${System.currentTimeMillis()}.bak")
        Files.move(file, backup)
        return backup
    }

    fun snapshot(bankRegistry: BankRegistry, auditLedger: AuditLedger, trafficSimulator: TrafficSimulator) = HubState(
        banks = bankRegistry.getAllBanks(),
        ledger = auditLedger.getEntries(),
        simulatorRunning = trafficSimulator.isSimulatorRunning(),
        simulatorIntervalMs = trafficSimulator.getIntervalMs()
    )

    fun restoreInto(state: HubState, bankRegistry: BankRegistry, auditLedger: AuditLedger, trafficSimulator: TrafficSimulator) {
        bankRegistry.restore(state.banks)
        auditLedger.restore(state.ledger)
        if (state.simulatorRunning) trafficSimulator.start(state.simulatorIntervalMs)
    }
}
