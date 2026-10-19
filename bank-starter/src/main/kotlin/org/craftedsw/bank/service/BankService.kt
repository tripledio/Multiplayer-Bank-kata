package org.craftedsw.bank.service

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.craftedsw.bank.domain.Account
import org.craftedsw.bank.domain.Amount.Companion.amountOf
import org.craftedsw.bank.domain.Statement
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.Iban
import org.craftedsw.contracts.StatementLineDto
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.WithdrawRequest

class BankService {

    private val accounts: ConcurrentHashMap<String, Account> = ConcurrentHashMap()
    private val balancesCents: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    fun getOrCreateAccount(iban: Iban): Account {
        return accounts.computeIfAbsent(iban.value) {
            balancesCents.putIfAbsent(iban.value, 0L)
            Account(Statement())
        }
    }

    fun deposit(request: DepositRequest, date: Date = Date()): Result<Long> {
        if (request.amountCents <= 0) {
            return Result.failure(IllegalArgumentException("Deposit amount must be positive"))
        }

        val iban = Iban(request.iban)
        val account = getOrCreateAccount(iban)
        val amount = amountOf((request.amountCents / 100).toInt())
        account.deposit(amount, date)

        val newBalance = balancesCents.compute(iban.value) { _, current -> (current ?: 0L) + request.amountCents } ?: 0L
        return Result.success(newBalance)
    }

    fun withdraw(request: WithdrawRequest, date: Date = Date()): Result<Long> {
        if (request.amountCents <= 0) {
            return Result.failure(IllegalArgumentException("Withdrawal amount must be positive"))
        }

        val iban = Iban(request.iban)
        val currentBalance = balancesCents.getOrDefault(iban.value, 0L)
        if (currentBalance < request.amountCents) {
            return Result.failure(IllegalStateException("Insufficient funds: balance=$currentBalance, required=${request.amountCents}"))
        }

        val account = getOrCreateAccount(iban)
        val amount = amountOf((request.amountCents / 100).toInt())
        account.withdrawal(amount, date)

        val newBalance = balancesCents.compute(iban.value) { _, current -> (current ?: 0L) - request.amountCents } ?: 0L
        return Result.success(newBalance)
    }

    fun processIncomingTransfer(transfer: TransferRequest): TransferResult {
        if (transfer.amountCents <= 0) {
            return TransferResult(
                transactionId = transfer.transactionId,
                status = TransferStatus.REJECTED,
                message = "Transfer amount must be positive"
            )
        }

        val depositResult = deposit(
            DepositRequest(iban = transfer.toIban, amountCents = transfer.amountCents),
            parseTimestampOrNow(transfer.timestamp)
        )

        return depositResult.fold(
            onSuccess = {
                TransferResult(
                    transactionId = transfer.transactionId,
                    status = TransferStatus.ACCEPTED,
                    message = "Successfully credited ${transfer.amountCents} cents to ${transfer.toIban}"
                )
            },
            onFailure = { error ->
                TransferResult(
                    transactionId = transfer.transactionId,
                    status = TransferStatus.FAILED,
                    message = error.message ?: "Failed to process transfer"
                )
            }
        )
    }

    fun getStatement(ibanStr: String): StatementResponse {
        val iban = Iban(ibanStr)
        val account = getOrCreateAccount(iban)
        val currentBalance = balancesCents.getOrDefault(iban.value, 0L)

        val baos = ByteArrayOutputStream()
        account.printStatement(PrintStream(baos, true, StandardCharsets.UTF_8.name()))
        val output = baos.toString(StandardCharsets.UTF_8.name())
        val lines = parseStatementLines(output)

        return StatementResponse(
            iban = iban.value,
            lines = lines,
            currentBalanceCents = currentBalance
        )
    }

    fun getBalanceCents(ibanStr: String): Long {
        return balancesCents.getOrDefault(ibanStr, 0L)
    }

    private fun parseTimestampOrNow(timestamp: String): Date {
        return try {
            if (timestamp.isNotBlank()) {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                sdf.parse(timestamp) ?: Date()
            } else {
                Date()
            }
        } catch (_: Exception) {
            Date()
        }
    }

    private fun parseStatementLines(rawOutput: String): List<StatementLineDto> {
        val rawLines = rawOutput.lines().filter { it.isNotBlank() }
        if (rawLines.size <= 1) return emptyList()

        // Skip header line
        return rawLines.drop(1).mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 4) {
                StatementLineDto(
                    date = parts[0],
                    credit = parts[1].ifBlank { null },
                    debit = parts[2].ifBlank { null },
                    balance = parts[3]
                )
            } else {
                null
            }
        }
    }
}
