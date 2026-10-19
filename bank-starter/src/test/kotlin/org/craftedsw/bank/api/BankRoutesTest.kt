package org.craftedsw.bank.api

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.bank.service.BankService
import org.craftedsw.contracts.DepositRequest
import org.craftedsw.contracts.StatementResponse
import org.craftedsw.contracts.TransferRequest
import org.craftedsw.contracts.TransferResult
import org.craftedsw.contracts.TransferStatus
import org.craftedsw.contracts.WithdrawRequest
import org.junit.jupiter.api.Test

class BankRoutesTest {

    @Test
    fun `health endpoint should return UP status and BIC`() = testApplication {
        application {
            bankModule(bic = "TESTBEXX")
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.get("/health")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<org.craftedsw.contracts.HealthResponse>()
        assertThat(body.status).isEqualTo("UP")
        assertThat(body.bic).isEqualTo("TESTBEXX")

        val pingResponse = client.get("/ping")
        assertThat(pingResponse.status).isEqualTo(HttpStatusCode.OK)
        val pingBody = pingResponse.body<org.craftedsw.contracts.HealthResponse>()
        assertThat(pingBody.status).isEqualTo("UP")
        assertThat(pingBody.bic).isEqualTo("TESTBEXX")
    }

    @Test
    fun `deposit endpoint should credit balance`() = testApplication {
        val bankService = BankService()
        application {
            bankModule(bankService = bankService)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val response = client.post("/api/deposit") {
            contentType(ContentType.Application.Json)
            setBody(DepositRequest(iban = "BE68BANKA0001234567", amountCents = 100000))
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(bankService.getBalanceCents("BE68BANKA0001234567")).isEqualTo(100000)
    }

    @Test
    fun `withdraw endpoint should debit balance or return 422 if insufficient funds`() = testApplication {
        val bankService = BankService()
        application {
            bankModule(bankService = bankService)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        // Deposit 1000.00 first
        bankService.deposit(DepositRequest(iban = "BE68BANKA0001234567", amountCents = 100000))

        // Withdraw 300.00
        val okResponse = client.post("/api/withdraw") {
            contentType(ContentType.Application.Json)
            setBody(WithdrawRequest(iban = "BE68BANKA0001234567", amountCents = 30000))
        }
        assertThat(okResponse.status).isEqualTo(HttpStatusCode.OK)
        assertThat(bankService.getBalanceCents("BE68BANKA0001234567")).isEqualTo(70000)

        // Try to withdraw 800.00 (more than balance)
        val failResponse = client.post("/api/withdraw") {
            contentType(ContentType.Application.Json)
            setBody(WithdrawRequest(iban = "BE68BANKA0001234567", amountCents = 80000))
        }
        assertThat(failResponse.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun `transfer-in endpoint should credit recipient account`() = testApplication {
        val bankService = BankService()
        application {
            bankModule(bankService = bankService)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val transfer = TransferRequest(
            transactionId = "tx-999",
            fromIban = "BE68BANKB0009999999",
            toIban = "BE68BANKA0001234567",
            amountCents = 250000,
            timestamp = "2026-10-01T10:00:00",
            reference = "Consulting Fee"
        )

        val response = client.post("/api/transfer-in") {
            contentType(ContentType.Application.Json)
            setBody(transfer)
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val result = response.body<TransferResult>()
        assertThat(result.status).isEqualTo(TransferStatus.ACCEPTED)
        assertThat(bankService.getBalanceCents("BE68BANKA0001234567")).isEqualTo(250000)
    }

    @Test
    fun `statement endpoint should return structured statement lines via query parameter`() = testApplication {
        val bankService = BankService()
        application {
            bankModule(bankService = bankService)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val iban = "BE68BANKA0001234567"
        bankService.deposit(DepositRequest(iban = iban, amountCents = 100000))
        bankService.deposit(DepositRequest(iban = iban, amountCents = 200000))
        bankService.withdraw(WithdrawRequest(iban = iban, amountCents = 50000))

        val response = client.get("/api/statement?iban=$iban")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)

        val statement = response.body<StatementResponse>()
        assertThat(statement.iban).isEqualTo(iban)
        assertThat(statement.currentBalanceCents).isEqualTo(250000)
        assertThat(statement.lines).hasSize(3)
    }

    @Test
    fun `statement endpoint should return structured statement lines via path parameter`() = testApplication {
        val bankService = BankService()
        application {
            bankModule(bankService = bankService)
        }

        val client = createClient {
            install(ContentNegotiation) { json() }
        }

        val iban = "BE68BANKA0001234567"
        bankService.deposit(DepositRequest(iban = iban, amountCents = 666666))
        bankService.deposit(DepositRequest(iban = iban, amountCents = 666666))

        val response = client.get("/api/statement/$iban")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)

        val statement = response.body<StatementResponse>()
        assertThat(statement.iban).isEqualTo(iban)
        assertThat(statement.currentBalanceCents).isEqualTo(1333332)
        assertThat(statement.lines).hasSize(2)
    }
}
