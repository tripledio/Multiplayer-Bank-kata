package org.craftedsw.bank.service

import java.util.Date
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.WithdrawRequest

class BankService {

    fun deposit(request: DepositRequest, date: Date = Date()): Result<Long> {
        TODO("Not yet implemented")
    }

    fun withdraw(request: WithdrawRequest, date: Date = Date()): Result<Long> {
        TODO("Not yet implemented")
    }

    fun processIncomingTransfer(transfer: TransferRequest): TransferResult {
        TODO("Not yet implemented")
    }

    fun getStatement(ibanStr: String): StatementResponse {
        TODO("Not yet implemented")
    }

    fun getBalanceCents(ibanStr: String): Long {
        TODO("Not yet implemented")
    }
}
