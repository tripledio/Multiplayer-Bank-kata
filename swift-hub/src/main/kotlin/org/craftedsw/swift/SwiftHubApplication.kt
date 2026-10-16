package org.craftedsw.swift

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.craftedsw.swift.api.swiftHubModule
import org.craftedsw.swift.persistence.HubState
import org.craftedsw.swift.persistence.HubStateStore
import org.craftedsw.swift.router.AuditLedger
import org.craftedsw.swift.router.BankRegistry
import org.craftedsw.swift.router.TransferRouter
import org.craftedsw.swift.simulator.TrafficSimulator
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SwiftHubApplication")

private const val SAVE_INTERVAL_SECONDS = 2L

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9000
    logger.info("==================================================")
    logger.info("🚀 Starting SWIFT Hub & Scoreboard on port $port")
    logger.info("👉 Open Dashboard at: http://localhost:$port")
    logger.info("==================================================")

    val bankRegistry = BankRegistry()
    val auditLedger = AuditLedger()
    val transferRouter = TransferRouter(bankRegistry, auditLedger)
    val trafficSimulator = TrafficSimulator(bankRegistry, transferRouter)

    val stateStore = HubStateStore(Path.of(System.getenv("SWIFT_HUB_STATE_FILE") ?: "swift-hub-state.json"))
    val previous = stateStore.load()
    if (previous != null && (previous.banks.isNotEmpty() || previous.ledger.isNotEmpty())) {
        if (shouldResume(previous)) {
            stateStore.restoreInto(previous, bankRegistry, auditLedger, trafficSimulator)
            logger.info("♻️ Resumed previous session from ${stateStore.location()}")
        } else {
            val backup = stateStore.archive()
            logger.info("🆕 Starting a new session (previous state archived to $backup)")
        }
    }

    val saver = Executors.newSingleThreadScheduledExecutor { Thread(it, "hub-state-saver").apply { isDaemon = true } }
    val saveState = {
        try {
            stateStore.save(stateStore.snapshot(bankRegistry, auditLedger, trafficSimulator))
        } catch (e: Exception) {
            logger.warn("Failed to save hub state to ${stateStore.location()}: ${e.message}")
        }
    }
    saver.scheduleWithFixedDelay(saveState, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(Thread { saveState() })

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        swiftHubModule(
            bankRegistry = bankRegistry,
            auditLedger = auditLedger,
            transferRouter = transferRouter,
            trafficSimulator = trafficSimulator
        )
    }.start(wait = true)
}

/**
 * SWIFT_HUB_RESUME=true|false skips the question. Without an answer (Enter, or no terminal attached)
 * the hub resumes, so an unattended restart never throws away a running workshop.
 */
private fun shouldResume(previous: HubState): Boolean {
    System.getenv("SWIFT_HUB_RESUME")?.let { return it.toBooleanStrictOrNull() ?: true }

    println()
    println("Found a previous session: ${previous.banks.size} bank(s), ${previous.ledger.size} transaction(s).")
    print("Resume it? [Y/n] (n = start a new session): ")
    System.out.flush()
    val answer = readlnOrNull()?.trim()?.lowercase() ?: return true
    return answer !in setOf("n", "no", "new")
}
