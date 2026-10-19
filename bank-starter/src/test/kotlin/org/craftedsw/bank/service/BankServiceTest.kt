package org.craftedsw.bank.service

import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.WithdrawRequest
import org.junit.jupiter.api.Test

class BankServiceTest {

    @Test
    fun `should process deposits and withdrawals and generate statement`() {
        val bankService = BankService()
        val iban = "BE68BANKA0001234567"

        bankService.deposit(DepositRequest(iban = iban, amountCents = 100000))
        bankService.deposit(DepositRequest(iban = iban, amountCents = 200000))

        val statement = bankService.getStatement(iban)

        assertThat(statement.iban).isEqualTo(iban)
        assertThat(statement.currentBalanceCents).isEqualTo(300000)
        assertThat(statement.lines).hasSize(2)
    }
}
