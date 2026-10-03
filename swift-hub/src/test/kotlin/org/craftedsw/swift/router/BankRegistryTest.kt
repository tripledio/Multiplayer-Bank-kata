package org.craftedsw.swift.router

import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.Bic
import org.craftedsw.contracts.RegisterBankRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BankRegistryTest {

    private lateinit var registry: BankRegistry

    @BeforeEach
    fun setUp() {
        registry = BankRegistry()
    }

    @Test
    fun `should register bank and normalize BIC and URL`() {
        val request = RegisterBankRequest(
            bic = "bankaxxx",
            name = "Bank Alpha",
            webhookUrl = "https://alpha.loca.lt/"
        )

        val node = registry.register(request)

        assertThat(node.bic).isEqualTo("BANKAXXX")
        assertThat(node.webhookUrl).isEqualTo("https://alpha.loca.lt")
        assertThat(node.status).isEqualTo("HEALTHY")

        val retrieved = registry.getBank(Bic("BANKAXXX"))
        assertThat(retrieved).isNotNull
        assertThat(retrieved?.name).isEqualTo("Bank Alpha")
    }

    @Test
    fun `should support flexible BIC lookup with XXX and 000 suffixes`() {
        registry.register(RegisterBankRequest(bic = "BANKBXXX", name = "Bank Beta", webhookUrl = "http://localhost:8081"))

        // Exact match
        assertThat(registry.getBank(Bic("BANKBXXX"))?.name).isEqualTo("Bank Beta")

        // Match with 000 suffix from IBANs like BE68BANKB0001234567
        assertThat(registry.getBank(Bic("BANKB000"))?.name).isEqualTo("Bank Beta")

        // Match with base prefix
        assertThat(registry.getBank(Bic("BANKB"))?.name).isEqualTo("Bank Beta")
    }

    @Test
    fun `should update scores and transaction counts and total money`() {
        val bic = Bic("BANKAXXX")
        registry.register(RegisterBankRequest(bic = "BANKAXXX", name = "Alpha", webhookUrl = "http://localhost:8080"))

        registry.recordTransaction(bic, isSuccess = true, pointsAwarded = 15, amountCents = 25000)
        registry.recordTransaction(bic, isSuccess = false, pointsAwarded = -5, amountCents = 10000)

        val node = registry.getBank(bic)!!
        assertThat(node.totalTransactions).isEqualTo(2)
        assertThat(node.successfulTransactions).isEqualTo(1)
        assertThat(node.failedTransactions).isEqualTo(1)
        assertThat(node.score).isEqualTo(10)
        assertThat(node.totalMoneyCents).isEqualTo(25000)
    }

    @Test
    fun `should generate 8-character ASCII BIC when bic is omitted or blank`() {
        val node1 = registry.register(RegisterBankRequest(name = "Bank Alpha", webhookUrl = "https://alpha.loca.lt"))
        val node2 = registry.register(RegisterBankRequest(name = "Bank Alpha", webhookUrl = "https://alpha2.loca.lt"))
        val node3 = registry.register(RegisterBankRequest(name = "Tripled Banking", webhookUrl = "https://tripled.loca.lt"))

        assertThat(node1.bic).hasSize(8)
        assertThat(node1.bic).matches("^[A-Z0-9]{8}$")
        assertThat(node1.bic).isEqualTo("BANKXXXX")

        assertThat(node2.bic).hasSize(8)
        assertThat(node2.bic).matches("^[A-Z0-9]{8}$")
        assertThat(node2.bic).isNotEqualTo(node1.bic)

        assertThat(node3.bic).hasSize(8)
        assertThat(node3.bic).matches("^[A-Z0-9]{8}$")
        assertThat(node3.bic).startsWith("TRIP")

        assertThat(registry.getAllBanks()).hasSize(3)
        assertThat(registry.getBank(Bic(node1.bic))).isNotNull
        assertThat(registry.getBank(Bic(node2.bic))).isNotNull
        assertThat(registry.getBank(Bic(node3.bic))).isNotNull
    }
}
