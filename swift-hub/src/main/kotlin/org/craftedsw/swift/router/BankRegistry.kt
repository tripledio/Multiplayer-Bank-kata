package org.craftedsw.swift.router

import java.util.concurrent.ConcurrentHashMap
import org.craftedsw.contracts.BankNodeInfo
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.RegisterBankRequest

class BankRegistry {

    private val registry: ConcurrentHashMap<String, BankNodeInfo> = ConcurrentHashMap()

    fun register(request: RegisterBankRequest): BankNodeInfo {
        val bic = if (request.bic.isNotBlank()) {
            val normalized = request.bic.uppercase().trim()
            Bic(if (normalized.length <= 8) normalized.padEnd(8, 'X') else normalized.substring(0, 8))
        } else {
            generateBic(request.name)
        }
        val node = BankNodeInfo(
            bic = bic.value,
            name = request.name.trim(),
            webhookUrl = request.webhookUrl.trimEnd('/'),
            status = "HEALTHY",
            lastSeenTimestamp = System.currentTimeMillis()
        )
        registry[bic.value] = node
        return node
    }

    fun generateBic(name: String): Bic {
        val cleanLetters = name.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()
        val base = when {
            cleanLetters.length >= 4 -> cleanLetters.take(4)
            cleanLetters.isNotEmpty() -> cleanLetters.padEnd(4, 'X')
            else -> "BANK"
        }
        var candidate = "${base}XXX".take(8).padEnd(8, 'X')
        var attempt = 1
        while (registry.containsKey(candidate) || candidate.length != 8) {
            val chars = ('A'..'Z') + ('0'..'9')
            val randomSuffix = (1..4).map { chars.random() }.joinToString("")
            candidate = "${base.take(4)}$randomSuffix"
            attempt++
            if (attempt > 100) {
                candidate = (1..8).map { ('A'..'Z').random() }.joinToString("")
            }
        }
        return Bic(candidate)
    }

    fun getBank(bic: Bic): BankNodeInfo? {
        val normalized = bic.value.uppercase().trim()
        val exact = registry[normalized]
        if (exact != null) return exact

        // Flexible lookup: match base bank identifier without trailing 'X' or '0'
        val baseCode = normalized.trimEnd('X', '0')
        if (baseCode.length >= 3) {
            val match = registry.values.firstOrNull { it.bic.startsWith(baseCode) }
            if (match != null) return match
        }
        return null
    }

    fun getAllBanks(): List<BankNodeInfo> {
        return registry.values.toList()
    }

    fun updateStatus(bic: Bic, status: String) {
        val targetBank = getBank(bic) ?: return
        registry.computeIfPresent(targetBank.bic) { _, current ->
            current.copy(status = status, lastSeenTimestamp = System.currentTimeMillis())
        }
    }

    fun recordTransaction(bic: Bic, isSuccess: Boolean, pointsAwarded: Long, amountCents: Long = 0L) {
        val targetBank = getBank(bic) ?: return
        registry.computeIfPresent(targetBank.bic) { _, current ->
            current.copy(
                totalTransactions = current.totalTransactions + 1,
                successfulTransactions = if (isSuccess) current.successfulTransactions + 1 else current.successfulTransactions,
                failedTransactions = if (!isSuccess) current.failedTransactions + 1 else current.failedTransactions,
                score = (current.score + pointsAwarded).coerceAtLeast(0),
                totalMoneyCents = if (isSuccess) current.totalMoneyCents + amountCents else current.totalMoneyCents,
                status = if (isSuccess) "HEALTHY" else if (current.failedTransactions > 3) "DEGRADED" else current.status,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun clear() {
        registry.clear()
    }
}
