package org.craftedsw.swift.router

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
}
