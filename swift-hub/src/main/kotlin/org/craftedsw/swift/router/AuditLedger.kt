package org.craftedsw.swift.router

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.craftedsw.contracts.BankGraph
import org.craftedsw.contracts.BankGraphEdge
import org.craftedsw.contracts.BankGraphNode
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.Iban
import org.craftedsw.contracts.LedgerEntry
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus

class AuditLedger {

    private val entries = CopyOnWriteArrayList<LedgerEntry>()
    private val totalSettledVolume = AtomicLong(0)
    private val totalSuccessfulTransactions = AtomicLong(0)
    private val totalFailedTransactions = AtomicLong(0)

    fun record(transfer: TransferRequest, result: TransferResult): LedgerEntry {
        val entry = LedgerEntry(
            transactionId = transfer.transactionId.ifBlank { "tx-${System.currentTimeMillis()}" },
            fromIban = transfer.fromIban,
            toIban = transfer.toIban,
            amountCents = transfer.amountCents,
            status = result.status,
            timestamp = System.currentTimeMillis(),
            message = result.message
        )
        entries.add(entry)

        if (result.status == TransferStatus.ACCEPTED) {
            totalSettledVolume.addAndGet(transfer.amountCents)
            totalSuccessfulTransactions.incrementAndGet()
        } else {
            totalFailedTransactions.incrementAndGet()
        }
        return entry
    }

    fun getEntries(): List<LedgerEntry> = entries.toList()

    fun getRecentEntries(limit: Int = 20): List<LedgerEntry> =
        entries.takeLast(limit).reversed()

    fun getTotalSettledVolumeCents(): Long = totalSettledVolume.get()

    fun getTotalSuccessfulCount(): Long = totalSuccessfulTransactions.get()

    fun getTotalFailedCount(): Long = totalFailedTransactions.get()

    fun getTotalTransactionsCount(): Long = entries.size.toLong()

    fun verifyConservationOfMoney(): Boolean {
        // Every accepted transfer debit must balance its credit
        val successful = entries.filter { it.status == TransferStatus.ACCEPTED }
        val sumDebits = successful.sumOf { it.amountCents }
        val sumCredits = successful.sumOf { it.amountCents }
        return sumDebits == sumCredits
    }

    fun getGraph(bankRegistry: BankRegistry): BankGraph {
        val allBanks = bankRegistry.getAllBanks()
        val nodeMap = mutableMapOf<String, BankGraphNode>()

        allBanks.forEach { bank ->
            nodeMap[bank.bic] = BankGraphNode(
                bic = bank.bic,
                name = bank.name,
                status = bank.status,
                totalMoneyCents = bank.totalMoneyCents,
                score = bank.score,
                pingLatencyMs = bank.pingLatencyMs,
                statusMessage = bank.statusMessage
            )
        }

        val edgeMap = mutableMapOf<Pair<String, String>, EdgeAccumulator>()

        for (entry in entries) {
            val srcBic = resolveBic(entry.fromIban, bankRegistry)
            val dstBic = resolveBic(entry.toIban, bankRegistry)

            if (!nodeMap.containsKey(srcBic)) {
                val bank = bankRegistry.getBank(Bic(srcBic))
                nodeMap[srcBic] = BankGraphNode(
                    bic = srcBic,
                    name = bank?.name ?: srcBic,
                    status = bank?.status ?: "UNKNOWN",
                    totalMoneyCents = bank?.totalMoneyCents ?: 0L,
                    score = bank?.score ?: 0L,
                    pingLatencyMs = bank?.pingLatencyMs,
                    statusMessage = bank?.statusMessage
                )
            }
            if (!nodeMap.containsKey(dstBic)) {
                val bank = bankRegistry.getBank(Bic(dstBic))
                nodeMap[dstBic] = BankGraphNode(
                    bic = dstBic,
                    name = bank?.name ?: dstBic,
                    status = bank?.status ?: "UNKNOWN",
                    totalMoneyCents = bank?.totalMoneyCents ?: 0L,
                    score = bank?.score ?: 0L,
                    pingLatencyMs = bank?.pingLatencyMs,
                    statusMessage = bank?.statusMessage
                )
            }

            val key = Pair(srcBic, dstBic)
            val accumulator = edgeMap.getOrPut(key) {
                val srcNode = nodeMap[srcBic]
                val dstNode = nodeMap[dstBic]
                EdgeAccumulator(
                    sourceBic = srcBic,
                    targetBic = dstBic,
                    sourceName = srcNode?.name ?: srcBic,
                    targetName = dstNode?.name ?: dstBic
                )
            }

            accumulator.transactionCount++
            if (entry.status == TransferStatus.ACCEPTED) {
                accumulator.totalAmountCents += entry.amountCents
                accumulator.successfulCount++
            }
        }

        return BankGraph(
            nodes = nodeMap.values.toList(),
            edges = edgeMap.values.map { it.toEdge() }
        )
    }

    private fun resolveBic(ibanOrBic: String, bankRegistry: BankRegistry): String {
        val clean = ibanOrBic.trim()
        if (clean.isBlank()) return "UNKNOWN"

        // 1. If valid IBAN, extract BIC
        if (clean.length >= 12) {
            val extracted = try {
                Iban(clean).extractBic()
            } catch (e: Exception) {
                null
            }
            if (extracted != null) {
                val matched = bankRegistry.getBank(extracted)
                if (matched != null) return matched.bic
                return extracted.value
            }
        }

        // 2. Direct lookup as BIC
        val directMatch = bankRegistry.getBank(Bic(if (clean.length <= 8) clean.padEnd(8, 'X') else clean.substring(0, 8)))
        if (directMatch != null) return directMatch.bic

        // 3. Substring match on any registered bank
        val fuzzyMatch = bankRegistry.getAllBanks().firstOrNull { bank ->
            clean.contains(bank.bic) || bank.bic.startsWith(clean.take(4))
        }
        if (fuzzyMatch != null) return fuzzyMatch.bic

        return if (clean.length >= 8) clean.take(8) else clean
    }

    fun restore(restored: List<LedgerEntry>) {
        clear()
        entries.addAll(restored)
        restored.forEach { entry ->
            if (entry.status == TransferStatus.ACCEPTED) {
                totalSettledVolume.addAndGet(entry.amountCents)
                totalSuccessfulTransactions.incrementAndGet()
            } else {
                totalFailedTransactions.incrementAndGet()
            }
        }
    }

    fun clear() {
        entries.clear()
        totalSettledVolume.set(0)
        totalSuccessfulTransactions.set(0)
        totalFailedTransactions.set(0)
    }

    private data class EdgeAccumulator(
        val sourceBic: String,
        val targetBic: String,
        var sourceName: String = "",
        var targetName: String = "",
        var totalAmountCents: Long = 0,
        var transactionCount: Long = 0,
        var successfulCount: Long = 0
    ) {
        fun toEdge(): BankGraphEdge = BankGraphEdge(
            sourceBic = sourceBic,
            targetBic = targetBic,
            sourceName = sourceName,
            targetName = targetName,
            totalAmountCents = totalAmountCents,
            transactionCount = transactionCount,
            successfulCount = successfulCount
        )
    }
}
